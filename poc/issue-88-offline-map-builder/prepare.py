#!/usr/bin/env python3
"""Issue #88 POC: automated high-detail offline map preparation.

One command prepares a bounded offline map for a centre + geodesic radius:

    python3 prepare.py --input inputs/arinsal-20km.json \
        --cache ~/gnss-poc-88-work/cache --out ~/gnss-poc-88-work/runs/cold \
        --allowance-mb 2000 --command-binary ~/gnss-poc-88-work/bin/gnss-command

Stages: plan, osm, dem, landcover, imagery, package, baseline, report.
All external transfers go through netguard (explicit cap, byte ledger);
--offline denies every external connection and prepares from the cache only.
Dependencies: Python 3.10+ (stdlib only), GDAL >= 3.8 CLI tools, osmium-tool.
This is feasibility tooling, not production Command code.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from array import array
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import geo  # noqa: E402
import gterr  # noqa: E402
import netguard  # noqa: E402

PROFILES = {
    "standard": ["osm", "dem"],
    "high": ["osm", "dem", "landcover", "imagery"],
}
GEOFABRIK_INDEX = "https://download.geofabrik.de/index-v1.json"
COP_DEM = "https://copernicus-dem-30m.s3.amazonaws.com/{n}/{n}.tif"
SKADI = "https://elevation-tiles-prod.s3.amazonaws.com/skadi/{d}/{n}.hgt.gz"
WORLDCOVER = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_{t}_Map.tif"
EARTH_SEARCH = "https://earth-search.aws.element84.com/v1/search"
GEOID = {"egm96": "https://cdn.proj.org/us_nga_egm96_15.tif", "egm08": "https://cdn.proj.org/us_nga_egm08_25.tif"}
WORLDCOVER_CLASSES = {10: ("Tree cover", "#006400"), 20: ("Shrubland", "#ffbb22"), 30: ("Grassland", "#ffff4c"),
                      40: ("Cropland", "#f096ff"), 50: ("Built-up", "#fa0000"), 60: ("Bare / sparse", "#b4b4b4"),
                      70: ("Snow and ice", "#f0f0f0"), 80: ("Permanent water", "#0064c8"), 90: ("Herbaceous wetland", "#0096a0"),
                      95: ("Mangroves", "#00cf75"), 100: ("Moss and lichen", "#fae6a0")}
COMMAND_KEYS = ("highway", "building", "waterway", "natural", "landuse")
MERC_Z13 = 2 * math.pi * 6378137 / (256 * 2 ** 13)
MERC_Z12 = MERC_Z13 * 2


def utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Failure(Exception):
    pass


class Runner:
    def __init__(self, a: argparse.Namespace):
        self.a = a
        self.out = Path(a.out).expanduser().resolve()
        self.cache = Path(a.cache).expanduser().resolve()
        self.work = self.out / "_work"
        for p in (self.out, self.cache / "sources", self.work):
            p.mkdir(parents=True, exist_ok=True)
        cfg = json.loads(Path(a.input).read_text()) if a.input else {}
        self.name = cfg.get("name", "area")
        self.lat = float(a.lat if a.lat is not None else cfg["centre"][0])
        self.lon = float(a.lon if a.lon is not None else cfg["centre"][1])
        self.radius = float(a.radius_km if a.radius_km is not None else cfg.get("radius_km", 20)) * 1000
        self.profile = a.profile or cfg.get("profile", "high")
        self.layers = a.layers.split(",") if a.layers else PROFILES[self.profile]
        self.imagery_window = cfg.get("imagery", {})
        self.cap = int(a.allowance_mb * 1_000_000)
        self.server, self.ledger, self.proxy = netguard.start(self.cap, deny=a.offline)
        self.report = {"poc": "issue-88-offline-map-builder", "started_at": utc(), "offline": a.offline,
                       "input": {"name": self.name, "centre": [self.lat, self.lon], "radius_m": self.radius,
                                 "profile": self.profile, "layers": self.layers, "allowance_bytes": self.cap},
                       "environment": environment(), "stages": {}, "layers": {}, "commands": []}
        self.peak_disk = 0
        self.env = dict(os.environ)
        for k in list(self.env):
            if k.lower() in ("http_proxy", "https_proxy", "all_proxy", "no_proxy"):
                self.env.pop(k)
        self.env.update({"HTTPS_PROXY": self.proxy, "https_proxy": self.proxy, "HTTP_PROXY": self.proxy,
                         "http_proxy": self.proxy, "NO_PROXY": "127.0.0.1,localhost", "no_proxy": "127.0.0.1,localhost",
                         "GDAL_HTTP_MAX_RETRY": "2", "GDAL_HTTP_RETRY_DELAY": "2", "GDAL_HTTP_TIMEOUT": "60",
                         "CPL_VSIL_CURL_ALLOWED_EXTENSIONS": ".tif,.TIF",
                         "VSI_CACHE": "TRUE", "GDAL_PAM_ENABLED": "NO", "PROJ_NETWORK": "OFF", "GDAL_NUM_THREADS": "ALL_CPUS"})
        if a.gis_prefix:
            gp = Path(a.gis_prefix).expanduser()
            self.env["PATH"] = f"{gp / 'bin'}:{self.env.get('PATH', '')}"
            self.env["PROJ_DATA"] = str(gp / "share" / "proj")
            self.env["GDAL_DATA"] = str(gp / "share" / "gdal")
        os.environ["PATH"] = self.env["PATH"]
        self.report["environment"] = environment()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({"https": self.proxy, "http": self.proxy}))

    # ------------------------------------------------------------------ utils
    def run(self, cmd: list[str], stage: str, check: bool = True) -> subprocess.CompletedProcess:
        t0 = time.time()
        p = subprocess.run(["/usr/bin/time", "-l"] + cmd, env=self.env, capture_output=True, text=True)
        rss = 0
        m = re.search(r"(\d+)\s+maximum resident set size", p.stderr)
        if m:
            rss = int(m.group(1))
        self.report["commands"].append({"stage": stage, "cmd": " ".join(os.path.basename(c) if i == 0 else c for i, c in enumerate(cmd))[:400],
                                        "seconds": round(time.time() - t0, 3), "peak_rss_bytes": rss, "exit": p.returncode})
        self.disk()
        if check and p.returncode != 0:
            tool_err = re.split(r"\n?\s*[\d.]+ real\s", p.stderr)[0].strip()
            raise Failure(f"{os.path.basename(cmd[0])} failed: {tool_err[-800:]}")
        return p

    def disk(self) -> None:
        total = 0
        for root in (self.work, self.out):
            for dp, _, fs in os.walk(root):
                for f in fs:
                    try:
                        total += os.path.getsize(os.path.join(dp, f))
                    except OSError:
                        pass
        self.peak_disk = max(self.peak_disk, total)

    def remaining(self) -> int:
        return self.cap - self.ledger.snapshot()["received_bytes"]

    def fetch(self, url: str, stage: str, max_bytes: int | None = None) -> Path:
        """Cache-first bounded download with resume. Never exceeds allowance."""
        key = hashlib.sha256(url.encode()).hexdigest()[:24]
        dst = self.cache / "sources" / f"{key}-{os.path.basename(url.split('?')[0])}"
        meta_path = dst.with_name(dst.name + ".json")
        if dst.exists() and meta_path.exists():
            meta = json.loads(meta_path.read_text())
            if meta.get("bytes") == dst.stat().st_size:
                self.note(stage, {"source": url, "cache": "hit", "bytes": meta["bytes"], "sha256": meta["sha256"]})
                return dst
        if self.a.offline:
            raise Failure(f"not in local cache and offline: {url}")
        part = dst.with_name(dst.name + ".part")
        part_meta = dst.with_name(dst.name + ".part.json")
        headers, offset = {}, 0
        if part.exists() and part_meta.exists():
            pm = json.loads(part_meta.read_text())
            offset = part.stat().st_size
            headers["Range"] = f"bytes={offset}-"
            if pm.get("etag"):
                headers["If-Range"] = pm["etag"]
        req = urllib.request.Request(url, headers=headers)
        t0 = time.time()
        try:
            resp = self.opener.open(req, timeout=60)
        except urllib.error.HTTPError as e:
            raise Failure(f"HTTP {e.code} for {url}")
        except OSError as e:
            raise Failure(f"transfer failed for {url}: {e}")
        with resp:
            resumed = resp.status == 206
            if not resumed:
                offset = 0
            length = resp.headers.get("Content-Length")
            need = int(length) if length else None
            limit = min(self.remaining(), max_bytes or self.cap)
            if need is not None and need > limit:
                raise Failure(f"approval required: {url} announces {need} bytes, remaining allowance {limit}")
            part_meta.write_text(json.dumps({"url": url, "etag": resp.headers.get("ETag"), "last_modified": resp.headers.get("Last-Modified")}))
            with open(part, "ab" if resumed else "wb") as f:
                read = 0
                while True:
                    chunk = resp.read(1 << 20)
                    if not chunk:
                        break
                    read += len(chunk)
                    if read > limit:
                        raise Failure(f"allowance reached while reading {url}; partial file kept for resume")
                    f.write(chunk)
                    if self.a.interrupt_after_mb and read > self.a.interrupt_after_mb * 1_000_000:
                        raise Failure(f"simulated interruption after {read} bytes of {url}")
        if need is not None and part.stat().st_size != need + offset:
            raise Failure(f"incomplete transfer for {url}: {part.stat().st_size} of {need + offset}")
        h = hashlib.sha256()
        with open(part, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""):
                h.update(b)
        meta = {"url": url, "bytes": part.stat().st_size, "sha256": h.hexdigest(), "fetched_at": utc(),
                "resumed_from": offset if resumed else 0, "last_modified": resp.headers.get("Last-Modified"),
                "etag": resp.headers.get("ETag"), "seconds": round(time.time() - t0, 2)}
        os.replace(part, dst)
        part_meta.unlink(missing_ok=True)
        meta_path.write_text(json.dumps(meta, indent=1))
        self.note(stage, {"source": url, "cache": "miss", **meta})
        return dst

    def note(self, stage: str, item: dict) -> None:
        self.report["stages"].setdefault(stage, {}).setdefault("sources", []).append(item)

    def layer(self, name: str, status: str, **kw) -> None:
        self.report["layers"][name] = {"status": status, **kw}

    def save(self) -> None:
        self.report["ledger"] = self.ledger.snapshot()
        self.report["peak_disk_bytes"] = self.peak_disk
        (self.out / "run.json").write_text(json.dumps(self.report, indent=1))

    # ------------------------------------------------------------------- plan
    def plan(self) -> None:
        ring = geo.circle(self.lat, self.lon, self.radius)
        self.ring = ring
        self.bbox = geo.bbox(ring)
        step = 1 / 15  # Command's 240-arc-second chunk grid
        self.grid_bbox = (math.floor(self.bbox[0] / step) * step, math.floor(self.bbox[1] / step) * step,
                          math.ceil(self.bbox[2] / step) * step, math.ceil(self.bbox[3] / step) * step)
        (self.out / "plan").mkdir(exist_ok=True)
        circle = {"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {"name": self.name},
                                                            "geometry": {"type": "Polygon", "coordinates": [ring]}}]}
        (self.out / "plan" / "circle.geojson").write_text(json.dumps(circle))
        area_km2 = math.pi * (self.radius / 1000) ** 2
        self.report["plan"] = {"bbox": self.bbox, "chunk_grid_bbox": self.grid_bbox, "area_km2": round(area_km2, 1),
                               "circle_points": len(ring) - 1}

    # -------------------------------------------------------------------- osm
    def osm(self) -> None:
        st = "osm"
        t0 = time.time()
        idx = json.loads(self.fetch(GEOFABRIK_INDEX, st).read_text())
        bb = self.bbox
        cands = []
        for f in idx["features"]:
            g = f.get("geometry")
            if not g or "pbf" not in f["properties"].get("urls", {}):
                continue
            gb = geom_bbox(g)
            if gb[0] > bb[2] or gb[2] < bb[0] or gb[1] > bb[3] or gb[3] < bb[1]:
                continue
            cands.append((geom_area(g), f))
        samples = geo.sample_circle(self.lat, self.lon, self.radius, 1000)
        chosen, uncovered = {}, 0
        for x, y in samples:
            best = None
            for area, f in cands:
                gb = geom_bbox(f["geometry"])
                if gb[0] <= x <= gb[2] and gb[1] <= y <= gb[3] and geo.point_in_geometry(x, y, f["geometry"]):
                    if best is None or area < best[0]:
                        best = (area, f)
            if best is None:
                uncovered += 1
            else:
                chosen.setdefault(best[1]["properties"]["id"], [best[1], 0])[1] += 1
        regions = []
        for rid, (f, n) in sorted(chosen.items()):
            regions.append({"id": rid, "name": f["properties"]["name"], "pbf": f["properties"]["urls"]["pbf"], "samples": n})
        self.report["stages"].setdefault(st, {})["region_selection"] = {
            "method": "smallest Geofabrik polygon containing each 1 km circle sample (polygon + hole test)",
            "samples": len(samples), "uncovered_samples": uncovered, "regions": regions}
        if uncovered:
            raise Failure(f"{uncovered} circle samples are outside every Geofabrik extract")
        # Command admits a PBF crop only if its declared bounds cover every 240" chunk
        # envelope, so extract the whole chunk-grid envelope (data genuinely covers
        # what is declared) instead of declaring bounds larger than the data.
        e = 2e-7
        gb = self.grid_bbox
        bbox_arg = f"{gb[0] - e:.7f},{gb[1] - e:.7f},{gb[2] + e:.7f},{gb[3] + e:.7f}"
        crops = []
        for r in regions:
            pbf = self.fetch(r["pbf"], st)
            md5 = self.fetch(r["pbf"].replace(".osm.pbf", ".osm.pbf.md5"), st).read_text().split()[0]
            if file_md5(pbf) != md5:
                raise Failure(f"MD5 mismatch for {r['pbf']}")
            info = self.run(["osmium", "fileinfo", "-g", "header.option.osmosis_replication_timestamp", str(pbf)], st, check=False).stdout.strip()
            r["replication_timestamp"] = info or None
            crop = self.work / f"crop-{r['id'].replace('/', '_')}.osm.pbf"
            self.run(["osmium", "extract", "-b", bbox_arg, "-s", "complete_ways", "--set-bounds",
                      "--overwrite", "-o", str(crop), str(pbf)], st)
            crops.append(str(crop))
        (self.out / "osm").mkdir(exist_ok=True)
        area_pbf = self.out / "osm" / "area.osm.pbf"
        if len(crops) == 1:
            shutil.copy(crops[0], area_pbf)
        else:
            merged = self.work / "merged.osm.pbf"
            self.run(["osmium", "merge", "--overwrite", "-o", str(merged)] + crops, st)
            # merge drops the header bbox; re-declare the genuinely covered envelope.
            self.run(["osmium", "extract", "-b", bbox_arg, "-s", "complete_ways", "--set-bounds", "--overwrite",
                      "-o", str(area_pbf), str(merged)], st)
        header = self.run(["osmium", "fileinfo", "-g", "header.boxes", str(area_pbf)], st).stdout.strip()
        self.report["stages"][st]["declared_bounds"] = header
        self.report["stages"][st]["seconds_before_layers"] = round(time.time() - t0, 2)
        self.osm_layers(area_pbf)
        self.report["stages"][st]["seconds"] = round(time.time() - t0, 2)

    def osm_layers(self, area_pbf: Path) -> None:
        st = "osm"
        filtered = self.work / "filtered.osm.pbf"
        self.run(["osmium", "tags-filter", "--overwrite", "-o", str(filtered), str(area_pbf),
                  "nwr/highway", "wr/building", "nwr/waterway", "wr/natural", "nwr/water", "wr/landuse",
                  "n/place", "n/natural=peak,saddle,spring", "w/aerialway", "w/railway", "n/tourism=alpine_hut,wilderness_hut",
                  "n/amenity=shelter"], st)
        gj = self.work / "filtered.geojsonseq"
        self.run(["osmium", "export", "--overwrite", "-f", "geojsonseq", "-x", "print_record_separator=false",
                  "-a", "type,id", "-o", str(gj), str(filtered)], st)
        layers = {k: [] for k in ("water_areas", "landcover", "buildings", "waterways", "roads", "trails", "aerialways",
                                  "railways", "places", "peaks", "huts")}
        counts = {"command_renderable_ways": 0, "nodes_dropped_by_command": 0, "relations_dropped_by_command": 0,
                  "ways_outside_command_tag_scope": 0}
        for line in gj.open():
            f = json.loads(line)
            p, g = f["properties"], f["geometry"]
            otype = p.get("@type")
            cat = classify(p, g["type"])
            if cat:
                layers[cat].append({"type": "Feature", "geometry": round_geom(g), "properties": slim(p, cat)})
            if otype == "way":
                if any(p.get(k) for k in COMMAND_KEYS):
                    counts["command_renderable_ways"] += 1
                elif cat:
                    counts["ways_outside_command_tag_scope"] += 1
            elif otype == "node" and cat:
                counts["nodes_dropped_by_command"] += 1
            elif otype == "relation" and cat:
                counts["relations_dropped_by_command"] += 1
        d = self.out / "osm" / "layers"
        d.mkdir(parents=True, exist_ok=True)
        sizes = {}
        for k, feats in layers.items():
            path = d / f"{k}.geojson"
            path.write_text(json.dumps({"type": "FeatureCollection", "features": feats}, separators=(",", ":")))
            sizes[k] = {"features": len(feats), "bytes": path.stat().st_size}
        trails_named = sum(1 for f in layers["trails"] if f["properties"].get("name") or f["properties"].get("ref"))
        self.layer("osm", "ready", pbf_bytes=area_pbf.stat().st_size, layers=sizes, command_scope=counts,
                   trails_named_or_ref=trails_named,
                   regions=self.report["stages"][st]["region_selection"]["regions"],
                   attribution="© OpenStreetMap contributors, ODbL 1.0 (Geofabrik extracts)")

    # -------------------------------------------------------------------- dem
    def dem(self) -> None:
        st = "dem"
        t0 = time.time()
        gb = self.grid_bbox
        tiles = []
        for la in range(math.floor(gb[1]), math.floor(gb[3] - 1e-9) + 1):
            for lo in range(math.floor(gb[0]), math.floor(gb[2] - 1e-9) + 1):
                n = f"Copernicus_DSM_COG_10_{'N' if la >= 0 else 'S'}{abs(la):02d}_00_{'E' if lo >= 0 else 'W'}{abs(lo):03d}_00_DEM"
                tiles.append(str(self.fetch(COP_DEM.format(n=n), st)))
        d = self.out / "dem"
        d.mkdir(exist_ok=True)
        # Geoid windows (tiny) for an offline EGM2008 -> EGM96 vertical conversion.
        m = 0.5
        win = [str(gb[0] - m), str(gb[3] + m), str(gb[2] + m), str(gb[1] - m)]
        geoids = {}
        for k, url in GEOID.items():
            geoids[k] = self.window(url, win, st, f"geoid-{k}")
        vrt = self.work / "cop.vrt"
        self.run(["gdalbuildvrt", "-overwrite", str(vrt)] + tiles, st)
        # Native node window: integer arc-second nodes, chunk halo included.
        w = math.floor(gb[0] * 3600) - 2
        e = math.ceil(gb[2] * 3600) + 2
        s = math.floor(gb[1] * 3600) - 2
        n = math.ceil(gb[3] * 3600) + 2
        te = [str((w - 0.5) / 3600), str((s - 0.5) / 3600), str((e + 0.5) / 3600), str((n + 0.5) / 3600)]
        cop = self.work / "cop_egm08.tif"
        self.run(["gdalwarp", "-overwrite", "-te"] + te + ["-tr", str(1 / 3600), str(1 / 3600), "-r", "near", "-ot", "Float32",
                                                         str(vrt), str(cop)], st)
        grids = {}
        for k, path in geoids.items():
            grids[k] = self.work / f"n_{k}.tif"
            self.run(["gdalwarp", "-overwrite", "-te"] + te + ["-tr", str(1 / 3600), str(1 / 3600), "-r", "bilinear",
                                                             "-ot", "Float32", path, str(grids[k])], st)
        # Orthometric height change: H96 = H08 + N08 - N96 (explicit, auditable; no horizontal resampling of the DEM).
        dem_f = self.work / "dem_egm96_f32.tif"
        self.run(["gdal_calc", "--quiet", "--overwrite", "-A", str(cop), "-B", str(grids["egm08"]), "-C", str(grids["egm96"]),
                  "--calc", "A+B-C", "--type", "Float32", "--outfile", str(dem_f)], st)
        dem_tif = d / "dem_egm96.tif"
        dem_r = self.work / "dem_egm96_round.tif"
        self.run(["gdal_calc", "--quiet", "--overwrite", "-A", str(dem_f), "--calc", "numpy.round(A)", "--type", "Int16",
                  "--outfile", str(dem_r)], st)
        self.run(["gdal_translate", "-co", "COMPRESS=DEFLATE", "-co", "PREDICTOR=2", "-co", "TILED=YES",
                  str(dem_r), str(dem_tif)], st)
        bil = self.work / "dem_egm96.bil"
        self.run(["gdal_translate", "-of", "EHdr", "-ot", "Int16", str(dem_tif), str(bil)], st)
        self.dem_grid = {"bil": str(bil), "west": w, "north": n, "cols": e - w + 1, "rows": n - s + 1}
        # Verify the geotransform puts pixel centres on integer arc-second nodes.
        gi = json.loads(self.run(["gdalinfo", "-json", str(dem_tif)], st).stdout)
        gt = gi["geoTransform"]
        if abs(gt[0] * 3600 + 0.5 - w) > 1e-6 or abs(gt[3] * 3600 - 0.5 - n) > 1e-6:
            raise Failure(f"DEM grid not node-aligned: {gt}")
        # Geoid shift actually applied (EGM96 - EGM2008 heights) at the centre.
        orig = self.run(["gdallocationinfo", "-valonly", "-wgs84", str(vrt), str(self.lon), str(self.lat)], st).stdout.strip()
        conv = self.run(["gdallocationinfo", "-valonly", "-wgs84", str(dem_f), str(self.lon), str(self.lat)], st).stdout.strip()
        proj_check = "skipped offline"
        if not self.a.offline:
            env = dict(self.env, PROJ_NETWORK="ON", PROJ_USER_WRITABLE_DIRECTORY=str(self.cache / "proj"))
            p = subprocess.run(["cs2cs", "-f", "%.3f", "EPSG:4326+3855", "EPSG:4326+5773"], input=f"{self.lat} {self.lon} {orig}\n",
                               capture_output=True, text=True, env=env)
            try:
                ref = float(p.stdout.split()[2])
                proj_check = {"cs2cs_egm96": ref, "runner_egm96": float(conv), "difference_m": round(float(conv) - ref, 3)}
            except (IndexError, ValueError):
                proj_check = {"error": (p.stderr or p.stdout).strip()[:300]}
        # Reference products for the POC viewer.
        merc = self.work / "dem_3857.tif"
        self.run(["gdalwarp", "-overwrite", "-t_srs", "EPSG:3857", "-tr", str(MERC_Z12 / 2), str(MERC_Z12 / 2), "-r", "bilinear",
                  str(dem_tif), str(merc)], st)
        hs = self.work / "hillshade.tif"
        zf = 1 / math.cos(math.radians(self.lat))
        self.run(["gdaldem", "hillshade", "-multidirectional", "-compute_edges", "-z", f"{zf:.4f}", str(merc), str(hs)], st)
        hs_cut = self.work / "hillshade_cut.tif"
        self.run(["gdalwarp", "-overwrite", "-cutline", str(self.out / "plan" / "circle.geojson"), "-dstalpha",
                  str(hs), str(hs_cut)], st)
        hs_mb = d / "hillshade.mbtiles"
        self.mbtiles(hs_cut, hs_mb, st, "PNG", "Hillshade (Copernicus GLO-30)")
        contours = self.work / "contours.geojson"
        contours.unlink(missing_ok=True)
        self.run(["gdal_contour", "-a", "ele", "-i", "20", "-f", "GeoJSON", str(dem_tif), str(contours)], st)
        cont_out = d / "contours.geojson"
        cont_out.unlink(missing_ok=True)
        self.run(["ogr2ogr", "-f", "GeoJSON", "-clipsrc", str(self.out / "plan" / "circle.geojson"),
                  "-lco", "COORDINATE_PRECISION=5", str(cont_out), str(contours)], st)
        stats = self.dem_stats(bil)
        cmp = self.compare_skadi(st)
        self.layer("dem", "ready", source="Copernicus DEM GLO-30 (AWS Open Data COG, 1 arc-second)",
                   vertical="EGM2008 to EGM96: H96 = H08 + N08 - N96 with NGA geoid grids (cdn.proj.org windows, bilinear on DEM nodes); checked against PROJ cs2cs",
                   centre_original_egm2008=orig, centre_converted_egm96=conv, proj_independent_check=proj_check, grid_nodes=[e - w + 1, n - s + 1],
                   hillshade_mbtiles_bytes=hs_mb.stat().st_size, contours_bytes=cont_out.stat().st_size,
                   stats_in_circle=stats, skadi_comparison=cmp,
                   attribution="Copernicus DEM GLO-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018, provided under COPERNICUS by the European Union and ESA",
                   seconds=round(time.time() - t0, 2))

    def window(self, url: str, projwin: list[str], stage: str, label: str, extra: list[str] | None = None) -> str:
        """Windowed COG read through the guard, cached by (url, window)."""
        key = hashlib.sha256((url + "|" + ",".join(projwin) + "|" + ",".join(extra or [])).encode()).hexdigest()[:20]
        dst = self.cache / "sources" / f"window-{label}-{key}.tif"
        if dst.exists() and dst.with_suffix(".json").exists():
            self.note(stage, {"source": url, "window": projwin, "cache": "hit", "bytes": dst.stat().st_size})
            return str(dst)
        if self.a.offline:
            raise Failure(f"window not cached and offline: {url}")
        before = self.ledger.snapshot()["received_bytes"]
        tmp = dst.with_suffix(".part.tif")
        self.run(["gdal_translate", "--config", "GDAL_DISABLE_READDIR_ON_OPEN", "EMPTY_DIR", "-projwin"] + projwin + (extra or []) + ["-co", "COMPRESS=DEFLATE", "-co", "TILED=YES",
                                                                             f"/vsicurl/{url}", str(tmp)], stage)
        got = self.ledger.snapshot()["received_bytes"] - before
        os.replace(tmp, dst)
        dst.with_suffix(".json").write_text(json.dumps({"url": url, "window": projwin, "transfer_bytes": got, "at": utc()}))
        self.note(stage, {"source": url, "window": projwin, "cache": "miss", "transfer_bytes": got, "bytes": dst.stat().st_size})
        return str(dst)

    def mbtiles(self, src: Path, dst: Path, stage: str, fmt: str, name: str, strategy: str = "LOWER") -> None:
        if dst.exists():
            dst.unlink()
        self.run(["gdal_translate", "-of", "MBTILES", "-co", f"TILE_FORMAT={fmt}", "-co", f"ZOOM_LEVEL_STRATEGY={strategy}",
                  "-co", f"NAME={name}", "-co", "QUALITY=82", str(src), str(dst)], stage)
        self.run(["gdaladdo", "-r", "average", str(dst), "2", "4", "8", "16", "32"], stage)

    def dem_stats(self, bil: Path) -> dict:
        g = self.dem_grid
        a = array("h")
        a.frombytes(Path(bil).read_bytes())
        if sys.byteorder == "big":
            a.byteswap()
        lo, hi, valid, void, total = 99999, -99999, 0, 0, 0
        cos = math.cos(math.radians(self.lat))
        for r in range(0, g["rows"], 3):
            y = (g["north"] - r) / 3600
            for c in range(0, g["cols"], 3):
                x = (g["west"] + c) / 3600
                if ((x - self.lon) * 111320 * cos) ** 2 + ((y - self.lat) * 111320) ** 2 > self.radius ** 2:
                    continue
                total += 1
                v = a[r * g["cols"] + c]
                if -12000 <= v <= 9000:
                    valid += 1
                    lo, hi = min(lo, v), max(hi, v)
                else:
                    void += 1
        return {"sampled_nodes": total, "valid_pct": round(100 * valid / max(total, 1), 3), "min_m": lo, "max_m": hi,
                "sampling": "every 3rd node inside circle"}

    def compare_skadi(self, stage: str) -> dict:
        """Independent check: Copernicus (converted to EGM96) vs Skadi composite on identical nodes."""
        out = {}
        g = self.dem_grid
        a = array("h")
        a.frombytes(Path(g["bil"]).read_bytes())
        if sys.byteorder == "big":
            a.byteswap()
        diffs = []
        cos = math.cos(math.radians(self.lat))
        tiles = {}
        for la in range(math.floor(self.bbox[1]), math.floor(self.bbox[3]) + 1):
            for lo in range(math.floor(self.bbox[0]), math.floor(self.bbox[2]) + 1):
                name = f"{'N' if la >= 0 else 'S'}{abs(la):02d}{'E' if lo >= 0 else 'W'}{abs(lo):03d}"
                try:
                    raw = gzip.decompress(self.fetch(SKADI.format(d=name[:3], n=name), stage).read_bytes())
                except Failure as e:
                    out.setdefault("errors", []).append(str(e))
                    continue
                t = array("h")
                t.frombytes(raw)
                if sys.byteorder == "little":
                    t.byteswap()
                tiles[(la, lo)] = t
        if not tiles:
            return {"status": "skadi unavailable", **out}
        for r in range(0, g["rows"], 4):
            ny = g["north"] - r
            y = ny / 3600
            for c in range(0, g["cols"], 4):
                nx = g["west"] + c
                x = nx / 3600
                if ((x - self.lon) * 111320 * cos) ** 2 + ((y - self.lat) * 111320) ** 2 > self.radius ** 2:
                    continue
                la, lo = math.floor(y - 1e-12), math.floor(x)
                t = tiles.get((la, lo))
                if t is None:
                    continue
                row = (la + 1) * 3600 - ny
                col = nx - lo * 3600
                if not (0 <= row <= 3600 and 0 <= col <= 3600):
                    continue
                sv, cv = t[row * 3601 + col], a[r * g["cols"] + c]
                if sv == -32768 or not (-12000 <= cv <= 9000):
                    continue
                diffs.append(cv - sv)
        diffs.sort()
        n = len(diffs)
        absd = sorted(abs(d) for d in diffs)
        return {"nodes": n, "mean_m": round(sum(diffs) / n, 2), "rmse_m": round(math.sqrt(sum(d * d for d in diffs) / n), 2),
                "median_abs_m": absd[n // 2], "p95_abs_m": absd[int(n * 0.95)], "max_abs_m": absd[-1],
                "note": "Copernicus GLO-30 (DSM, EGM96-converted) minus Skadi composite (EGM96) at identical 1\" nodes; every 4th node in circle"}

    # -------------------------------------------------------------- landcover
    def landcover(self) -> None:
        st = "landcover"
        t0 = time.time()
        bb = self.bbox
        m = 0.002
        projwin = [str(bb[0] - m), str(bb[3] + m), str(bb[2] + m), str(bb[1] - m)]
        parts = []
        for la in range(math.floor(bb[1] / 3) * 3, math.floor(bb[3] / 3) * 3 + 1, 3):
            for lo in range(math.floor(bb[0] / 3) * 3, math.floor(bb[2] / 3) * 3 + 1, 3):
                t = f"{'N' if la >= 0 else 'S'}{abs(la):02d}{'E' if lo >= 0 else 'W'}{abs(lo):03d}"
                parts.append(self.window(WORLDCOVER.format(t=t), projwin, st, f"worldcover-{t}"))
        d = self.out / "landcover"
        d.mkdir(exist_ok=True)
        src = parts[0]
        if len(parts) > 1:
            src = str(self.work / "wc.vrt")
            self.run(["gdalbuildvrt", "-overwrite", src] + parts, st)
        # Class statistics inside the circle (255 = outside).
        inside = self.work / "wc_inside.tif"
        self.run(["gdalwarp", "-overwrite", "-cutline", str(self.out / "plan" / "circle.geojson"), "-srcnodata", "None",
                  "-dstnodata", "255", "-r", "near", src, str(inside)], st)
        hist = self.histogram(inside, st)
        total = sum(v for k, v in hist.items() if k != 255)
        classes = {WORLDCOVER_CLASSES[k][0]: round(100 * v / total, 2) for k, v in sorted(hist.items()) if k in WORLDCOVER_CLASSES}
        nodata_pct = round(100 * hist.get(0, 0) / max(total, 1), 3)
        pal = self.work / "wc_palette.txt"
        pal.write_text("0 0 0 0 0\n" + "".join(f"{k} {int(c[1][1:3], 16)} {int(c[1][3:5], 16)} {int(c[1][5:7], 16)} 255\n"
                                               for k, c in WORLDCOVER_CLASSES.items()) + "255 0 0 0 0\n")
        rgba = self.work / "wc_rgba.tif"
        self.run(["gdaldem", "color-relief", "-alpha", "-nearest_color_entry", str(inside), str(pal), str(rgba)], st)
        merc = self.work / "wc_3857.tif"
        self.run(["gdalwarp", "-overwrite", "-t_srs", "EPSG:3857", "-tr", str(MERC_Z13), str(MERC_Z13), "-r", "mode",
                  str(rgba), str(merc)], st)
        mb = d / "worldcover.mbtiles"
        self.mbtiles(merc, mb, st, "PNG", "ESA WorldCover 2021 v200")
        status = "ready" if nodata_pct < 0.1 else "ready_with_limitations"
        self.layer("landcover", status, source="ESA WorldCover 10 m 2021 v200 (AWS Open Data COG, windowed read)",
                   native_resolution_m=10, rendered_max_zoom=13, classes_pct_in_circle=classes, nodata_pct=nodata_pct,
                   mbtiles_bytes=mb.stat().st_size, licence="CC BY 4.0",
                   attribution="© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium",
                   seconds=round(time.time() - t0, 2))

    def histogram(self, path: Path, stage: str) -> dict:
        """Exact integer value counts for a Byte raster (nodata excluded by GDAL)."""
        out = self.run(["gdalinfo", "-hist", "-json", str(path)], stage).stdout
        b = json.loads(out)["bands"][0]["histogram"]
        lo, hi, n = b["min"], b["max"], b["count"]
        width = (hi - lo) / n
        if abs(width - 1) > 1e-9:
            raise Failure(f"unexpected histogram bucket width {width} for {path}")
        return {int(math.floor(lo + (i + 0.5) * width)): c for i, c in enumerate(b["buckets"]) if c}

    def circle_mask_pct(self, merc_raster: Path, stage: str) -> float:
        """Share of requested-circle pixels that hold valid data (alpha > 0)."""
        circ = self.work / "circle_3857.geojson"
        if not circ.exists():
            self.run(["ogr2ogr", "-f", "GeoJSON", "-t_srs", "EPSG:3857", str(circ), str(self.out / "plan" / "circle.geojson")], stage)
        info = json.loads(self.run(["gdalinfo", "-json", str(merc_raster)], stage).stdout)
        gt, (w, h) = info["geoTransform"], info["size"]
        mask = self.work / f"mask-{merc_raster.stem}.tif"
        self.run(["gdal_rasterize", "-q", "-burn", "1", "-init", "0", "-ot", "Byte", "-te", str(gt[0]), str(gt[3] + gt[5] * h),
                  str(gt[0] + gt[1] * w), str(gt[3]), "-ts", str(w), str(h), str(circ), str(mask)], stage)
        both = self.work / f"valid-{merc_raster.stem}.tif"
        bands = len(info["bands"])
        self.run(["gdal_calc", "--quiet", "--overwrite", "-A", str(mask), "-B", str(merc_raster), f"--B_band={bands}",
                  "--calc", "(A>0).astype(numpy.uint8)*(B>0)+(A>0).astype(numpy.uint8)", "--type", "Byte", "--NoDataValue", "0", "--outfile", str(both)], stage)
        h2 = self.histogram(both, stage)  # 1 = inside circle without data, 2 = inside circle with data
        return round(100 * h2.get(2, 0) / max(h2.get(1, 0) + h2.get(2, 0), 1), 3)

    # ---------------------------------------------------------------- imagery
    def imagery(self) -> None:
        st = "imagery"
        t0 = time.time()
        bb = self.bbox
        win = self.imagery_window
        cache_key = self.cache / "sources" / f"stac-{hashlib.sha256(json.dumps([bb, win]).encode()).hexdigest()[:16]}.json"
        if cache_key.exists():
            items = json.loads(cache_key.read_text())
            self.note(st, {"source": EARTH_SEARCH, "cache": "hit"})
        else:
            if self.a.offline:
                raise Failure("Sentinel-2 catalogue not cached and offline")
            body = {"collections": ["sentinel-2-l2a"], "bbox": list(bb), "limit": 200,
                    "datetime": win.get("datetime", "2025-06-15T00:00:00Z/2026-09-30T23:59:59Z"),
                    "query": {"eo:cloud_cover": {"lt": win.get("max_scene_cloud", 10)}}}
            items = []
            url = EARTH_SEARCH
            for _ in range(5):
                req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
                with self.opener.open(req, timeout=60) as r:
                    page = json.loads(r.read())
                items += page.get("features", [])
                nxt = [l for l in page.get("links", []) if l.get("rel") == "next"]
                if not nxt:
                    break
                body = nxt[0].get("body", body)
                url = nxt[0]["href"]
            cache_key.write_text(json.dumps(items))
            self.note(st, {"source": EARTH_SEARCH, "cache": "miss", "items": len(items)})
        months = set(win.get("months", [6, 7, 8, 9]))
        samples = geo.sample_circle(self.lat, self.lon, self.radius, 2000)
        groups = {}
        for it in items:
            p = it["properties"]
            if int(p["datetime"][5:7]) not in months:
                continue
            if "visual" not in it["assets"] or "scl" not in it["assets"]:
                continue
            groups.setdefault(p["datetime"][:10], []).append(it)
        ranked = []
        for date, its in groups.items():
            covered = sum(1 for x, y in samples if any(geo.point_in_geometry(x, y, i["geometry"]) for i in its))
            if covered < len(samples):
                continue
            ranked.append((max(i["properties"]["eo:cloud_cover"] for i in its), date, its))
        ranked.sort(key=lambda r: (r[0], r[1]))
        if not ranked:
            raise Failure("no Sentinel-2 date fully covers the circle within the cloud/season window")
        tried = []
        d = self.out / "imagery"
        d.mkdir(exist_ok=True)
        merc_bb = merc_bbox(bb)
        chosen = None
        for cloud, date, its in ranked[: int(win.get("max_candidates", 3))]:
            # Prefer the newest processing baseline when duplicates exist per MGRS tile.
            by_tile = {}
            for i in its:
                t = i["properties"].get("grid:code") or i["id"].split("_")[1]
                cur = by_tile.get(t)
                if cur is None or str(i["properties"].get("s2:processing_baseline", "")) > str(cur["properties"].get("s2:processing_baseline", "")):
                    by_tile[t] = i
            its = list(by_tile.values())
            scl = self.scene_cloud(its, date, st)
            tried.append({"date": date, "scene_cloud_max_pct": cloud, "aoi": scl, "items": [i["id"] for i in its]})
            if scl["cloud_and_shadow_pct"] <= win.get("max_aoi_cloud_pct", 3):
                chosen = (date, its, scl, cloud)
                break
        self.report["stages"].setdefault(st, {})["candidates"] = tried
        if chosen is None:
            self.layer("imagery", "failed", reason="no candidate below the AOI cloud threshold", candidates=tried)
            return
        date, its, scl, cloud = chosen
        visuals = []
        for i in its:
            visuals.append(self.window(i["assets"]["visual"]["href"], [str(merc_bb[0]), str(merc_bb[3]), str(merc_bb[2]), str(merc_bb[1])],
                                       st, f"s2-visual-{i['id']}", ["-projwin_srs", "EPSG:3857"]))
        vrt = self.work / "s2.vrt"
        self.run(["gdalbuildvrt", "-overwrite", "-srcnodata", "0", str(vrt)] + visuals, st)
        merc = self.work / "s2_3857.tif"
        self.run(["gdalwarp", "-overwrite", "-t_srs", "EPSG:3857", "-tr", str(MERC_Z13), str(MERC_Z13), "-r", "average",
                  "-te"] + [str(v) for v in merc_bb] + ["-cutline", str(self.out / "plan" / "circle.geojson"), "-dstalpha",
                                                       "-co", "COMPRESS=DEFLATE", str(vrt), str(merc)], st)
        mb = d / "sentinel2.mbtiles"
        self.mbtiles(merc, mb, st, "JPEG", f"Sentinel-2 L2A true colour {date}")
        valid = self.circle_mask_pct(merc, st)
        status = "ready" if valid >= 99.9 and scl["cloud_and_shadow_pct"] <= 1 else "ready_with_limitations"
        self.layer("imagery", status, source="Sentinel-2 L2A true-colour (TCI) via Earth Search STAC on AWS Open Data",
                   date=date, items=[i["id"] for i in its], scene_cloud_max_pct=cloud, aoi_quality=scl,
                   native_resolution_m=10, rendered_max_zoom=13, rendered_ground_m_per_px=round(MERC_Z13 * math.cos(math.radians(self.lat)), 1),
                   valid_pixels_in_circle_pct=valid, mbtiles_bytes=mb.stat().st_size,
                   attribution=f"Contains modified Copernicus Sentinel data {date[:4]}, processed by ESA; catalogue: Element 84 Earth Search",
                   seconds=round(time.time() - t0, 2))

    def scene_cloud(self, its: list, date: str, stage: str) -> dict:
        """Measure clouds/shadow/snow inside the circle from the 20 m SCL band."""
        bb = self.bbox
        merc_bb = merc_bbox(bb)
        parts = [self.window(i["assets"]["scl"]["href"], [str(merc_bb[0]), str(merc_bb[3]), str(merc_bb[2]), str(merc_bb[1])],
                             stage, f"s2-scl-{i['id']}", ["-projwin_srs", "EPSG:3857"]) for i in its]
        vrt = self.work / f"scl-{date}.vrt"
        self.run(["gdalbuildvrt", "-overwrite", "-srcnodata", "0", str(vrt)] + parts, stage)
        cut = self.work / f"scl-{date}.tif"
        self.run(["gdalwarp", "-overwrite", "-t_srs", "EPSG:4326", "-cutline", str(self.out / "plan" / "circle.geojson"),
                  "-srcnodata", "None", "-dstnodata", "255", "-r", "near", str(vrt), str(cut)], stage)
        h = self.histogram(cut, stage)
        total = sum(h.values())
        pct = lambda ks: round(100 * sum(h.get(k, 0) for k in ks) / max(total, 1), 2)  # noqa: E731
        return {"cloud_and_shadow_pct": pct([3, 8, 9, 10]), "snow_pct": pct([11]), "nodata_pct": pct([0]),
                "saturated_pct": pct([1]), "class_counts": {str(k): v for k, v in sorted(h.items())},
                "method": "Sentinel-2 SCL classes 3,8,9,10 within circle (20 m)"}

    def alpha_coverage(self, path: Path, stage: str) -> float:
        out = json.loads(self.run(["gdalinfo", "-stats", "-json", str(path)], stage).stdout)
        a = out["bands"][-1]
        return round(100 * a.get("mean", 0) / 255, 2)

    # ---------------------------------------------------------------- package
    def package(self) -> None:
        from command_bridge import Command
        st = "package"
        t0 = time.time()
        d = self.out / "command"
        d.mkdir(exist_ok=True)
        binary = self.a.command_binary
        cmd = Command(binary, str(self.work / "pkg.sqlite"), str(d / "command-package.log"), self.proxy, sandbox_offline=self.a.offline)
        try:
            imp = cmd.import_pbf(str(self.out / "osm" / "area.osm.pbf"), self.lat, self.lon, self.radius)
            mid = imp["id"]
            man = cmd.manifest(mid)
            chunks = [c["key"] for c in man["plan"]["chunks"]]
            results = {}
            for k in chunks:
                results[k] = cmd.acquire(mid, k, "vector")
            failed = {k: v for k, v in results.items() if v["status"] != "complete"}
            vec_zip = self.work / "command-vectors.gmap.zip"
            cmd.export(mid, str(vec_zip))
            plan = man["plan"]
        finally:
            cmd.stop()
        # Independent geographic check: Command's canonical plan vs our Vincenty bbox.
        pb = plan["bounds"]
        dem = gterr.Dem(self.dem_grid["bil"], self.dem_grid["west"], self.dem_grid["north"], self.dem_grid["cols"],
                        self.dem_grid["rows"], None)
        comps = {"hillshade": True, "contours": True, "elevation": True}
        prov = {"provider": "Copernicus DEM GLO-30 via AWS Open Data (POC)", "acquired_at": utc(),
                "attribution": "Copernicus DEM GLO-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018, provided under COPERNICUS by the European Union and ESA",
                "terms_url": "https://spacedata.copernicus.eu/documents/20123/121286/CSCDA_ESA_Mission-specific+Annex_31_Oct_22.pdf",
                "tiles": []}
        enhanced = d / f"{self.name}-enhanced.gmap.zip"
        terr = gterr.augment_package(str(vec_zip), str(enhanced), dem, comps, prov, utc(),
                                     "https://copernicus-dem-30m.s3.amazonaws.com/")
        # Validate by importing into a fresh, unmodified Command.
        cmd = Command(binary, str(self.work / "verify.sqlite"), str(d / "command-verify.log"), self.proxy, sandbox_offline=self.a.offline)
        try:
            status, body = cmd.import_package(str(enhanced))
            verify = {"http": status, "response": body}
            if status == 200:
                vm = cmd.manifest(body["id"])
                complete_t = sum(1 for c in vm["chunks"] if c["terrain"]["status"] == "complete")
                complete_v = sum(1 for c in vm["chunks"] if c["vector"]["status"] == "complete")
                verify.update({"chunks": len(vm["chunks"]), "vector_complete": complete_v, "terrain_complete": complete_t})
                # Byte-level check of one terrain chunk against our own grid.
                k = vm["chunks"][len(vm["chunks"]) // 2]["key"]
                g = cmd.get_json(f"/local/maps/{body['id']}/chunks/{k}?layer=terrain")
                w, s, e, n = gterr.chunk_grid(next(c["bounds"] for c in plan["chunks"] if c["key"] == k))
                mism = 0
                for i, v in enumerate(g["samples"]):
                    r, c = divmod(i, g["columns"])
                    if v != dem.value(w + c, n - r):
                        mism += 1
                verify["sample_check"] = {"chunk": k, "samples": len(g["samples"]), "mismatches": mism}
            # Corrupt package must be rejected without touching the saved map.
            bad = self.work / "corrupt.gmap.zip"
            gterr.augment_package(str(vec_zip), str(bad), dem, comps, prov, utc(), "corrupt-test",
                                  corrupt_key=plan["chunks"][0]["key"])
            before = cmd.maps()
            s2, b2 = cmd.import_package(str(bad))
            after = cmd.maps()
            verify["corrupt_package"] = {"http": s2, "error": b2.get("message") or b2.get("error"),
                                         "maps_before": len(before), "maps_after": len(after),
                                         "prior_map_retained": [m["id"] for m in before] == [m["id"] for m in after]}
        finally:
            cmd.stop()
        sizes = zip_sizes(enhanced)
        self.layer("command_package", "ready" if not failed and verify.get("http") == 200 else "partial",
                   plan_bounds=pb, our_bbox=list(self.bbox), plan_chunks=len(chunks), vector_failures=failed,
                   terrain_chunks={k: v["status"] for k, v in terr.items() if v["status"] != "complete"} or "all complete",
                   package_bytes=enhanced.stat().st_size, package_expanded=sizes, quota_256mib_pct=round(100 * sizes["expanded"] / (256 * 2 ** 20), 2),
                   verify_import=verify, seconds=round(time.time() - t0, 2))

    def baseline(self) -> None:
        """Current Command path for the same area: same OSM crop through the
        existing PBF import, terrain through Command's own Skadi acquisition."""
        from command_bridge import Command
        st = "baseline"
        t0 = time.time()
        d = self.out / "command"
        d.mkdir(exist_ok=True)
        before = self.ledger.snapshot()["received_bytes"]
        cmd = Command(self.a.command_binary, str(self.work / "baseline.sqlite"), str(d / "command-baseline.log"), self.proxy,
                      sandbox_offline=self.a.offline)
        try:
            mid = cmd.import_pbf(str(self.out / "osm" / "area.osm.pbf"), self.lat, self.lon, self.radius)["id"]
            chunks = [c["key"] for c in cmd.manifest(mid)["plan"]["chunks"]]
            vec = {k: cmd.acquire(mid, k, "vector") for k in chunks}
            cmd.set_products(mid, {"hillshade": True, "contours": True, "elevation": True})
            ter = {}
            for k in chunks:
                ter[k] = cmd.acquire(mid, k, "terrain", approval_cap=int(self.a.baseline_approval_mb * 1_000_000))
            out = d / f"{self.name}-baseline.gmap.zip"
            cmd.export(mid, str(out))
            transfer = cmd.manifest(mid)["transfer"]
        finally:
            cmd.stop()
        approvals = [a for v in ter.values() for a in v.get("approvals", [])]
        tfail = {k: v for k, v in ter.items() if v["status"] != "complete"}
        vfail = {k: v for k, v in vec.items() if v["status"] != "complete"}
        sizes = zip_sizes(out)
        self.layer("command_baseline", "ready" if not tfail and not vfail else "partial",
                   description="Unmodified Command: local PBF import of the same OSM crop + automatic Skadi terrain",
                   vector_failures=vfail, terrain_failures=tfail, approvals=approvals, command_transfer_ledger=transfer,
                   guard_measured_bytes=self.ledger.snapshot()["received_bytes"] - before,
                   package_bytes=out.stat().st_size, package_expanded=sizes,
                   quota_256mib_pct=round(100 * sizes["expanded"] / (256 * 2 ** 20), 2), seconds=round(time.time() - t0, 2))

    # ----------------------------------------------------------------- driver
    def main(self) -> int:
        stages = self.a.stages.split(",")
        self.plan()
        order = [("osm", self.osm), ("dem", self.dem), ("landcover", self.landcover), ("imagery", self.imagery)]
        failed = False
        for name, fn in order:
            if name not in self.layers or name not in stages:
                self.layer(name, "skipped")
                continue
            t0 = time.time()
            try:
                fn()
            except Failure as e:
                failed = True
                self.layer(name, "failed", error=str(e), seconds=round(time.time() - t0, 2))
            self.save()
        if "package" in stages:
            if self.report["layers"].get("osm", {}).get("status") == "ready" and self.report["layers"].get("dem", {}).get("status") == "ready":
                try:
                    self.package()
                except (Failure, RuntimeError) as e:
                    failed = True
                    self.layer("command_package", "failed", error=str(e))
            else:
                self.layer("command_package", "blocked", reason="needs ready OSM and DEM layers")
            self.save()
        if "baseline" in stages and self.report["layers"].get("osm", {}).get("status") == "ready":
            try:
                self.baseline()
            except (Failure, RuntimeError) as e:
                failed = True
                self.layer("command_baseline", "failed", error=str(e))
        self.report["finished_at"] = utc()
        self.report["overall"] = overall(self.report["layers"], self.layers)
        self.save()
        shutil.rmtree(self.work, ignore_errors=True) if not self.a.keep_work else None
        print(json.dumps({"overall": self.report["overall"], "layers": {k: v["status"] for k, v in self.report["layers"].items()},
                          "received_bytes": self.ledger.snapshot()["received_bytes"], "peak_disk_bytes": self.peak_disk,
                          "report": str(self.out / "run.json")}, indent=1))
        return 1 if failed else 0


# ------------------------------------------------------------------ helpers
def overall(layers: dict, wanted: list) -> str:
    st = [layers.get(k, {}).get("status") for k in wanted]
    if all(s == "ready" for s in st):
        return "ready"
    if all(s in ("ready", "ready_with_limitations") for s in st):
        return "ready_with_limitations"
    if any(s in ("ready", "ready_with_limitations") for s in st):
        return "partial"
    return "failed"


def environment() -> dict:
    def ver(cmd):
        try:
            return subprocess.run(cmd, capture_output=True, text=True).stdout.strip().splitlines()[0]
        except Exception:
            return None
    return {"python": sys.version.split()[0], "gdal": ver(["gdalinfo", "--version"]), "osmium": ver(["osmium", "--version"]),
            "proj": ver(["projinfo"]) and subprocess.run(["projinfo"], capture_output=True, text=True).stderr.strip()[:80],
            "platform": ver(["uname", "-mrs"]),
            "cpu": ver(["sysctl", "-n", "machdep.cpu.brand_string"]),
            "memory_bytes": ver(["sysctl", "-n", "hw.memsize"])}


def file_md5(p: Path) -> str:
    h = hashlib.md5()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def geom_bbox(g: dict) -> tuple:
    xs, ys = [], []
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    for p in polys:
        for x, y in p[0]:
            xs.append(x)
            ys.append(y)
    return (min(xs), min(ys), max(xs), max(ys))


def geom_area(g: dict) -> float:
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    total = 0.0
    for p in polys:
        r = p[0]
        s = 0.0
        for i in range(len(r) - 1):
            s += r[i][0] * r[i + 1][1] - r[i + 1][0] * r[i][1]
        total += abs(s) / 2 * math.cos(math.radians(r[0][1]))
    return total


def merc_bbox(bb) -> list[float]:
    def m(lon, lat):
        return lon * math.pi / 180 * 6378137, math.log(math.tan(math.pi / 4 + math.radians(lat) / 2)) * 6378137
    x0, y0 = m(bb[0], bb[1])
    x1, y1 = m(bb[2], bb[3])
    return [x0, y0, x1, y1]


TRAILS = {"path", "footway", "track", "bridleway", "steps", "via_ferrata", "cycleway"}
LANDCOVER = {"wood", "forest", "scrub", "grassland", "heath", "bare_rock", "scree", "glacier", "shingle", "meadow", "fell", "rock"}


def classify(p: dict, gtype: str) -> str | None:
    area = gtype in ("Polygon", "MultiPolygon")
    if p.get("place") and p.get("name") and gtype == "Point":
        return "places"
    if p.get("natural") in ("peak", "saddle") and gtype == "Point":
        return "peaks"
    if gtype == "Point" and (p.get("tourism") in ("alpine_hut", "wilderness_hut") or p.get("amenity") == "shelter"):
        return "huts"
    if gtype == "Point":
        return None
    if p.get("building") and area:
        return "buildings"
    if area and (p.get("natural") == "water" or p.get("water") or p.get("landuse") == "reservoir"):
        return "water_areas"
    if area and (p.get("landuse") in ("forest", "meadow", "grass", "residential", "farmland", "orchard", "industrial", "commercial", "retail")
                 or p.get("natural") in LANDCOVER):
        return "landcover"
    if p.get("waterway") and not area:
        return "waterways"
    if p.get("highway") and not area:
        return "trails" if p["highway"] in TRAILS else "roads"
    if p.get("aerialway") and not area:
        return "aerialways"
    if p.get("railway") and not area:
        return "railways"
    return None


KEEP = {"name", "ref", "highway", "waterway", "natural", "landuse", "place", "ele", "sac_scale", "trail_visibility",
        "aerialway", "railway", "building", "tourism", "amenity", "population", "intermittent", "water", "tracktype", "surface"}


def slim(p: dict, cat: str) -> dict:
    out = {k: v for k, v in p.items() if k in KEEP}
    out["@id"] = f"{p.get('@type', '?')[0]}{p.get('@id')}"
    return out


def round_geom(g: dict) -> dict:
    def r(c):
        if isinstance(c[0], (int, float)):
            return [round(c[0], 6), round(c[1], 6)]
        return [r(x) for x in c]
    return {"type": g["type"], "coordinates": r(g["coordinates"])}


def zip_sizes(path: Path) -> dict:
    import zipfile
    with zipfile.ZipFile(path) as z:
        infos = z.infolist()
        return {"expanded": sum(i.file_size for i in infos), "terrain": sum(i.file_size for i in infos if i.filename.endswith(".terrain")),
                "vector": sum(i.file_size for i in infos if i.filename.endswith(".vector")), "entries": len(infos)}


def parse() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", help="JSON input (name, centre [lat, lon], radius_km, profile, imagery window)")
    p.add_argument("--lat", type=float)
    p.add_argument("--lon", type=float)
    p.add_argument("--radius-km", type=float)
    p.add_argument("--profile", choices=sorted(PROFILES))
    p.add_argument("--layers", help="comma list overriding the profile: osm,dem,landcover,imagery")
    p.add_argument("--allowance-mb", type=float, required=True, help="explicit total external transfer allowance (MB)")
    p.add_argument("--cache", required=True, help="reusable regional source cache")
    p.add_argument("--out", required=True, help="output directory for this run")
    p.add_argument("--offline", action="store_true", help="deny all external connections; prepare from cache only")
    p.add_argument("--stages", default="osm,dem,landcover,imagery,package,baseline")
    p.add_argument("--command-binary", default=os.path.expanduser("~/gnss-poc-88-work/bin/gnss-command"))
    p.add_argument("--baseline-approval-mb", type=float, default=60, help="max cumulative transfer approved for baseline Skadi")
    p.add_argument("--interrupt-after-mb", type=float, default=0, help="test hook: abort any single download after N MB")
    p.add_argument("--keep-work", action="store_true")
    p.add_argument("--gis-prefix", default=os.environ.get("GNSS_POC_GIS_PREFIX"),
                   help="prefix of an isolated GDAL/PROJ install (e.g. a conda-forge env); bin/ is prepended to PATH")
    return p.parse_args()


if __name__ == "__main__":
    sys.exit(Runner(parse()).main())

#!/usr/bin/env python3
"""Independent cross-layer alignment checks for a POC run (offline, cache only).

    python3 evaluate.py --run ~/gnss-poc-88-work/runs/cold --cache ~/gnss-poc-88-work/cache-final \
        --gis-prefix ~/gnss-poc-88-work/tools/gis

1. OSM summits with a surveyed `ele` tag vs Copernicus (EGM96) and Skadi at the peak node.
   Peaks are local maxima, so a 30 m grid sample is expected to read slightly LOW;
   a systematic horizontal shift would show as a large, one-sided bias.
2. OSM lake polygons (natural=water, area >= 2 ha) vs ESA WorldCover water (class 80)
   at an interior point.
3. The same interior points vs the Sentinel-2 SCL water class (6), checking the imagery
   mosaic's georeferencing against OSM.
Writes <run>/evaluation.json.
"""
from __future__ import annotations

import argparse
import glob
import gzip
import json
import math
import os
import statistics
import subprocess
import tempfile
from pathlib import Path

import geo


def locate(env, raster: str, pts: list[tuple[float, float]]) -> list[float | None]:
    p = subprocess.run(["gdallocationinfo", "-valonly", "-wgs84", raster], input="".join(f"{x} {y}\n" for x, y in pts),
                       capture_output=True, text=True, env=env)
    out = []
    for line in p.stdout.splitlines():
        try:
            out.append(float(line))
        except ValueError:
            out.append(None)
    return out + [None] * (len(pts) - len(out))


def interior(poly) -> tuple[float, float] | None:
    """A point inside the outer ring (centroid if inside, else best scanline midpoint)."""
    ring = poly[0]
    xs, ys = [p[0] for p in ring], [p[1] for p in ring]
    cx, cy = sum(xs) / len(xs), sum(ys) / len(ys)
    if geo.point_in_polygon(cx, cy, poly):
        return cx, cy
    best = None
    for k in range(1, 20):
        y = min(ys) + (max(ys) - min(ys)) * k / 20
        cross = []
        for i in range(len(ring) - 1):
            (x1, y1), (x2, y2) = ring[i], ring[i + 1]
            if (y1 > y) != (y2 > y):
                cross.append(x1 + (y - y1) * (x2 - x1) / (y2 - y1))
        cross.sort()
        for a, b in zip(cross[::2], cross[1::2]):
            if best is None or b - a > best[0]:
                best = (b - a, ((a + b) / 2, y))
    return best[1] if best else None


def area_m2(ring) -> float:
    lat0 = math.radians(sum(p[1] for p in ring) / len(ring))
    s = 0.0
    for i in range(len(ring) - 1):
        x1, y1 = ring[i]
        x2, y2 = ring[i + 1]
        s += math.radians(x1) * math.cos(lat0) * math.radians(y2) - math.radians(x2) * math.cos(lat0) * math.radians(y1)
    return abs(s) / 2 * 6371008.8 ** 2


def summary(diffs: list[float]) -> dict:
    a = sorted(abs(d) for d in diffs)
    return {"n": len(diffs), "mean_m": round(statistics.mean(diffs), 1), "median_m": round(statistics.median(diffs), 1),
            "median_abs_m": round(a[len(a) // 2], 1), "p90_abs_m": round(a[int(len(a) * 0.9)], 1)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--cache", required=True)
    ap.add_argument("--gis-prefix", required=True)
    a = ap.parse_args()
    run, cache, gp = Path(a.run).expanduser(), Path(a.cache).expanduser(), Path(a.gis_prefix).expanduser()
    env = dict(os.environ, PATH=f"{gp / 'bin'}:{os.environ['PATH']}", PROJ_DATA=str(gp / "share" / "proj"),
               GDAL_DATA=str(gp / "share" / "gdal"), GDAL_PAM_ENABLED="NO")
    info = json.loads((run / "run.json").read_text())["input"]
    lat0, lon0, radius = info["centre"][0], info["centre"][1], info["radius_m"]
    inside = lambda x, y: geo.haversine(lat0, lon0, y, x) <= radius  # noqa: E731
    res = {}

    # 1. Summits
    peaks = []
    for f in json.loads((run / "osm/layers/peaks.geojson").read_text())["features"]:
        p = f["properties"]
        x, y = f["geometry"]["coordinates"]
        try:
            ele = float(str(p.get("ele", "")).replace("m", "").replace(",", ".").strip())
        except ValueError:
            continue
        if p.get("natural") == "peak" and inside(x, y) and 500 < ele < 4000:
            peaks.append((x, y, ele, p.get("name")))
    pts = [(x, y) for x, y, _, _ in peaks]
    cop = locate(env, str(run / "dem/dem_egm96.tif"), pts)
    skadi_files = glob.glob(str(cache / "sources" / "*.hgt.gz"))
    sk = [None] * len(pts)
    if skadi_files:
        with tempfile.TemporaryDirectory() as td:
            vrt_inputs = []
            for g in skadi_files:
                name = Path(g).name.split("-")[-1].replace(".gz", "")
                dst = Path(td) / name
                dst.write_bytes(gzip.decompress(Path(g).read_bytes()))
                vrt_inputs.append(str(dst))
            vrt = str(Path(td) / "skadi.vrt")
            subprocess.run(["gdalbuildvrt", "-q", vrt] + vrt_inputs, env=env, check=True)
            sk = locate(env, vrt, pts)
    dc = [c - e for (_, _, e, _), c in zip(peaks, cop) if c is not None]
    ds = [s - e for (_, _, e, _), s in zip(peaks, sk) if s is not None and s > -1000]
    worst = sorted(((abs(c - e), n or "", e, c) for (_, _, e, n), c in zip(peaks, cop) if c is not None), key=lambda w: w[0], reverse=True)[:5]
    res["summits"] = {"copernicus_minus_osm_ele": summary(dc), "skadi_minus_osm_ele": summary(ds) if ds else None,
                      "largest_copernicus_differences": [{"name": n, "osm_ele": e, "copernicus": c} for _, n, e, c in worst],
                      "interpretation": "negative = grid reads below the surveyed summit (expected for 30 m cells); OSM ele values have mixed provenance"}

    # 2/3. Lakes vs WorldCover and Sentinel-2 SCL
    lakes = []
    for f in json.loads((run / "osm/layers/water_areas.geojson").read_text())["features"]:
        g, p = f["geometry"], f["properties"]
        if p.get("natural") != "water" or p.get("water") in ("river", "stream", "canal", "wastewater"):
            continue
        polys = [g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]
        poly = max(polys, key=lambda q: area_m2(q[0]))
        if area_m2(poly[0]) < 20000:
            continue
        pt = interior(poly)
        if pt and inside(*pt):
            lakes.append((pt, p.get("name"), round(area_m2(poly[0]) / 1e4, 1)))
    lp = [pt for pt, _, _ in lakes]
    wc_src = sorted(glob.glob(str(cache / "sources" / "window-worldcover-*.tif")))
    wc = locate(env, wc_src[0], lp) if wc_src else []
    scl_src = sorted(glob.glob(str(cache / "sources" / "window-s2-scl-*.tif")))
    scl = [None] * len(lp)
    for s in scl_src:
        vals = locate(env, s, lp)
        scl = [v if (o is None or o == 0) else o for o, v in zip(scl, vals)]
    res["lakes"] = {"n": len(lakes), "min_area_ha": 2,
                    "worldcover_water_match_pct": round(100 * sum(1 for v in wc if v == 80) / max(len(wc), 1), 1),
                    "worldcover_classes_at_points": {str(int(k)): sum(1 for v in wc if v == k) for k in sorted({v for v in wc if v is not None})},
                    "sentinel2_scl_water_match_pct": round(100 * sum(1 for v in scl if v == 6) / max(len(scl), 1), 1),
                    "sentinel2_scl_classes_at_points": {str(int(k)): sum(1 for v in scl if v == k) for k in sorted({v for v in scl if v is not None})},
                    "mismatches": [{"name": n, "ha": h, "worldcover": w, "scl": s} for (pt, n, h), w, s in zip(lakes, wc, scl) if w != 80 or s != 6][:10]}
    (run / "evaluation.json").write_text(json.dumps(res, indent=1))
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Build the offline MapLibre POC bundle from an existing POC run (no downloads).

    ~/gnss-poc-88-work/tools/gis/bin/python3 maplibre/build.py \
        --run ~/gnss-poc-88-work/runs/cold --cache ~/gnss-poc-88-work/cache-final \
        --node ~/gnss-poc-88-work/node --out ~/gnss-poc-88-work/maplibre-bundle

Produces <out>/tiles/{osm,dem,landcover}.mbtiles and <out>/static (MapLibre GL JS,
Noto Sans webfonts). Needs osmium, tippecanoe and GDAL (with Python bindings).
"""
from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import subprocess
import time
from pathlib import Path

from osgeo import ogr

ogr.UseExceptions()

ROAD = {"motorway": ("motorway", 6), "trunk": ("trunk", 6), "primary": ("primary", 8), "secondary": ("secondary", 9),
        "tertiary": ("tertiary", 10), "unclassified": ("minor", 12), "residential": ("minor", 12), "living_street": ("minor", 13),
        "road": ("minor", 13), "service": ("service", 14), "track": ("track", 12), "path": ("path", 13), "footway": ("path", 14),
        "bridleway": ("path", 13), "steps": ("path", 15), "cycleway": ("path", 14), "pedestrian": ("minor", 14), "via_ferrata": ("path", 13)}
PLACE = {"city": 6, "town": 7, "suburb": 12, "village": 10, "hamlet": 12, "locality": 13, "isolated_dwelling": 14, "neighbourhood": 14}
WOOD = {"wood", "forest"}
GRASS = {"grassland", "meadow", "heath", "fell", "scrub", "grass"}
ROCK = {"bare_rock", "scree", "shingle", "rock", "cliff"}
BUILT = {"residential", "commercial", "industrial", "retail", "construction", "railway", "garages"}
POI = {
    ("tourism", "viewpoint"): ("viewpoint", 13), ("tourism", "alpine_hut"): ("hut", 13), ("tourism", "wilderness_hut"): ("hut", 13),
    ("amenity", "shelter"): ("hut", 15), ("tourism", "hotel"): ("lodging", 15), ("tourism", "guest_house"): ("lodging", 16),
    ("tourism", "hostel"): ("lodging", 15), ("tourism", "camp_site"): ("camp", 14), ("tourism", "attraction"): ("attraction", 14),
    ("tourism", "museum"): ("attraction", 15), ("historic", "castle"): ("attraction", 14), ("historic", "monument"): ("attraction", 15),
    ("historic", "church"): ("worship", 15), ("amenity", "place_of_worship"): ("worship", 15), ("amenity", "hospital"): ("health", 13),
    ("amenity", "clinic"): ("health", 15), ("amenity", "pharmacy"): ("health", 16), ("amenity", "police"): ("emergency", 15),
    ("amenity", "fire_station"): ("emergency", 15), ("amenity", "fuel"): ("fuel", 15), ("natural", "spring"): ("water", 15),
    ("waterway", "waterfall"): ("water", 14), ("leisure", "park"): ("park", 15), ("aerialway", "station"): ("lift", 14),
}
WORLDCOVER_SOFT = {10: (198, 227, 186), 20: (214, 233, 200), 30: (226, 238, 214), 40: (236, 234, 214), 50: (236, 233, 228),
                   60: (236, 232, 224), 70: (250, 250, 250), 80: (170, 218, 255), 90: (205, 230, 220), 95: (190, 226, 205),
                   100: (229, 235, 216)}


def run(cmd, env):
    t = time.time()
    p = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if p.returncode:
        raise SystemExit(f"{cmd[0]} failed: {p.stderr[-1500:]}")
    print(f"  {os.path.basename(cmd[0])} {time.time() - t:.1f}s", flush=True)
    return p


def area_m2(g) -> float:
    env = g.GetEnvelope()
    lat = math.radians((env[2] + env[3]) / 2)
    return g.GetArea() * (111320 ** 2) * math.cos(lat)


def area_minzoom(a: float, big=6, mid=9, small=11, tiny=13) -> int:
    return big if a > 1e6 else mid if a > 1e5 else small if a > 1e4 else tiny


def classify(p: dict, gtype: str, geom):
    """Return (layer, properties, minzoom) or None. Only map-relevant attributes are kept."""
    name = p.get("name:en") or p.get("name")
    poly = gtype in ("Polygon", "MultiPolygon")
    out = []
    if gtype == "Point":
        if p.get("place") in PLACE and name:
            try:
                pop = int(str(p.get("population", "0")).replace(" ", "").replace(",", "")) if p.get("population") else 0
            except ValueError:
                pop = 0
            out.append(("place", {"class": p["place"], "name": name, "pop": pop}, PLACE[p["place"]]))
        if p.get("natural") in ("peak", "saddle", "volcano") and name:
            try:
                ele = round(float(str(p.get("ele", "")).split()[0].replace(",", ".")))
            except (ValueError, IndexError):
                ele = None
            z = 13 if p["natural"] == "saddle" else (10 if ele and ele >= 2800 else 11 if ele and ele >= 2400 else 12)
            out.append(("peak", {"class": p["natural"], "name": name, **({"ele": ele} if ele else {})}, z))
    if gtype in ("Point", "Polygon", "MultiPolygon"):
        for k in ("tourism", "amenity", "historic", "natural", "waterway", "leisure", "aerialway"):
            hit = POI.get((k, p.get(k)))
            if hit and (name or hit[0] in ("viewpoint", "hut", "water")):
                pt = geom if gtype == "Point" else geom.PointOnSurface()
                out.append(("poi", {"class": hit[0], **({"name": name} if name else {})}, hit[1], pt))
                break
    if gtype in ("LineString", "MultiLineString"):
        if p.get("highway") in ROAD:
            cls, z = ROAD[p["highway"]]
            props = {"class": cls}
            if name:
                props["name"] = name
            if p.get("ref"):
                props["ref"] = p["ref"]
            if p["highway"].endswith("_link"):
                props["link"] = 1
            if p.get("tunnel") in ("yes", "building_passage"):
                props["tunnel"] = 1
            if p.get("bridge") == "yes":
                props["bridge"] = 1
            out.append(("transportation", props, z))
        elif str(p.get("highway", "")).endswith("_link") and p["highway"][:-5] in ROAD:
            cls, z = ROAD[p["highway"][:-5]]
            out.append(("transportation", {"class": cls, "link": 1, **({"ref": p["ref"]} if p.get("ref") else {})}, z + 1))
        elif p.get("waterway") in ("river", "stream", "canal", "ditch", "drain"):
            w = p["waterway"]
            out.append(("waterway", {"class": w, **({"name": name} if name else {})}, 8 if w == "river" else 12 if w in ("stream", "canal") else 14))
        elif p.get("aerialway") and p["aerialway"] not in ("station", "pylon"):
            out.append(("aerialway", {"class": p["aerialway"], **({"name": name} if name else {})}, 12))
        elif p.get("railway") in ("rail", "narrow_gauge", "funicular"):
            out.append(("railway", {"class": p["railway"]}, 10))
        elif p.get("boundary") == "administrative" and p.get("admin_level") == "2":
            out.append(("boundary", {"admin_level": 2}, 6))
        elif p.get("natural") == "cliff":
            out.append(("cliff", {}, 13))
    if poly:
        a = area_m2(geom)
        if p.get("building") and p.get("building") != "no":
            out.append(("building", {}, 14 if a < 2000 else 13))
        elif p.get("natural") == "water" or p.get("water") or p.get("landuse") in ("reservoir", "basin"):
            out.append(("water", {}, area_minzoom(a, 6, 8, 10, 12)))
            if name and a > 5000:
                out.append(("water_name", {"name": name, "area": round(a)}, 10 if a > 2e5 else 12 if a > 2e4 else 14, geom.PointOnSurface()))
        elif p.get("natural") == "glacier":
            out.append(("landcover", {"class": "ice"}, area_minzoom(a)))
        elif p.get("natural") in WOOD or p.get("landuse") in WOOD:
            out.append(("landcover", {"class": "wood"}, area_minzoom(a, 7, 9, 11, 13)))
        elif p.get("natural") in GRASS or p.get("landuse") in ("meadow", "grass"):
            out.append(("landcover", {"class": "grass"}, area_minzoom(a, 8, 10, 12, 14)))
        elif p.get("natural") in ROCK:
            out.append(("landcover", {"class": "rock"}, area_minzoom(a, 8, 10, 12, 14)))
        elif p.get("landuse") in BUILT:
            out.append(("landuse", {"class": "industrial" if p["landuse"] in ("industrial", "railway") else "residential"}, area_minzoom(a, 8, 10, 12, 13)))
        elif p.get("landuse") in ("farmland", "orchard", "vineyard", "allotments") or p.get("leisure") in ("park", "pitch", "golf_course"):
            out.append(("landuse", {"class": "park" if p.get("leisure") else "farmland"}, area_minzoom(a, 9, 10, 12, 14)))
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--cache", required=True)
    ap.add_argument("--node", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--title", help="area label shown in the map badge")
    a = ap.parse_args()
    run_dir, cache, node, out = (Path(x).expanduser() for x in (a.run, a.cache, a.node, a.out))
    gis = Path(os.environ.get("CONDA_PREFIX") or Path(os.sys.executable).parent.parent)
    env = dict(os.environ, PATH=f"{gis / 'bin'}:{os.environ['PATH']}", PROJ_DATA=str(gis / "share/proj"),
               GDAL_DATA=str(gis / "share/gdal"), GDAL_PAM_ENABLED="NO")
    work = out / "_work"
    for d in (out / "tiles", out / "static/fonts", work):
        d.mkdir(parents=True, exist_ok=True)
    stats = {"started": time.strftime("%Y-%m-%dT%H:%M:%S")}
    t0 = time.time()

    # 1. OSM -> classified newline-delimited GeoJSON per layer (all tags available here).
    print("vector layers", flush=True)
    filt = work / "filtered.osm.pbf"
    run(["osmium", "tags-filter", "--overwrite", "-o", str(filt), str(run_dir / "osm/area.osm.pbf"),
         "nwr/highway", "wr/building", "nwr/waterway", "wr/natural", "nwr/water", "wr/landuse", "n/place", "n/natural",
         "nwr/tourism", "nwr/amenity", "nwr/historic", "nwr/leisure", "nwr/aerialway", "w/railway", "w/boundary=administrative"], env)
    seq = work / "all.geojsonseq"
    run(["osmium", "export", "--overwrite", "-f", "geojsonseq", "-x", "print_record_separator=false", "-o", str(seq), str(filt)], env)
    files, counts = {}, {}
    for line in seq.open():
        f = json.loads(line)
        g = f["geometry"]
        geom = ogr.CreateGeometryFromJson(json.dumps(g))
        for item in classify(f["properties"], g["type"], geom):
            layer, props, z = item[:3]
            gj = json.loads(item[3].ExportToJson()) if len(item) > 3 else g
            if layer not in files:
                files[layer] = (work / f"{layer}.geojsonseq").open("w")
            files[layer].write(json.dumps({"type": "Feature", "tippecanoe": {"minzoom": z}, "properties": props, "geometry": gj}) + "\n")
            counts[layer] = counts.get(layer, 0) + 1
    for fh in files.values():
        fh.close()
    stats["features_per_layer"] = counts
    osm_mb = out / "tiles/osm.mbtiles"
    cmd = ["tippecanoe", "-o", str(osm_mb), "--force", "-Z6", "-z14", "-P", "--simplification=4", "--detect-shared-borders",
           "--drop-densest-as-needed", "--extend-zooms-if-still-dropping", f"--name={a.title or 'POC area'}", "--attribution=© OpenStreetMap contributors"]
    for layer in counts:
        cmd += ["-L", f"{layer}:{work / (layer + '.geojsonseq')}"]
    p = run(cmd, env)
    stats["tippecanoe_warnings"] = [l for l in p.stderr.splitlines() if "drop" in l.lower() or "too" in l.lower()][-10:]

    # 2. Terrarium-encoded DEM tiles for MapLibre's native hillshade (z<=12, nearest overviews).
    print("dem tiles", flush=True)
    res12 = 2 * math.pi * 6378137 / (256 * 2 ** 12)
    dem3857 = work / "dem3857.tif"
    run(["gdalwarp", "-overwrite", "-t_srs", "EPSG:3857", "-tr", str(res12), str(res12), "-r", "bilinear", "-ot", "Float32",
         str(run_dir / "dem/dem_egm96.tif"), str(dem3857)], env)
    bands = []
    for i, expr in enumerate(["numpy.floor((A+32768)/256)", "numpy.floor(A+32768)%256", "numpy.floor(((A+32768)%1)*256)"]):
        b = work / f"terrarium{i}.tif"
        run(["gdal_calc", "--quiet", "--overwrite", "-A", str(dem3857), "--calc", expr, "--type", "Byte", "--outfile", str(b)], env)
        bands.append(str(b))
    rgb = work / "terrarium.vrt"
    run(["gdalbuildvrt", "-overwrite", "-separate", str(rgb)] + bands, env)
    dem_mb = out / "tiles/dem.mbtiles"
    dem_mb.unlink(missing_ok=True)
    run(["gdal_translate", "-of", "MBTILES", "-co", "TILE_FORMAT=PNG", "-co", "ZOOM_LEVEL_STRATEGY=UPPER", "-co", "RESAMPLING=NEAREST",
         "-co", "NAME=Copernicus DEM terrarium", str(rgb), str(dem_mb)], env)
    run(["gdaladdo", "-r", "nearest", str(dem_mb), "2", "4", "8", "16", "32", "64"], env)

    # 3. Soft land-cover underlay from the cached WorldCover window (z<=13).
    print("landcover tiles", flush=True)
    src = sorted(cache.glob("sources/window-worldcover-*.tif"))[0]
    pal = work / "soft.txt"
    pal.write_text("0 0 0 0 0\n" + "".join(f"{k} {r} {g} {b} 255\n" for k, (r, g, b) in WORLDCOVER_SOFT.items()))
    rgba = work / "wc_rgba.tif"
    run(["gdaldem", "color-relief", "-alpha", "-nearest_color_entry", str(src), str(pal), str(rgba)], env)
    res13 = res12 / 2
    wc3857 = work / "wc3857.tif"
    run(["gdalwarp", "-overwrite", "-t_srs", "EPSG:3857", "-tr", str(res13), str(res13), "-r", "mode", str(rgba), str(wc3857)], env)
    lc_mb = out / "tiles/landcover.mbtiles"
    lc_mb.unlink(missing_ok=True)
    run(["gdal_translate", "-of", "MBTILES", "-co", "TILE_FORMAT=PNG8", "-co", "ZOOM_LEVEL_STRATEGY=LOWER", "-co", "NAME=WorldCover soft",
         str(wc3857), str(lc_mb)], env)
    run(["gdaladdo", "-r", "mode", str(lc_mb), "2", "4", "8", "16", "32"], env)

    # 4. Static runtime: MapLibre GL JS (BSD-3-Clause) and Noto Sans (OFL) webfonts.
    dist = node / "node_modules/maplibre-gl/dist"
    for f in ("maplibre-gl.mjs", "maplibre-gl-shared.mjs", "maplibre-gl-worker.mjs", "maplibre-gl.css"):
        shutil.copy(dist / f, out / "static" / f)
    shutil.copy(node / "node_modules/maplibre-gl/LICENSE.txt", out / "static/MAPLIBRE-LICENSE.txt")
    fonts = node / "node_modules/@fontsource/noto-sans/files"
    for f in fonts.glob("noto-sans-latin*-[46]00-*.woff2"):
        shutil.copy(f, out / "static/fonts" / f.name)
    shutil.copy(run_dir / "plan/circle.geojson", out / "static/circle.geojson")
    stats["version_maplibre"] = json.loads((node / "node_modules/maplibre-gl/package.json").read_text())["version"]
    stats["seconds"] = round(time.time() - t0, 1)
    stats["sizes"] = {p.name: p.stat().st_size for p in (out / "tiles").glob("*.mbtiles")}
    stats["static_bytes"] = sum(p.stat().st_size for p in (out / "static").rglob("*") if p.is_file())
    info = json.loads((run_dir / "run.json").read_text())["input"]
    stats["centre"], stats["radius_m"] = info["centre"], info["radius_m"]
    bbox = json.loads((run_dir / "run.json").read_text())["plan"]["bbox"]
    m = 0.15
    (out / "static/config.json").write_text(json.dumps({
        "title": a.title or info["name"], "centre": info["centre"], "radius_km": info["radius_m"] / 1000,
        "bounds": [[bbox[0] - m, bbox[1] - m], [bbox[2] + m, bbox[3] + m]]}))
    (out / "build.json").write_text(json.dumps(stats, indent=1))
    shutil.rmtree(work, ignore_errors=True)
    print(json.dumps(stats, indent=1))


if __name__ == "__main__":
    main()

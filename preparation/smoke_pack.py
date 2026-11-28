#!/usr/bin/env python3
"""Native smoke test for a GNSS preparation pack (issue #97).

Runs entirely offline against a pack directory — including relocated paths
containing spaces — with a restricted PATH containing only the pack and bare
system directories. Exercises exactly what the preparation pipeline needs:
GDAL MVT/MBTiles write drivers, raster/datum drivers, osmium, and the
zero-invalid repair gate. Exits nonzero on any failure.

Usage: python3 smoke_pack.py --pack <dir> [--workdir <scratch>]
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True, type=Path)
    ap.add_argument("--workdir", type=Path, default=None)
    args = ap.parse_args()
    pack = args.pack.resolve()
    if not (pack / "manifest.json").exists():
        sys.exit("no manifest.json — not a preparation pack")
    manifest = json.loads((pack / "manifest.json").read_text())
    ext = ".exe" if manifest["target"] == "windows-x64" else ""
    bindir = pack / "bin"

    scratch = args.workdir or Path(tempfile.mkdtemp(prefix="gnss-pack-smoke "))
    scratch.mkdir(parents=True, exist_ok=True)
    os.chdir(scratch)

    env = {
        "PATH": str(bindir) + os.pathsep + (r"C:\Windows\System32;C:\Windows" if ext else "/usr/bin:/bin"),
        "GDAL_DATA": str(pack / "share" / "gdal"),
        "PROJ_DATA": str(pack / "share" / "proj"),
        "PROJ_NETWORK": "OFF",
        # deliberately no HOME/USERPROFILE influence on data lookup, no conda,
        # no python — proving independence from any operator toolchain.
    }
    if not ext:
        env["HOME"] = str(scratch)

    def run(cmd, **kw):
        print("+", " ".join(str(c) for c in cmd))
        return subprocess.run([str(c) for c in cmd], env=env, capture_output=True, text=True, **kw)

    failures = []

    def check(name, cond, detail=""):
        print(("PASS " if cond else "FAIL ") + name + (f" — {detail}" if detail and not cond else ""))
        if not cond:
            failures.append(name)

    # 1. binaries start offline with no operator environment
    r = run([bindir / ("ogr2ogr" + ext), "--version"])
    check("ogr2ogr starts", r.returncode == 0 and "GDAL" in r.stdout, r.stdout.strip()[:80])
    r = run([bindir / ("osmium" + ext), "--version"])
    check("osmium starts", r.returncode == 0 and "osmium" in r.stdout.lower(), (r.stdout + r.stderr)[:80])
    r = run([bindir / ("gnss-mvt-repair" + ext)])
    check("gnss-mvt-repair starts", "usage" in (r.stderr + r.stdout).lower())

    # 2. required drivers present (MVT/MBTiles write, raster, datum)
    r = run([bindir / ("ogrinfo" + ext), "--formats"])
    formats = r.stdout
    for drv in ("MVT", "MBTiles"):
        check(f"{drv} driver", drv in formats)
    r = run([bindir / ("gdalinfo" + ext), "--formats"])
    check("raster drivers present", "GTiff" in r.stdout and "PNG" in r.stdout, r.stdout[:80])

    # 3. vector tiling write/read parity with the qualified reference fixture:
    #    holes, multipolygon-capable polygons, name/ref/class preservation
    fixture = {
        "type": "FeatureCollection",
        "features": [
            {"type": "Feature", "properties": {"class": "primary", "name": "pack smoke", "ref": "T1"},
             "geometry": {"type": "LineString", "coordinates": [[1.475, 42.568], [1.4847, 42.5722], [1.496, 42.576]]}},
            {"type": "Feature", "properties": {"class": "wood"},
             "geometry": {"type": "Polygon", "coordinates": [
                 [[1.47, 42.56], [1.49, 42.56], [1.49, 42.58], [1.47, 42.58], [1.47, 42.56]],
                 [[1.475, 42.565], [1.48, 42.565], [1.48, 42.57], [1.475, 42.565]]]}},
        ],
    }
    (scratch / "fixture.geojson").write_text(json.dumps(fixture))
    r = run([bindir / ("ogr2ogr" + ext), "-f", "MBTiles", "smoke.mbtiles", "fixture.geojson",
             "-dsco", "MINZOOM=6", "-dsco", "MAXZOOM=14", "-lco", "NAME=transportation"])
    check("MBTiles write", r.returncode == 0 and (scratch / "smoke.mbtiles").stat().st_size > 0, r.stderr[:120])
    r = run([bindir / ("ogr2ogr" + ext), "-f", "MVT", "smoke-xyz", "fixture.geojson",
             "-dsco", "FORMAT=DIRECTORY", "-dsco", "MINZOOM=6", "-dsco", "MAXZOOM=14",
             "-dsco", "COMPRESS=YES", "-lco", "NAME=transportation"])
    check("MVT directory write", r.returncode == 0, r.stderr[:120])
    ntiles = len(list((scratch / "smoke-xyz").rglob("*.pbf"))) if (scratch / "smoke-xyz").exists() else 0
    check("reference tile count", ntiles == 16, f"got {ntiles}, reference 16")
    r = run([bindir / ("ogrinfo" + ext), "-ro", "-q", "-oo", "ZOOM_LEVEL=14", "-al", "smoke.mbtiles"])
    check("name preservation", "pack smoke" in r.stdout)
    check("ref preservation", "= T1" in r.stdout)
    check("polygon (with hole) readback", "POLYGON" in r.stdout)

    # 4. zero-invalid repair gate round-trip on produced tiles
    r = run([bindir / ("gnss-mvt-repair" + ext), "smoke-xyz", "smoke-fixed", "repair-report.json"])
    check("repair zero-invalid gate", r.returncode == 0 and (scratch / "smoke-fixed").exists(), (r.stderr or "")[:120])
    r = run([bindir / ("gnss-mvt-repair" + ext), "--verify", "smoke-fixed"])
    check("verify clean after repair", r.returncode == 0 and '"invalid": 0' in r.stdout)

    print()
    if failures:
        print("SMOKE FAIL:", ", ".join(failures))
        sys.exit(1)
    print("SMOKE PASS —", manifest["target"], "pack", manifest["version"],
          f"({len(manifest['files'])} files)")


if __name__ == "__main__":
    main()

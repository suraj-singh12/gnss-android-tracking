#!/usr/bin/env python3
"""Package the MapLibre POC as a portable, offline zip with standalone viewers.

    python3 maplibre/package.py --bundle ~/gnss-poc-88-work/maplibre-bundle --out ~/gnss-poc-88-work/dist

Creates gnss-offline-map-poc-arinsal.zip containing map/ (page, MapLibre, fonts, tiles as
files) and one viewer executable per platform (Go, standard library only).
"""
from __future__ import annotations

import argparse
import os
import shutil
import sqlite3
import subprocess
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
TARGETS = [("darwin", "arm64", "open-map-macos-apple-silicon"), ("darwin", "amd64", "open-map-macos-intel"),
           ("windows", "amd64", "open-map-windows.exe"), ("linux", "amd64", "open-map-linux")]
README = """GNSS offline map POC (Issue #88) - Arinsal, Andorra, 20 km radius
==================================================================
Experimental evaluation build. NOT GNSS Command. Works fully offline.

Run (no installation needed):
  macOS (Apple silicon): double-click or run ./open-map-macos-apple-silicon
  macOS (Intel):         ./open-map-macos-intel
  Windows:               double-click open-map-windows.exe
  Linux:                 ./open-map-linux

It serves the "map" folder on http://127.0.0.1:8770 (this computer only) and opens your
browser. Use Chrome, Edge or Firefox with hardware graphics (WebGL). Keep the window open
while testing; close it to stop. Option: --port 8780 if 8770 is busy.

macOS may block the unsigned viewer: right-click it > Open, or run
  xattr -dr com.apple.quarantine <unzipped folder>
Windows SmartScreen may warn: More info > Run anyway.

Try: scroll/pinch to zoom, drag to pan, double-click to zoom in. Start at the overview and zoom
into Andorra la Vella (town), Arinsal (village) and the lakes/peaks to the north and east.

Data: (c) OpenStreetMap contributors (ODbL); Copernicus DEM GLO-30 (c) DLR/Airbus, ESA/EU;
ESA WorldCover 2021 (CC BY 4.0). Software: MapLibre GL JS (BSD-3-Clause), Noto Sans (OFL).
Source: poc/issue-88-offline-map-builder/maplibre on branch devX/issue-88-offline-map-builder-poc.
"""


def export_tiles(mb: Path, dst: Path, ext: str) -> int:
    n = 0
    db = sqlite3.connect(f"file:{mb}?mode=ro", uri=True)
    for z, x, y, data in db.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles"):
        p = dst / str(z) / str(x) / f"{(1 << z) - 1 - y}.{ext}"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        n += 1
    return n


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    bundle, out = Path(a.bundle).expanduser(), Path(a.out).expanduser()
    stage = out / "gnss-offline-map-poc-arinsal"
    shutil.rmtree(stage, ignore_errors=True)
    m = stage / "map"
    shutil.copytree(bundle / "static", m / "static")
    for f in ("index.html", "app.js", "style.js"):
        shutil.copy(HERE / "web" / f, m / f)
    counts = {name: export_tiles(bundle / "tiles" / f"{name}.mbtiles", m / "tiles" / name, ext)
              for name, ext in (("osm", "pbf"), ("dem", "png"), ("landcover", "png"))}
    for goos, arch, name in TARGETS:
        env = dict(os.environ, GOOS=goos, GOARCH=arch, CGO_ENABLED="0")
        subprocess.run(["go", "build", "-trimpath", "-ldflags=-s -w", "-o", str(stage / name), "."], cwd=HERE / "server", env=env, check=True)
    (stage / "README.txt").write_text(README)
    zpath = out / "gnss-offline-map-poc-arinsal.zip"
    zpath.unlink(missing_ok=True)
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(stage.rglob("*")):
            if p.is_file():
                info = zipfile.ZipInfo.from_file(p, p.relative_to(out))
                if p.name.startswith("open-map-") and not p.suffix:
                    info.external_attr = (0o755 & 0xFFFF) << 16
                with open(p, "rb") as fh:
                    z.writestr(info, fh.read(), zipfile.ZIP_DEFLATED)
    print({"zip": str(zpath), "bytes": zpath.stat().st_size, "tiles": counts})


if __name__ == "__main__":
    main()

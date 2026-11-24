#!/usr/bin/env python3
"""Offline POC reference viewer server (loopback only, stdlib only).

    python3 viewer/serve.py --run ~/gnss-poc-88-work/runs/cold --port 8765

Serves the viewer page, MBTiles raster tiles and the run's GeoJSON layers.
It makes no external request. This is a reference viewer for evaluating the
POC outputs; it is NOT GNSS Command and not a production renderer.
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
RASTERS = {"imagery": "imagery/sentinel2.mbtiles", "landcover": "landcover/worldcover.mbtiles", "hillshade": "dem/hillshade.mbtiles"}


class Handler(BaseHTTPRequestHandler):
    run: Path
    dbs: dict
    lock = threading.Lock()

    def log_message(self, *a):  # quiet
        pass

    def send(self, code: int, body: bytes, ctype: str = "application/octet-stream") -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; style-src 'self'")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        path = self.path.split("?")[0]
        if path in ("/", "/index.html"):
            return self.send(200, (HERE / "index.html").read_bytes(), "text/html; charset=utf-8")
        if path in ("/viewer.js", "/viewer.css"):
            return self.send(200, (HERE / path[1:]).read_bytes(), mimetypes.guess_type(path)[0] or "text/plain")
        if path.startswith("/tiles/"):
            try:
                _, _, layer, z, x, y = path.split("/")
                z, x, y = int(z), int(x), int(y.split(".")[0])
            except ValueError:
                return self.send(400, b"bad tile")
            db = self.dbs.get(layer)
            if db is None:
                return self.send(404, b"layer unavailable")
            with self.lock:
                row = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                                 (z, x, (1 << z) - 1 - y)).fetchone()
            if not row:
                return self.send(204, b"")
            data = bytes(row[0])
            return self.send(200, data, "image/png" if data[:4] == b"\x89PNG" else "image/jpeg")
        if path == "/meta.json":
            meta = {}
            for k, db in self.dbs.items():
                with self.lock:
                    meta[k] = dict(db.execute("SELECT name, value FROM metadata").fetchall())
                    meta[k]["zooms"] = [r[0] for r in db.execute("SELECT DISTINCT zoom_level FROM tiles ORDER BY 1")]
            run = json.loads((self.run / "run.json").read_text()) if (self.run / "run.json").exists() else {}
            return self.send(200, json.dumps({"rasters": meta, "input": run.get("input"), "layers": {
                k: {kk: vv for kk, vv in v.items() if kk in ("status", "attribution", "date", "source")} for k, v in run.get("layers", {}).items()}}).encode(),
                "application/json")
        if path.startswith("/data/"):
            rel = path[len("/data/"):]
            allowed = {"plan/circle.geojson", "dem/contours.geojson"} | {f"osm/layers/{p.name}" for p in (self.run / "osm" / "layers").glob("*.geojson")}
            if rel not in allowed:
                return self.send(404, b"not found")
            return self.send(200, (self.run / rel).read_bytes(), "application/geo+json")
        return self.send(404, b"not found")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--run", required=True)
    p.add_argument("--port", type=int, default=8765)
    a = p.parse_args()
    run = Path(a.run).expanduser().resolve()
    Handler.run = run
    Handler.dbs = {}
    for k, rel in RASTERS.items():
        if (run / rel).exists():
            Handler.dbs[k] = sqlite3.connect(f"file:{run / rel}?mode=ro", uri=True, check_same_thread=False)
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    print(f"POC reference viewer (not Command): http://127.0.0.1:{a.port}/  rasters={sorted(Handler.dbs)}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()

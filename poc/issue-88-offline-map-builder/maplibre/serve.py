#!/usr/bin/env python3
"""Serve the offline MapLibre POC on loopback (stdlib only, no external requests).

    python3 maplibre/serve.py --bundle ~/gnss-poc-88-work/maplibre-bundle --port 8770
    open http://127.0.0.1:8770/
"""
from __future__ import annotations

import argparse
import mimetypes
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

WEB = Path(__file__).resolve().parent / "web"
TYPES = {".mjs": "text/javascript", ".js": "text/javascript", ".css": "text/css", ".json": "application/json",
         ".woff2": "font/woff2", ".html": "text/html; charset=utf-8", ".txt": "text/plain"}


class H(BaseHTTPRequestHandler):
    bundle: Path
    dbs: dict = {}
    lock = threading.Lock()

    def log_message(self, *a):
        pass

    def send(self, code, body=b"", ctype="application/octet-stream", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def file(self, root: Path, rel: str):
        p = (root / rel).resolve()
        if root.resolve() not in p.parents or not p.is_file():
            return self.send(404, b"not found")
        self.send(200, p.read_bytes(), TYPES.get(p.suffix, mimetypes.guess_type(p.name)[0] or "application/octet-stream"))

    def do_GET(self):  # noqa: N802
        path = self.path.split("?")[0]
        if path == "/":
            return self.file(WEB, "index.html")
        if path.startswith("/static/"):
            return self.file(self.bundle / "static", path[8:])
        if path.startswith("/tiles/"):
            try:
                _, _, name, z, x, y = path.split("/")
                z, x, y = int(z), int(x), int(y.split(".")[0])
            except ValueError:
                return self.send(400, b"bad tile")
            db = self.dbs.get(name)
            if db is None:
                return self.send(404, b"no tileset")
            with self.lock:
                row = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                                 (z, x, (1 << z) - 1 - y)).fetchone()
            if not row:
                return self.send(204)
            data = bytes(row[0])
            if data[:2] == b"\x1f\x8b":
                return self.send(200, data, "application/x-protobuf", {"Content-Encoding": "gzip"})
            return self.send(200, data, "image/png" if data[:4] == b"\x89PNG" else "image/jpeg")
        return self.file(WEB, path.lstrip("/"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--port", type=int, default=8770)
    a = ap.parse_args()
    H.bundle = Path(a.bundle).expanduser().resolve()
    for p in (H.bundle / "tiles").glob("*.mbtiles"):
        H.dbs[p.stem] = sqlite3.connect(f"file:{p}?mode=ro", uri=True, check_same_thread=False)
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), H)
    print(f"MapLibre offline POC (not GNSS Command): http://127.0.0.1:{a.port}/  tilesets={sorted(H.dbs)}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()

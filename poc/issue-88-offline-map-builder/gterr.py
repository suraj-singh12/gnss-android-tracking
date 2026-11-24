"""GTERR001 encoding and `.gmap.zip` (format 1) terrain augmentation.

Mirrors the existing Command contract in command/internal/core/terrain.go and
map_package.go. No new fields or format version are introduced: the POC only
fills the existing optional per-chunk terrain assets of format 1.
"""
from __future__ import annotations

import hashlib
import json
import math
import struct
import zipfile
from array import array

MAX_SIDE = 257


def chunk_grid(bounds: list[float]) -> tuple[int, int, int, int]:
    """Global integer arc-second node indices with the one-sample halo used by
    Command's Skadi path (dem_download.go)."""
    w = int(math.floor(bounds[0] * 3600)) - 1
    e = int(math.ceil(bounds[2] * 3600)) + 1
    s = int(math.floor(bounds[1] * 3600)) - 1
    n = int(math.ceil(bounds[3] * 3600)) + 1
    if e - w + 1 > MAX_SIDE or n - s + 1 > MAX_SIDE:
        raise ValueError("chunk crop exceeds 257x257")
    return w, s, e, n


def encode(meta: dict, samples: list[int | None]) -> bytes:
    cols, rows = meta["columns"], meta["rows"]
    if len(samples) != cols * rows:
        raise ValueError("sample count mismatch")
    m = json.dumps(meta, separators=(",", ":")).encode()
    if len(m) > 8192:
        raise ValueError("terrain metadata too large")
    out = bytearray(b"GTERR001")
    out += struct.pack(">I", len(m))
    out += m
    a = array("h", (-32768 if v is None else int(v) for v in samples))
    if a.itemsize != 2:
        raise RuntimeError("int16 array required")
    import sys
    if sys.byteorder == "little":
        a.byteswap()
    out += a.tobytes()
    return bytes(out)


def decode(b: bytes) -> tuple[dict, list[int | None]]:
    if b[:8] != b"GTERR001":
        raise ValueError("not GTERR001")
    n = struct.unpack(">I", b[8:12])[0]
    meta = json.loads(b[12:12 + n])
    a = array("h")
    a.frombytes(b[12 + n:])
    import sys
    if sys.byteorder == "little":
        a.byteswap()
    return meta, [None if v == -32768 else v for v in a]


class Dem:
    """A north-first int16 grid on integer arc-second nodes (EHdr export)."""

    def __init__(self, path_bil: str, west_node: int, north_node: int, cols: int, rows: int, nodata: int | None):
        self.west, self.north, self.cols, self.rows, self.nodata = west_node, north_node, cols, rows, nodata
        raw = open(path_bil, "rb").read()
        self.a = array("h")
        self.a.frombytes(raw)
        if len(self.a) != cols * rows:
            raise ValueError("DEM size mismatch")

    def value(self, x_node: int, y_node: int) -> int | None:
        col, row = x_node - self.west, self.north - y_node
        if col < 0 or row < 0 or col >= self.cols or row >= self.rows:
            return None
        v = self.a[row * self.cols + col]
        if self.nodata is not None and v == self.nodata:
            return None
        if v < -12000 or v > 9000:
            return None
        return v


def chunk_terrain(dem: Dem, bounds: list[float], components: dict, provenance: dict) -> tuple[bytes, dict]:
    w, s, e, n = chunk_grid(bounds)
    cols, rows = e - w + 1, n - s + 1
    samples, valid_cells, void_cells = [], 0, 0
    for y in range(n, s - 1, -1):
        for x in range(w, e + 1):
            samples.append(dem.value(x, y))
    for r in range(rows - 1):
        for c in range(cols - 1):
            i = r * cols + c
            if None not in (samples[i], samples[i + 1], samples[i + cols], samples[i + cols + 1]):
                valid_cells += 1
            else:
                void_cells += 1
    if valid_cells == 0:
        raise ValueError("void-only chunk")
    meta = {
        "bounds": [w / 3600, s / 3600, e / 3600, n / 3600],
        "columns": cols,
        "rows": rows,
        "arc_seconds": 1,
        "source": "Copernicus DEM GLO-30 (POC; EGM2008 converted to EGM96)",
        "vertical_datum": "EGM96",
        "components": components,
        "provenance": provenance,
    }
    return encode(meta, samples), {"valid_dem_cells": valid_cells, "void_dem_cells": void_cells, "meta": meta}


def augment_package(src_zip: str, dst_zip: str, dem: Dem, components: dict, provenance: dict,
                    acquired_at: str, source_url: str, corrupt_key: str | None = None) -> dict:
    """Add Copernicus GTERR001 terrain to every planned chunk of an exported
    Command map package. Returns a per-chunk report."""
    with zipfile.ZipFile(src_zip) as z:
        manifest = json.loads(z.read("manifest.json"))
        payload = {n: z.read(n) for n in z.namelist() if n != "manifest.json"}
    job = json.loads(json.dumps(manifest["map"]))
    job["components"] = components
    job["attribution"] = (job.get("attribution") or "© OpenStreetMap contributors · ODbL") + \
        "; DEM: Copernicus DEM GLO-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018, provided under COPERNICUS by the European Union and ESA; all rights reserved"
    manifest["map"] = job
    assets = [a for a in manifest["assets"] if a["layer"] != "terrain"]
    report, missing = {}, False
    for chunk in job["plan"]["chunks"]:
        key, bounds = chunk["key"], chunk["bounds"]
        try:
            b, info = chunk_terrain(dem, bounds, components, provenance)
        except ValueError as e:
            report[key] = {"status": "missing", "error": str(e)}
            missing = True
            continue
        meta = dict(info["meta"])
        evidence = {"status": "complete", "attempts": 1, "stored_bytes": len(b), "acquired_at": acquired_at,
                    "source_url": source_url, "valid_dem_cells": info["valid_dem_cells"],
                    "void_dem_cells": info["void_dem_cells"], "dem_metadata": meta}
        path = f"chunks/{key}.terrain"
        sha = hashlib.sha256(b).hexdigest()
        if corrupt_key == key:
            # Corruption test: declare the genuine checksum, store altered bytes.
            b = b[:-2] + b"\x7f\xff"
        payload[path] = b
        assets.append({"path": path, "sha256": sha, "bytes": len(b), "chunk": key, "layer": "terrain", "evidence": evidence})
        report[key] = {"status": "complete", "bytes": len(b), "valid_cells": info["valid_dem_cells"], "void_cells": info["void_dem_cells"]}
    vectors = {a["chunk"] for a in assets if a["layer"] == "vector"}
    manifest["partial"] = missing or any(c["key"] not in vectors for c in job["plan"]["chunks"])
    manifest["assets"] = assets
    manifest["attribution"] = job["attribution"]
    manifest["derivation"] = ("GTERR001 1-arc-second Copernicus GLO-30 samples on native nodes (no horizontal resampling); "
                              "vertical EGM2008 to EGM96 conversion by PROJ; compatible Command Horn hillshade/marching-square contours; "
                              "prepared by the Issue #88 POC runner. Checksums show byte integrity, not publisher authenticity.")
    with zipfile.ZipFile(dst_zip, "w", zipfile.ZIP_DEFLATED) as z:
        for a in assets:
            z.writestr(a["path"], payload[a["path"]])
        z.writestr("manifest.json", json.dumps(manifest, separators=(",", ":")))
    return report

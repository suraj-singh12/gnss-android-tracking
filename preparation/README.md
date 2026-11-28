# Native preparation pack (issue #97)

Optional, portable native toolchain for enhanced map preparation. **The pack
is never required to view or import maps** — its absence disables only
enhanced preparation, and Command remains independently portable.

Operators never install Python, Node, QGIS, Conda, WSL, compilers or configure
PATH: the pack is a self-contained directory of native executables, runtime
libraries and data with a hash-verified manifest.

## Contents

| Component | Version | Role |
|---|---|---|
| GDAL/OGR (`ogr2ogr`, `ogrinfo`, `gdalinfo`, `gdal_translate`, `gdalwarp`) | 3.13.3 | MVT tiling (`SIMPLIFICATION=4`), raster/DEM stages |
| PROJ (+ data) | 9.9.0 | datum/CRS, offline (`PROJ_NETWORK=OFF`) |
| osmium | 1.19.1 | OSM crop/assembly |
| GEOS | 3.14.1 | geometry validity core |
| `gnss-mvt-repair` | this repository | deterministic zero-invalid geometry repair |

`gnss-mvt-repair` is the project-built native replacement for the qualified
experimental Python correction: it decodes final MVT tiles with an independent
decoder, validates polygon geometry with GEOS, repairs invalid polygons with
GEOS MakeValid followed by validity-preserving reduction onto the integer tile
grid, re-encodes changed tiles deterministically (gzip level 9, mtime 0) and
copies unchanged tiles byte-identically. Feature identity, names, references,
holes and multipolygons are preserved. The zero-invalid gate applies to the
final **encoded** geometry: any residual invalid feature fails the run.

```
gnss-mvt-repair <src_dir> <dst_dir> <report.json>   # repair
gnss-mvt-repair --verify <dir>                       # independent zero-invalid count
```

## Layout and runtime contract

```
gnss-prep-pack-<target>-<version>/
  manifest.json      format, target, versions, license inventory, per-file sha256
  bin/               executables (+ runtime dlls on Windows)
  lib/               shared libraries (Linux/macOS, soname links preserved)
  share/proj share/gdal
  licenses/          upstream license texts where shipped
```

Execution contract (used by Command preparation stages and the smoke test):
`GDAL_DATA=<pack>/share/gdal`, `PROJ_DATA=<pack>/share/proj`,
`PROJ_NETWORK=OFF`, executables invoked by absolute path — no PATH mutation.
The layout is relocation-safe, including paths containing spaces, and fully
offline.

## Reproducible build

```
python3 preparation/build_pack.py --target linux-x64|windows-x64|macos-arm64 \
    --workdir <scratch> --out <packdir>
python3 preparation/smoke_pack.py --pack <packdir>
```

The same recipe runs natively on each platform (there is no
cross-compilation). Inputs are pinned: micromamba `2.10.0-0` per-platform
sha256, exact conda-forge package versions (immutable artifacts), vendored
protozero 1.8.1 (BSD-2-Clause). CI workflow `preparation-pack.yml` reproduces
all three targets on native runners and publishes the candidate packs as
artifacts. Licensing: the notable copyleft component is GEOS (LGPL-2.1-only),
linked dynamically as a shared library; the full per-package inventory and
license texts ship in the manifest and `licenses/`.

## Command integration

- `GET /local/preparation-pack` — detected packs, validation state, active selection
- `POST /local/preparation-pack-import` — offline import of a `.tar.gz`/`.zip`
  candidate pack (same-origin, bounded admission, staged validation, atomic
  publish). Corrupt, foreign-target or tampered packs are rejected with the
  exact cause and the prior state is preserved.
- `POST /local/preparation-pack-select` — explicit version selection
  (`{"dir": "<pack-dir>"}`); automatic resolution otherwise prefers the newest
  valid version.

Detection scans `<datahome>/packs/` at startup and on every status read. If
the selected pack becomes corrupt, the newest other valid version takes over
and the fallback is recorded in `packs/active.json`. Packs are validated by
sha256 per file against their own manifest; the offline import itself is the
trust root for this review-only scope, exactly like map package imports.

Example offline import:

```
curl -X POST --data-binary @gnss-prep-pack-linux-x64.tar.gz \
  -H 'Content-Type: application/octet-stream' \
  http://127.0.0.1:8081/local/preparation-pack-import
```

## Qualified parity evidence

On the qualified Andorra fixture input (393 tiles, 10 invalid polygon
features): exact detection parity with the Python experiment (same 10
features, same 7 repaired tiles), zero invalid after encoding, 386 unchanged
tiles byte-identical, all 59555 feature identities preserved including 15440
named and 9672 ref-carrying features. Native repair wall time ≈ 0.5s versus
≈1.5s for the Python experiment on the same machine.

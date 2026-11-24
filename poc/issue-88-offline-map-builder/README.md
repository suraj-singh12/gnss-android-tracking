# Issue #88 POC: automated high-detail offline map preparation

Tracked in [#88](https://github.com/suraj-singh12/gnss-android-tracking/issues/88).
This directory is **isolated feasibility tooling**, not production code. It changes no
Command, Android, schema, renderer, UI, packaging or CI behaviour. The decision report
with measured results is [REPORT.md](REPORT.md). The operator design proposal is the
simulated prototype in [design/](design/get-a-map-prototype.html), not approved UI.

## What it does

One command prepares a bounded offline map for a centre and a geodesic radius:

1. **Plan:** WGS84 geodesic circle (Vincenty), bounding box, and Command's 240-arc-second
   chunk-grid envelope.
2. **Sources (cache first, bounded):** every external byte goes through `netguard.py`, a
   local CONNECT proxy that enforces one explicit allowance, records a per-host ledger and,
   with `--offline`, refuses every external connection.
   - OSM: the smallest Geofabrik extract polygons covering 1 km samples of the circle
     (point-in-polygon with holes), MD5-verified, resumable downloads, then `osmium extract`
     of the chunk-grid envelope with declared bounds, merged across regions.
   - Elevation: Copernicus DEM GLO-30 COG tiles (AWS Open Data), plus small windows of the
     NGA EGM2008/EGM96 geoid grids from cdn.proj.org.
   - Land cover: ESA WorldCover 2021 v200 10 m, windowed COG read.
   - Imagery: Sentinel-2 L2A true-colour (TCI) via the Earth Search STAC API. Candidate
     dates must fully cover the circle; clouds/shadow/snow are measured inside the circle
     from the scene classification (SCL) band before any imagery is accepted.
   - Skadi HGT for an independent elevation comparison.
3. **Processing:** native 1-arc-second DEM nodes (no horizontal resampling), explicit
   orthometric conversion `H96 = H08 + N08 − N96` checked against PROJ `cs2cs`,
   hillshade/contours, WorldCover colour classes, Sentinel-2 Web Mercator mosaic, MBTiles.
4. **Command package:** an **unmodified** Command binary imports the OSM crop through its
   existing local PBF import, acquires vector chunks from that local source, and exports a
   `.gmap.zip` (format 1). The runner adds Copernicus GTERR001 terrain to each planned
   chunk using the existing format-1 fields only, then imports the result into a fresh,
   unmodified Command to verify it (plus a deliberately corrupt copy that must be refused).
5. **Baseline:** the same OSM crop through Command's PBF import, with terrain from
   Command's own automatic Skadi acquisition, for side-by-side comparison.
6. **Report:** `run.json` with per-layer status, coverage, quality, provenance, timings,
   peak RSS per tool, peak temporary disk and the transfer ledger.

Layer status values: `ready`, `ready_with_limitations`, `partial`, `failed`, `skipped`.
Imagery, land cover, place labels, peaks and OSM relations do **not** render in
unmodified Command; they are shown only by the POC reference viewer (`viewer/`), which is
labelled as such and is not a production renderer.

## Setup (macOS arm64; Linux is similar)

Required tools, none of which are needed on an operational field laptop:

| Tool | Tested version | Purpose |
| --- | --- | --- |
| Python | 3.15.2 (3.10+, stdlib only) | runner, proxy, viewer server |
| GDAL + PROJ CLI | GDAL 3.13.3, PROJ 9.9.0 (conda-forge) | windowed COG reads, warp, calc, hillshade, contours, MBTiles |
| osmium-tool | 1.19.1 | regional PBF extract/merge/filter/export |
| Go | per `command/go.mod` | build the unmodified Command binary |
| Node + Playwright | Node 24, Playwright 1.65 | optional captures only |

GDAL can come from any distribution. On this POC machine Homebrew lives in a
non-default prefix and would have compiled GDAL from source, so an isolated conda-forge
environment was used instead:

```sh
mkdir -p ~/gnss-poc-88-work/tools && cd ~/gnss-poc-88-work/tools
curl -sSL https://micro.mamba.pm/api/micromamba/osx-arm64/latest | tar -xj bin/micromamba
MAMBA_ROOT_PREFIX=$PWD/mamba ./bin/micromamba create -y -p $PWD/gis -c conda-forge --override-channels gdal
(cd <repo>/command && go build -o ~/gnss-poc-88-work/bin/gnss-command ./cmd/party-tracker)
```

Keep the cache, runs and tools outside the repository (the Markdown contract test walks
the whole tree). Nothing here needs credentials; all sources are public and unsigned.

## Run

```sh
cd poc/issue-88-offline-map-builder
# Cold run (downloads, bounded by the allowance)
python3 prepare.py --input inputs/arinsal-20km.json \
  --cache ~/gnss-poc-88-work/cache --out ~/gnss-poc-88-work/runs/cold \
  --allowance-mb 2000 --gis-prefix ~/gnss-poc-88-work/tools/gis
# Warm run from the cache with every external connection refused
python3 prepare.py --input inputs/arinsal-20km.json \
  --cache ~/gnss-poc-88-work/cache --out ~/gnss-poc-88-work/runs/offline \
  --allowance-mb 2000 --gis-prefix ~/gnss-poc-88-work/tools/gis --offline --stages osm,dem,landcover,imagery,package
# Kernel-level proof that nothing reaches the network (loopback allowed)
/usr/bin/sandbox-exec -p '(version 1)(allow default)(deny network-outbound)(allow network-outbound (remote ip "localhost:*"))(allow network-outbound (remote unix-socket))' \
  python3 prepare.py ... --offline
```

Other options: `--lat/--lon/--radius-km/--profile standard|high/--layers`, `--stages`,
`--baseline-approval-mb` (cap for Command's own consent prompts during the baseline),
`--interrupt-after-mb` (test hook that aborts a download to exercise resume) and
`--keep-work`. `inputs/arinsal-20km.json` is the repeatable test input.

Outputs per run: `run.json`, `plan/circle.geojson`, `osm/area.osm.pbf` and
`osm/layers/*.geojson`, `dem/dem_egm96.tif`, `dem/hillshade.mbtiles`,
`dem/contours.geojson`, `landcover/worldcover.mbtiles`, `imagery/sentinel2.mbtiles`,
`command/<name>-enhanced.gmap.zip` and `command/<name>-baseline.gmap.zip`.

View the reference layers offline (loopback only, no external requests):

```sh
python3 viewer/serve.py --run ~/gnss-poc-88-work/runs/cold --port 8765
```

Captures (Command runs inside the network-denying sandbox; the browser blocks and
records non-loopback requests):

```sh
GNSS_PLAYWRIGHT_MODULE=~/gnss-poc-88-work/node/node_modules/playwright \
  node capture.cjs --run ~/gnss-poc-88-work/runs/cold --out ~/gnss-poc-88-work/captures
```

## Files

| File | Role |
| --- | --- |
| `prepare.py` | runner: plan, sources, processing, packaging, baseline, report |
| `netguard.py` | counting/capping/denying local proxy for every external transfer |
| `geo.py` | dependency-free WGS84 geodesy and point-in-polygon helpers |
| `gterr.py` | GTERR001 encoder and `.gmap.zip` format-1 terrain augmentation |
| `command_bridge.py` | drives an unmodified Command through its existing loopback API |
| `viewer/` | POC reference viewer (not Command) for imagery, land cover, labels |
| `evaluate.py` | independent cross-layer checks (summits vs DEMs, lakes vs land cover and imagery classes) |
| `capture.cjs` | Playwright captures and measurements |
| `design/get-a-map-prototype.html` | simulated Get a Map proposal for operator review |
| `evidence/` | small committed captures and metric summaries |

## Licences and attribution of prepared data

Data is downloaded at preparation time and never committed. Keep these notices with any
redistributed output: OpenStreetMap © contributors, ODbL 1.0 (Geofabrik extracts);
Copernicus DEM GLO-30 © DLR e.V. 2010–2014 and © Airbus Defence and Space GmbH
2014–2018, provided under COPERNICUS by the European Union and ESA; ESA WorldCover
2021 v200, CC BY 4.0; contains modified Copernicus Sentinel data processed by ESA
(Earth Search catalogue by Element 84 on AWS Open Data); Mapzen/Tilezen Skadi terrain
tiles (for the baseline). GDAL/PROJ (MIT/X), osmium-tool (GPL-3.0) and Playwright
(Apache-2.0) are external tools invoked by the POC, not linked into Command.

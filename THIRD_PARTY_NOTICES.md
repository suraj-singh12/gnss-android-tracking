# Third-party notices

GNSS Android Tracking is licensed under [GPL-3.0-only](LICENSE). It includes the
third-party components below, which keep their own licences. All of them are
compatible with GPL-3.0. Their full licence texts ship inside each component's source
distribution.

## Command (Go), compiled into the Command binaries

| Module | Version | Licence |
| --- | --- | --- |
| github.com/ncruces/go-sqlite3 (embeds SQLite, public domain) | v0.28.0 | MIT |
| github.com/ncruces/julianday | v1.0.0 | MIT |
| github.com/tetratelabs/wazero | v1.9.0 | Apache-2.0 |
| github.com/pymaxion/geographiclib-go | v1.1.0 | MIT |
| github.com/qedus/osmpbf | v1.2.0 | MIT |
| google.golang.org/protobuf | v1.26.0 | BSD-3-Clause |
| golang.org/x/sys | v0.35.0 | BSD-3-Clause |
| github.com/stretchr/testify | v1.8.4 | MIT |
| github.com/davecgh/go-spew | v1.1.1 | ISC |
| github.com/pmezard/go-difflib | v1.0.0 | BSD-3-Clause |
| gopkg.in/yaml.v3 | v3.0.1 | MIT and Apache-2.0 |

The Go standard library and toolchain are BSD-3-Clause.

## Android, packaged into the APK

| Library | Version | Licence |
| --- | --- | --- |
| androidx.room:room-runtime, room-ktx | 2.7.1 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.10.2 | Apache-2.0 |
| Kotlin standard library | 2.1.20 | Apache-2.0 |
| com.google.code.gson:gson | 2.12.1 | Apache-2.0 |
| com.squareup.okhttp3:okhttp | 4.12.0 | Apache-2.0 |

Build and test tools that are not shipped (Gradle wrapper under Apache-2.0, Android Gradle Plugin, KSP, Room compiler,
JUnit 4 under EPL-1.0, Robolectric under MIT, AndroidX Test, Playwright under
Apache-2.0) do not affect the licence of the distributed software.

## Command embedded web assets

| Component | Version | Licence |
| --- | --- | --- |
| MapLibre GL JS (`command/web/vendor/maplibre-gl*.mjs`, `maplibre-gl.css`) | 6.19.0 | BSD-3-Clause |
| Noto Sans font files (`command/web/fonts/noto-sans-*.woff2`) | google/fonts checkout | SIL Open Font License 1.1 |

MapLibre GL JS is © MapLibre contributors; its licence text ships in
`command/web/vendor/MAPLIBRE-LICENSE.txt`. Noto fonts are © the Noto Project authors; the
licence text ships in `command/web/fonts/OFL.txt`. Both licences are GPL-3.0
compatible. These assets are embedded into the Command binary for offline operation.

## Map and terrain data

Command downloads map and terrain data at run time; the data is not bundled. It keeps
its own terms, and Command shows the attribution:

- OpenStreetMap data (Overpass, Nominatim, Geofabrik): © OpenStreetMap contributors,
  [ODbL 1.0](https://opendatacommons.org/licenses/odbl/).
- Terrain Tiles on AWS (Mapzen/Tilezen):
  [attribution](https://github.com/tilezen/joerd/blob/master/docs/attribution.md).

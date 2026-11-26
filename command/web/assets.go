// Package web embeds the entire offline dashboard in the Command executable.
package web

import (
	"embed"
	"net/http"
)

//go:embed index.html style.css app.js playback.js playback-enhanced.js library.js recording.js control.js map.js terrain.js sos-alarm.js workspace.js enhanced.js enhanced-style.js vendor/maplibre-gl.mjs vendor/maplibre-gl-shared.mjs vendor/maplibre-gl-worker.mjs vendor/maplibre-gl.css vendor/MAPLIBRE-LICENSE.txt fonts
var files embed.FS

func Handler() http.Handler { return http.FileServer(http.FS(files)) }

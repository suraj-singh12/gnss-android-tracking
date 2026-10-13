// Package web embeds the entire offline dashboard in the Command executable.
package web

import (
	"embed"
	"net/http"
)

//go:embed index.html style.css app.js map.js
var files embed.FS

func Handler() http.Handler { return http.FileServer(http.FS(files)) }

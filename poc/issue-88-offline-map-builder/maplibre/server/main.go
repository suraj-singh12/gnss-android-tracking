// Standalone offline viewer for the Issue #88 MapLibre POC (not GNSS Command).
// Serves the "map" folder next to the executable on 127.0.0.1 only and opens the
// default browser. Standard library only; no network access is needed or made.
package main

import (
	"bytes"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

func main() {
	port := flag.Int("port", 8770, "loopback port")
	noOpen := flag.Bool("no-browser", false, "do not open a browser")
	flag.Parse()
	exe, err := os.Executable()
	if err != nil {
		log.Fatal(err)
	}
	root := filepath.Join(filepath.Dir(exe), "map")
	if _, err := os.Stat(filepath.Join(root, "index.html")); err != nil {
		root = "map" // fallback: run from the unzipped folder
	}
	h := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		switch {
		case strings.HasSuffix(p, ".pbf"):
			if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(p))); err != nil {
				w.WriteHeader(http.StatusNoContent) // empty tile
				return
			}
			w.Header().Set("Content-Type", "application/x-protobuf")
			w.Header().Set("Content-Encoding", "gzip") // tiles are stored gzip-compressed
		case strings.HasSuffix(p, ".png"):
			if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(p))); err != nil {
				w.WriteHeader(http.StatusNoContent)
				return
			}
		case strings.HasSuffix(p, ".mjs"):
			w.Header().Set("Content-Type", "text/javascript")
		}
		w.Header().Set("Cache-Control", "no-cache")
		if p == "/" {
			p = "/index.html"
		}
		// Serve from memory (files are small): avoids sendfile, which some sandboxes block.
		b, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(filepath.Clean("/"+p))))
		if err != nil {
			http.NotFound(w, r)
			return
		}
		http.ServeContent(w, r, filepath.Base(p), time.Time{}, bytes.NewReader(b))
	})
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", *port))
	if err != nil {
		log.Fatalf("cannot listen on port %d (already running?): %v", *port, err)
	}
	url := fmt.Sprintf("http://127.0.0.1:%d/#11/42.5722/1.4847", *port)
	fmt.Println("Offline MapLibre POC (not GNSS Command):", url)
	fmt.Println("Close this window or press Ctrl+C to stop.")
	if !*noOpen {
		openBrowser(url)
	}
	log.Fatal(http.Serve(ln, h))
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}

package core

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func compressedDEM(t *testing.T, lat, lon int) []byte {
	t.Helper()
	raw := make([]byte, 3601*3601*2)
	// A continuous geographically indexed fixture across negative/positive seams.
	for y := 0; y < 3601; y++ {
		for x := 0; x < 3601; x++ {
			binary.BigEndian.PutUint16(raw[(y*3601+x)*2:], uint16(int16(1000+(lat*3600+3600-y+lon*3600+x)%1000)))
		}
	}
	var b bytes.Buffer
	z := gzip.NewWriter(&b)
	z.Write(raw)
	z.Close()
	return b.Bytes()
}
func TestAutomaticDEMBoundsAndComponents(t *testing.T) {
	p := newMapProvider()
	for _, b := range [][4]float64{{0, -57, 0.001, -56.999}, {0, 60, 0.001, 60.001}, {179.999, 0, 180.001, .001}, {1, 1, 0, 0}, {0, 0, 1, 1}} {
		if _, err := p.acquireDEM(context.Background(), b, map[string]bool{"elevation": true}); err == nil {
			t.Fatal("invalid bounds accepted", b)
		}
	}
	for _, c := range []map[string]bool{{}, {"unknown": true}} {
		if _, err := p.acquireDEM(context.Background(), [4]float64{0, 0, .001, .001}, c); err == nil {
			t.Fatal("invalid components")
		}
	}
	if tileName(-1, -1) != "S01W001.hgt" || tileName(0, 0) != "N00E000.hgt" {
		t.Fatal("signed tile names")
	}
}
func TestAutomaticDEMCrossTileMosaic(t *testing.T) {
	tiles := map[string][]byte{}
	for lat := -1; lat <= 0; lat++ {
		for lon := -1; lon <= 0; lon++ {
			name := tileName(lat, lon)
			tiles["/"+name[:3]+"/"+name+".gz"] = compressedDEM(t, lat, lon)
		}
	}
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		b, ok := tiles[r.URL.Path]
		if !ok {
			t.Error(r.URL.Path)
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("User-Agent") == "" {
			t.Error("missing identity")
		}
		w.Write(b)
	}))
	defer server.Close()
	p := newMapProvider()
	p.dem = server.URL
	g, err := p.acquireDEM(context.Background(), [4]float64{-.001, -.001, .001, .001}, map[string]bool{"hillshade": true, "contours": true, "elevation": true})
	if err != nil {
		t.Fatal(err)
	}
	if calls != 4 || len(g.Provenance.Tiles) != 4 || g.ArcSeconds != 1 {
		t.Fatal("mosaic acquisition", calls, g)
	}
	for y := 0; y < g.Rows; y++ {
		for x := 0; x < g.Columns; x++ {
			worldX := int(g.Bounds[0]*3600) + x
			worldY := int(g.Bounds[3]*3600) - y
			want := float64(1000 + (worldX+worldY)%1000)
			v := g.Samples[y*g.Columns+x]
			if v == nil || *v != want {
				t.Fatalf("seam node (%d,%d): %v want %v", x, y, v, want)
			}
		}
	}
	encoded, err := encodeTerrain(g)
	if err != nil {
		t.Fatal(err)
	}
	copy, err := decodeTerrain(encoded)
	if err != nil || !reflect.DeepEqual(copy, g) {
		t.Fatal("provenance roundtrip", err)
	}
}
func TestAutomaticDEMProviderFailures(t *testing.T) {
	valid := compressedDEM(t, 28, 77)
	for _, test := range []struct {
		name   string
		status int
		body   []byte
	}{{"HTTP", 429, nil}, {"invalid gzip", 200, []byte("not HGT")}, {"truncated", 200, valid[:len(valid)-5]}, {"CRC", 200, append(append([]byte{}, valid[:len(valid)-8]...), make([]byte, 8)...)}} {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(test.status); w.Write(test.body) }))
			defer server.Close()
			p := newMapProvider()
			p.dem = server.URL
			if _, err := p.acquireDEM(context.Background(), [4]float64{77.01, 28.01, 77.014, 28.014}, map[string]bool{"elevation": true}); err == nil {
				t.Fatal("invalid provider response accepted")
			}
		})
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	p := newMapProvider()
	if _, err := p.acquireDEM(ctx, [4]float64{77.01, 28.01, 77.014, 28.014}, map[string]bool{"elevation": true}); err == nil {
		t.Fatal("cancellation ignored")
	}
}
func TestAutomaticDEMRetryPersistenceAndIsolation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "command.sqlite")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	s.Now = func() time.Time { return base.Add(-time.Second) }
	m, err := s.saveMap("existing", []byte(validOfflineMap))
	if err != nil {
		t.Fatal(err)
	}
	action(t, s, "start")
	ingest(t, s, message(t, 1, 0, 0, 0, 3))
	ingest(t, s, emergency(t, 2, 1))
	s.Now = func() time.Time { return base.Add(20 * time.Second) }
	before, _ := json.Marshal(snapshot(t, s))
	raw := compressedDEM(t, 28, 77)
	fail := true
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if fail {
			http.Error(w, "outage", 503)
			return
		}
		w.Write(raw)
	}))
	defer server.Close()
	p := newMapProvider()
	p.dem = server.URL
	call := func(origin string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "/local/maps/"+m.ID+"/terrain-download", strings.NewReader(`{"components":{"hillshade":true,"contours":true,"elevation":true}}`))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Origin", origin)
		w := httptest.NewRecorder()
		s.mapRequest(w, r, p)
		return w
	}
	if w := call("https://foreign.example"); w.Code != 403 || calls != 0 {
		t.Fatal("same origin", w.Code)
	}
	if w := call(""); w.Code != 400 {
		t.Fatal(w.Code, w.Body.String())
	}
	fail = false
	if w := call(""); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var first []byte
	s.db.QueryRow(`SELECT data FROM offline_terrain WHERE map_id=?`, m.ID).Scan(&first)
	fail = true
	if w := call(""); w.Code != 400 {
		t.Fatal("failed retry")
	}
	var after []byte
	s.db.QueryRow(`SELECT data FROM offline_terrain WHERE map_id=?`, m.ID).Scan(&after)
	if !bytes.Equal(first, after) {
		t.Fatal("failed retry destroyed terrain")
	}
	now, _ := json.Marshal(snapshot(t, s))
	if !bytes.Equal(before, now) {
		t.Fatal("GNSS/SOS/recording mutated")
	}
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	w := terrainCall(s, "GET", "/local/maps/"+m.ID+"/terrain", nil, "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), "provenance") {
		t.Fatal("offline restart", w.Code)
	}
	var vector []byte
	s.db.QueryRow(`SELECT data FROM offline_maps WHERE id=?`, m.ID).Scan(&vector)
	if string(vector) != validOfflineMap {
		t.Fatal("vector bytes changed")
	}
}

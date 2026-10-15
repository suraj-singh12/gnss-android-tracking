package core

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const validOfflineMap = `{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"highway":"path"},"geometry":{"type":"LineString","coordinates":[[77,28],[77.001,28.001]]}}]}`

func TestOfflineMapPersistenceAndIsolation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "command.sqlite")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	before, err := s.Snapshot(30)
	if err != nil {
		t.Fatal(err)
	}
	m, err := s.saveMap("test roads", []byte(validOfflineMap))
	if err != nil {
		t.Fatal(err)
	}
	after, err := s.Snapshot(30)
	if err != nil {
		t.Fatal(err)
	}
	a, _ := json.Marshal(before)
	b, _ := json.Marshal(after)
	if string(a) != string(b) {
		t.Fatal("cartography changed authoritative tracking state")
	}
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	maps, err := s.listMaps()
	if err != nil || len(maps) != 1 || maps[0].ID != m.ID {
		t.Fatalf("restart lost map: %v %v", maps, err)
	}
	h := s.LocalHandler(http.NotFoundHandler())
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest("GET", "/local/maps/"+m.ID, nil))
	if w.Code != 200 || w.Body.String() != validOfflineMap {
		t.Fatal(w.Code, w.Body.String())
	}
}
func TestOfflineMapGuards(t *testing.T) {
	s, err := Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for _, data := range []string{`{}`, `{"type":"Point","crs":{},"coordinates":[77,28]}`, `{"type":"Point","coordinates":[181,28]}`, `{"type":"Point","coordinates":[0,86]}`, `{"type":"LineString","coordinates":[[-179,1],[179,1]]}`, `{"type":"LineString","coordinates":[[77,28]]}`, `{"type":"Polygon","coordinates":[[[77,28],[77,29],[78,29],[78,28]]]}`, `{"type":"MultiPolygon","coordinates":[[[[77,28],[77,29],[77,28]]]]}`} {
		if _, err = s.saveMap("invalid", []byte(data)); err == nil {
			t.Fatalf("accepted invalid map %s", data)
		}
	}
	h := s.LocalHandler(http.NotFoundHandler())
	r := httptest.NewRequest("POST", "/local/maps", strings.NewReader(`{"source":"test","data":`+validOfflineMap+`}`))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Origin", "https://other.example")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal(w.Code)
	}
	r = httptest.NewRequest("POST", "/local/maps", strings.NewReader(`{"source":"test","data":`+validOfflineMap+`}`))
	r.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	for _, body := range []string{`{"width":500,"height":500}`, `{"lat":null,"lon":77,"width":500,"height":500}`} {
		r = httptest.NewRequest("POST", "/local/map-preview", strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		w = httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code == 200 || !strings.Contains(w.Body.String(), "valid WGS84 center") {
			t.Fatal("missing center was silently interpreted as zero", w.Code, w.Body.String())
		}
	}
}
func TestOfflineMapProviderAndLimits(t *testing.T) {
	var query string
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.Header.Get("User-Agent"), "GNSS-Command/") {
			t.Error("missing provider identification")
		}
		r.ParseForm()
		query = r.Form.Get("data")
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"elements":[{"id":1,"tags":{"highway":"path"},"geometry":[{"lat":28,"lon":77},{"lat":28.001,"lon":77.001}]},{"id":2,"tags":{"building":"yes"},"geometry":[{"lat":28,"lon":77},{"lat":28.001,"lon":77},{"lat":28.001,"lon":77.001},{"lat":28,"lon":77}]}]}`))
	}))
	defer provider.Close()
	p := newMapProvider()
	p.overpass = provider.URL
	b, err := p.area(context.Background(), 28, 77, 500, 500)
	if err != nil {
		t.Fatal(err)
	}
	if err = validateMap(b); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(b), "Polygon") || !strings.Contains(string(b), "OpenStreetMap") || !strings.Contains(query, `way["building"]`) {
		t.Fatal(string(b), query)
	}
	if _, err = p.area(context.Background(), 28, 77, 500, 500); err == nil {
		t.Fatal("provider cooldown missing")
	}
	if _, err = p.area(context.Background(), 28, 180, 500, 500); err == nil {
		t.Fatal("antimeridian area accepted")
	}
}


// A public Overpass timeout or overload must not permanently disable vector
// preparation. A second user-requested Preview can use a different real
// instance; the production API still fails honestly if neither succeeds.
func TestOfflineMapTransientOverpassRecovery(t *testing.T) {
	var originalCalls, backupCalls atomic.Int32
	primary := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		originalCalls.Add(1)
		http.Error(w, "busy", http.StatusServiceUnavailable)
	}))
	defer primary.Close()
	backup := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		backupCalls.Add(1)
		if r.Method != http.MethodPost || r.Header.Get("Content-Type") != "application/x-www-form-urlencoded" {
			t.Errorf("fallback changed wire contract: %s %s", r.Method, r.Header.Get("Content-Type"))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"elements":[{"id":31,"tags":{"highway":"path"},"geometry":[{"lat":28,"lon":77},{"lat":28.001,"lon":77.001}]}]}`))
	}))
	defer backup.Close()

	p := newMapProvider()
	p.overpass, p.overpassFallback = primary.URL, backup.URL
	_, err := p.area(context.Background(), 28, 77, 500, 500)
	if err == nil || !strings.Contains(err.Error(), "retry Preview") {
		t.Fatalf("must report real first-provider failure, got %v", err)
	}
	if originalCalls.Load() != 1 || backupCalls.Load() != 0 {
		t.Fatal("unbounded implicit failover inside one HTTP request")
	}
	// Simulate a new explicit Preview after the provider reservation period.
	p.mu.Lock()
	p.nextArea = time.Time{}
	p.mu.Unlock()
	data, err := p.area(context.Background(), 28, 77, 500, 500)
	if err != nil {
		t.Fatalf("second Preview must recover using fallback: %v", err)
	}
	if err := validateMap(data); err != nil || originalCalls.Load() != 1 || backupCalls.Load() != 1 {
		t.Fatalf("fallback did not produce valid original OSM ways: %v; primary=%d backup=%d", err, originalCalls.Load(), backupCalls.Load())
	}
}

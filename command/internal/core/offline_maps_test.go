package core

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
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

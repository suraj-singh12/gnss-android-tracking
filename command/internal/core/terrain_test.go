package core

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func fixtureHGT(side int) []byte {
	b := make([]byte, side*side*2)
	for y := 0; y < side; y++ {
		for x := 0; x < side; x++ {
			binary.BigEndian.PutUint16(b[(y*side+x)*2:], uint16(100+x+y))
		}
	}
	return b
}
func terrainFixture(t *testing.T) terrainGrid {
	t.Helper()
	g, err := importHGT("N28E077.hgt", fixtureHGT(1201), [4]float64{77.01, 28.01, 77.014, 28.014}, map[string]bool{"hillshade": true, "contours": true, "elevation": true})
	if err != nil {
		t.Fatal(err)
	}
	return g
}
func TestTerrainHGTBoundsResolutionAndOrientation(t *testing.T) {
	g := terrainFixture(t)
	if g.Bounds[0] > 77.01 || g.Bounds[1] > 28.01 || g.Bounds[2] < 77.014 || g.Bounds[3] < 28.014 || g.ArcSeconds != 3 || g.Datum != "EGM96" {
		t.Fatal(g)
	}
	if *g.Samples[1]-*g.Samples[0] != 1 || *g.Samples[g.Columns]-*g.Samples[0] != 1 {
		t.Fatal("north-up row orientation lost")
	}
	g, err := importHGT("S01W001.hgt", fixtureHGT(3601), [4]float64{-.01, -.01, -.009, -.009}, map[string]bool{"elevation": true})
	if err != nil || g.ArcSeconds != 1 {
		t.Fatal(err, g)
	}
}
func TestTerrainHGTGuards(t *testing.T) {
	b := fixtureHGT(1201)
	components := map[string]bool{"elevation": true}
	for _, name := range []string{"map.hgt", "N90E077.hgt", "../N28E077.hgt", "N28E200.hgt"} {
		if _, err := importHGT(name, b, [4]float64{77.01, 28.01, 77.014, 28.014}, components); err == nil {
			t.Fatal(name)
		}
	}
	for _, bounds := range [][4]float64{{76.99, 28.01, 77.01, 28.02}, {77, 28, 78, 29}} {
		if _, err := importHGT("N28E077.hgt", b, bounds, components); err == nil {
			t.Fatal(bounds)
		}
	}
	if _, err := importHGT("N28E077.hgt", b[:10], [4]float64{77.01, 28.01, 77.014, 28.014}, components); err == nil {
		t.Fatal("truncated HGT accepted")
	}
	if _, err := importHGT("N28E077.hgt", b, [4]float64{77.01, 28.01, 77.014, 28.014}, map[string]bool{}); err == nil {
		t.Fatal("no component accepted")
	}
}
func TestTerrainBinaryRoundTripAndValidation(t *testing.T) {
	g := terrainFixture(t)
	g.Samples[0] = nil
	b, err := encodeTerrain(g)
	if err != nil {
		t.Fatal(err)
	}
	restored, err := decodeTerrain(b)
	if err != nil || !reflect.DeepEqual(g, restored) {
		t.Fatal(err)
	}
	if bytes.Contains(b, []byte(`"samples"`)) {
		t.Fatal("raster encoded as JSON")
	}
	for _, invalid := range [][]byte{b[:11], b[:len(b)-1], append(append([]byte{}, b...), 0), []byte("GeoTIFF"), make([]byte, terrainLimit+1)} {
		if _, err := decodeTerrain(invalid); err == nil {
			t.Fatal("invalid package accepted")
		}
	}
	g.Columns = 258
	if _, err = encodeTerrain(g); err == nil {
		t.Fatal("unbounded grid accepted")
	}
}
func TestTerrainVoidAndMetadataGuards(t *testing.T) {
	g := terrainFixture(t)
	for i := range g.Samples {
		g.Samples[i] = nil
	}
	if _, err := encodeTerrain(g); err == nil {
		t.Fatal("all void accepted")
	}
	for _, modify := range []func(*terrainGrid){func(g *terrainGrid) { g.Datum = "ellipsoid" }, func(g *terrainGrid) { g.ArcSeconds = 1 }, func(g *terrainGrid) { g.Components["fake"] = true }, func(g *terrainGrid) { n := 1.5; g.Samples[0] = &n }} {
		g = terrainFixture(t)
		modify(&g)
		if _, err := encodeTerrain(g); err == nil {
			t.Fatal("bad metadata accepted")
		}
	}
}
func terrainCall(s *Store, method, path string, body any, origin string) *httptest.ResponseRecorder {
	b, _ := json.Marshal(body)
	r := httptest.NewRequest(method, path, bytes.NewReader(b))
	r.Header.Set("Content-Type", "application/json")
	if origin != "" {
		r.Header.Set("Origin", origin)
	}
	w := httptest.NewRecorder()
	s.LocalHandler(http.NotFoundHandler()).ServeHTTP(w, r)
	return w
}
func TestTerrainPersistenceExportImportDeleteAndIsolation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old-command.sqlite")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	m, err := s.saveMap("old vector", []byte(validOfflineMap))
	if err != nil {
		t.Fatal(err)
	}
	// This private fixture DB models the PR #17 schema before terrain existed.
	if _, err = s.db.Exec(`DROP TABLE offline_terrain`); err != nil {
		t.Fatal(err)
	}
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	s.Now = func() time.Time { return base.Add(-time.Second) }
	action(t, s, "start")
	ingest(t, s, message(t, 1, 0, 0, 0, 3))
	ingest(t, s, message(t, 2, 10, 30, 0, 3))
	ingest(t, s, emergency(t, 3, 11))
	s.Now = func() time.Time { return base.Add(20 * time.Second) }
	before := snapshot(t, s)
	if before.Recording == nil || len(before.RawPoints) != 2 || len(before.Alerts) != 1 {
		t.Fatal("isolation fixture must include recording, observations and pending SOS")
	}
	beforeJSON, _ := json.Marshal(before)
	body := map[string]any{"name": "N28E077.hgt", "data": fixtureHGT(1201), "components": map[string]bool{"hillshade": true, "contours": true, "elevation": true}}
	w := terrainCall(s, "POST", "/local/maps/"+m.ID+"/terrain", body, "")
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	after, _ := s.Snapshot(30)
	afterJSON, _ := json.Marshal(after)
	if !bytes.Equal(beforeJSON, afterJSON) {
		t.Fatal("terrain altered authoritative state")
	}
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.Now = func() time.Time { return base.Add(20 * time.Second) }
	w = terrainCall(s, "GET", "/local/maps/"+m.ID+"/terrain", nil, "")
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	exported := terrainCall(s, "GET", "/local/maps/"+m.ID+"/terrain-file", nil, "")
	if exported.Code != 200 {
		t.Fatal(exported.Body.String())
	}
	if w = terrainCall(s, "POST", "/local/maps/"+m.ID+"/terrain", map[string]any{"name": "area.gterrain", "data": exported.Body.Bytes()}, ""); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if w = terrainCall(s, "GET", "/local/maps/"+m.ID, nil, ""); w.Body.String() != validOfflineMap {
		t.Fatal("vector export changed")
	}
	if w = terrainCall(s, "POST", "/local/maps/"+m.ID+"/delete", nil, ""); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if terrainCall(s, "GET", "/local/maps/"+m.ID+"/terrain", nil, "").Code != 404 {
		t.Fatal("orphaned terrain")
	}
	if terrainCall(s, "GET", "/local/maps/"+m.ID, nil, "").Code != 404 {
		t.Fatal("map not deleted")
	}
	retained, _ := json.Marshal(snapshot(t, s))
	if !bytes.Equal(beforeJSON, retained) {
		t.Fatal("restart/map deletion altered GNSS, recording or pending SOS")
	}
}
func TestTerrainAPICompatibilityAndSecurity(t *testing.T) {
	s, err := Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	m, err := s.saveMap("vector only", []byte(validOfflineMap))
	if err != nil {
		t.Fatal(err)
	}
	if terrainCall(s, "GET", "/local/maps/"+m.ID+"/terrain", nil, "").Code != 404 {
		t.Fatal("imagined terrain")
	}
	if terrainCall(s, "POST", "/local/maps/"+m.ID+"/delete", nil, "https://other.example").Code != 403 {
		t.Fatal("cross-origin delete")
	}
	if terrainCall(s, "POST", "/local/maps/"+m.ID+"/terrain", nil, "").Code != 400 {
		t.Fatal("invalid terrain accepted")
	}
	w := httptest.NewRecorder()
	s.IngestHandler().ServeHTTP(w, httptest.NewRequest("GET", "/local/maps/"+m.ID+"/terrain", nil))
	if w.Code != 404 {
		t.Fatal("terrain on ingestion listener")
	}
	r := httptest.NewRequest("POST", "/local/maps/"+m.ID+"/terrain", bytes.NewReader([]byte(`{}`)))
	r.Header.Set("Content-Type", "application/json; charset=utf-8")
	w = httptest.NewRecorder()
	s.LocalHandler(http.NotFoundHandler()).ServeHTTP(w, r)
	if w.Code != 400 || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("JSON charset compatibility/cache guard", w.Code, w.Header())
	}
}

package core

import (
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/suraj-singh12/gnss-android-tracking/command/web"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func realMapSample(t *testing.T, name string) []byte {
	t.Helper()
	var catalog map[string]struct {
		SHA string `json:"sha256"`
	}
	b, err := os.ReadFile("testdata/maps/provenance.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(b, &catalog); err != nil {
		t.Fatal(err)
	}
	b, err = os.ReadFile(filepath.Join("testdata/maps", name))
	if err != nil {
		t.Fatal(err)
	}
	if catalog[name].SHA == "" || shaMap(b) != catalog[name].SHA {
		t.Fatal("original source integrity failure", name)
	}
	return b
}
func realHGT(t *testing.T) []byte {
	t.Helper()
	b := realMapSample(t, "N58W134.SRTMGL1.hgt.zip")
	z, err := zip.NewReader(bytes.NewReader(b), int64(len(b)))
	if err != nil {
		t.Fatal(err)
	}
	r, err := z.File[0].Open()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	raw, err := io.ReadAll(io.LimitReader(r, demRawSize+1))
	if err != nil || len(raw) != demRawSize || shaMap(raw) != "3ba3b8ab608e1adccf3bf91130c441278f8c1de8911222ca8a3176090502eded" {
		t.Fatal("real HGT integrity", err)
	}
	return raw
}
func replayDEM(t *testing.T, raw []byte) []byte {
	t.Helper()
	var out bytes.Buffer
	z := gzip.NewWriter(&out)
	if _, err := z.Write(raw); err != nil {
		t.Fatal(err)
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}
func mapAcceptanceHandler(s *Store, p *mapProvider) http.Handler {
	local := s.LocalHandler(web.Handler())
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.mapRequest(w, r, p) {
			return
		}
		local.ServeHTTP(w, r)
	})
}
func mapAPICall(t *testing.T, h http.Handler, route string, body any, want int) []byte {
	t.Helper()
	method := "GET"
	var data []byte
	if body != nil {
		method = "POST"
		data = mustJSON(body)
	}
	req := httptest.NewRequest(method, route, bytes.NewReader(data))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != want {
		t.Fatalf("%s %s: HTTP %d want %d: %s", method, route, w.Code, want, w.Body.String())
	}
	return w.Body.Bytes()
}
func approveAPI(t *testing.T, s *Store, p *mapProvider, id string, n int64, want int) {
	t.Helper()
	mapAPICall(t, mapAcceptanceHandler(s, p), "/local/maps/"+id+"/approve-transfer", map[string]int64{"approved_bytes": n}, want)
}
func TestMapApprovalAPIRefusalInsufficientRepeatedAndRestart(t *testing.T) {
	s := extensionStore(t)
	p := newMapProvider()
	m, err := s.createMapJob(mapCircle{.1, .1, 373}, nil, true)
	if err != nil {
		t.Fatal(err)
	}
	state, _ := s.transferState(m.ID)
	if state.Allowance != 5_000_000 {
		t.Fatal("confirmed must not authorize downloads", state)
	}
	_, err = s.changeTransfer(m.ID, func(t *mapTransfer) error {
		t.Charged = 4_000_000
		t.Received = 4_000_000
		t.Required = 8_000_000
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	j, _ := s.readMapJob(m.ID)
	if err = s.acquireMapChunk(ctx, p, m.ID, j.Plan.Chunks[0].Key, "vector", nil); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	state, _ = s.transferState(m.ID)
	if state.Allowance != 5_000_000 || state.Required != 8_000_000 {
		t.Fatal(state)
	}
	approveAPI(t, s, p, m.ID, 7_999_999, 400)
	approveAPI(t, s, p, m.ID, mapTransferLimit+1, 400)
	unchanged, _ := s.transferState(m.ID)
	if state != unchanged {
		t.Fatal("refused/insufficient approval mutated ledger", unchanged)
	}
	path := s.path
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	approveAPI(t, s, p, m.ID, 8_000_000, 200)
	approveAPI(t, s, p, m.ID, 8_000_000, 200)
	approveAPI(t, s, p, m.ID, 5_000_000, 400)
	_, err = s.changeTransfer(m.ID, func(t *mapTransfer) error { t.Charged += 32768; return nil })
	if err != nil {
		t.Fatal(err)
	}
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	state, _ = s.transferState(m.ID)
	if state.Allowance != 8_000_000 || state.Charged != 4_032_768 || state.Received != 4_000_000 || state.Required != 0 {
		t.Fatal(state)
	}
}

type countedBody struct {
	io.Reader
	reads *atomic.Int64
}

func (b countedBody) Read(p []byte) (int, error) {
	n, e := b.Reader.Read(p)
	b.reads.Add(int64(n))
	return n, e
}
func (b countedBody) Close() error { return nil }

type fixtureTransport func(*http.Request) (*http.Response, error)

func (f fixtureTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestMapApprovalNoBodyBeforeConsentAndIndependentSources(t *testing.T) {
	s := extensionStore(t)
	m, _ := s.createMapJob(mapCircle{.1, .1, 373}, nil, true)
	var read atomic.Int64
	transport := fixtureTransport(func(r *http.Request) (*http.Response, error) {
		n := int64(6_000_000)
		code := 200
		if strings.Contains(r.URL.Path, "large") {
			n = 228_000_000
		}
		if strings.Contains(r.URL.Path, "error") {
			code = 502
		}
		return &http.Response{StatusCode: code, Header: make(http.Header), ContentLength: n, Body: countedBody{bytes.NewReader(make([]byte, 6_000_000)), &read}}, nil
	})
	ctx := context.WithValue(context.Background(), mapBudgetKey{}, &mapBudget{s: s, id: m.ID})
	client := mapHTTPClient(ctx, &http.Client{Transport: transport})
	if _, err := client.Get("https://provider.invalid/large"); !errors.Is(err, errMapConsent) {
		t.Fatal(err)
	}
	if read.Load() != 0 {
		t.Fatal("unapproved body read")
	}
	if _, err := client.Get("https://provider.invalid/dem"); !errors.Is(err, errMapConsent) {
		t.Fatal(err)
	}
	state, _ := s.transferState(m.ID)
	if state.Required != 6_000_000 || state.Allowance != 5_000_000 || state.Received != 0 {
		t.Fatal("stale region contaminated DEM approval", state)
	}
	if err := s.approveMapTransfer(m.ID, state.Required); err != nil {
		t.Fatal(err)
	}
	r, err := client.Get("https://provider.invalid/dem")
	if err != nil {
		t.Fatal(err)
	}
	n, err := io.Copy(io.Discard, r.Body)
	r.Body.Close()
	if err != nil || n != 6_000_000 {
		t.Fatal(n, err)
	}
	if _, err = client.Get("https://provider.invalid/dem"); !errors.Is(err, errMapConsent) {
		t.Fatal(err)
	}
	state, _ = s.transferState(m.ID)
	if state.Charged != 6_000_000 || state.Received != 6_000_000 || state.Allowance != 6_000_000 {
		t.Fatal(state)
	}
	if err = s.approveMapTransfer(m.ID, 11_000_000); err != nil {
		t.Fatal(err)
	}
	r, err = client.Get("https://provider.invalid/large-error")
	if err != nil || r.StatusCode != 502 {
		t.Fatal("provider failure hidden", err)
	}
	r.Body.Close()
	if read.Load() != 6_000_000 {
		t.Fatal("error/approval body unexpectedly consumed")
	}
}
func TestMapRealDEMProductionAcquisitionApprovalPersistence(t *testing.T) {
	raw := realHGT(t)
	compressed := replayDEM(t, raw)
	s := extensionStore(t)
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Length", strconv.Itoa(len(compressed)))
		w.Write(compressed)
	}))
	defer server.Close()
	p := newMapProvider()
	p.dem = server.URL
	p.demClient = server.Client()
	m, err := s.createMapJob(mapCircle{58.6101, -133.8101, 373}, map[string]bool{"hillshade": true, "contours": true, "elevation": true}, true)
	if err != nil {
		t.Fatal(err)
	}
	j, _ := s.readMapJob(m.ID)
	if len(j.Plan.Chunks) != 1 {
		t.Fatal(j.Plan.Chunks)
	}
	c := j.Plan.Chunks[0]
	route := "/local/maps/" + m.ID + "/chunks/" + c.Key
	h := mapAcceptanceHandler(s, p)
	data := mapAPICall(t, h, route, map[string]string{"layer": "terrain"}, 409)
	if !bytes.Contains(data, []byte("transfer_approval_required")) {
		t.Fatal(string(data))
	}
	state, _ := s.transferState(m.ID)
	if state.Allowance != 5_000_000 || state.Received != 0 || state.Required != int64(len(compressed)) {
		t.Fatal(state)
	}
	approveAPI(t, s, p, m.ID, state.Required-1, 400)
	approveAPI(t, s, p, m.ID, state.Required, 200)
	mapAPICall(t, h, route, map[string]string{"layer": "terrain"}, 200)
	gridJSON := mapAPICall(t, h, route+"?layer=terrain", nil, 200)
	var grid terrainGrid
	if err = json.Unmarshal(gridJSON, &grid); err != nil {
		t.Fatal(err)
	}
	if grid.ArcSeconds != 1 || grid.Datum != "EGM96" || len(grid.Provenance.Tiles) != 1 || grid.Provenance.Tiles[0].SHA256 != shaMap(raw) {
		t.Fatal("native provenance", grid.Provenance)
	}
	for y := 0; y < grid.Rows; y++ {
		for x := 0; x < grid.Columns; x++ {
			row := int((59-grid.Bounds[3])*3600+.5) + y
			col := int((grid.Bounds[0]+134)*3600+.5) + x
			v := int16(binary.BigEndian.Uint16(raw[2*(row*3601+col):]))
			got := grid.Samples[y*grid.Columns+x]
			if (v == -32768 && got != nil) || (v != -32768 && (got == nil || *got != float64(v))) {
				t.Fatal("geographic sample mismatch", x, y)
			}
		}
	}
	original := mapAPICall(t, h, route+"?layer=terrain&format=gterrain", nil, 200)
	path := s.path
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	p = newMapProvider()
	p.dem = "http://127.0.0.1:1"
	h = mapAcceptanceHandler(s, p)
	mapAPICall(t, h, route, map[string]string{"layer": "terrain"}, 200)
	if !bytes.Equal(original, mapAPICall(t, h, route+"?layer=terrain&format=gterrain", nil, 200)) {
		t.Fatal("restart corrupted original terrain")
	}
	state, _ = s.transferState(m.ID)
	if state.Received != int64(len(compressed)) || state.Allowance != int64(len(compressed)) || calls.Load() != 2 {
		t.Fatal(state, calls.Load())
	}
	t.Logf("real SRTMGL1 decoded=%d compressed replay=%d every native node verified; original SHA256=%s", len(raw), len(compressed), shaMap(raw))
}
func TestMapRealPBFFallbackApprovalAndOfflineReuse(t *testing.T) {
	pbf := realMapSample(t, "delaware-latest.osm.pbf")
	s := extensionStore(t)
	var address string
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/overpass":
			w.WriteHeader(502)
		case "/index":
			json.NewEncoder(w).Encode(map[string]any{"features": []any{
				map[string]any{"properties": map[string]any{"name": "large parent", "urls": map[string]string{"pbf": address + "/large.osm.pbf"}}, "geometry": json.RawMessage(`{"type":"Polygon","coordinates":[[[-80,30],[-70,30],[-70,45],[-80,45],[-80,30]]]}`)},
				map[string]any{"properties": map[string]any{"name": "Delaware real sample", "urls": map[string]string{"pbf": address + "/delaware.osm.pbf"}}, "geometry": json.RawMessage(`{"type":"Polygon","coordinates":[[[-75.78974,38.45043],[-74.96121,38.45043],[-74.96121,40.03221],[-75.78974,40.03221],[-75.78974,38.45043]]]}`)},
			}})
		case "/large.osm.pbf":
			t.Error("selected large parent instead of smaller fully covering source")
			w.Header().Set("Content-Length", "228000000")
		case "/delaware.osm.pbf":
			calls.Add(1)
			w.Header().Set("Content-Length", strconv.Itoa(len(pbf)))
			w.Write(pbf)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	address = server.URL
	p := newMapProvider()
	p.overpass = address + "/overpass"
	p.catalog = address + "/index"
	p.client = server.Client()
	m, err := s.createMapJob(mapCircle{39.745, -75.548, 373}, nil, true)
	if err != nil {
		t.Fatal(err)
	}
	j, _ := s.readMapJob(m.ID)
	c := j.Plan.Chunks[0]
	if err = s.acquireMapChunk(context.Background(), p, m.ID, c.Key, "vector", nil); !errors.Is(err, errMapConsent) {
		t.Fatal(err)
	}
	state, _ := s.transferState(m.ID)
	if state.Required != state.Charged+int64(len(pbf)) || state.Allowance != mapConsentBytes {
		t.Fatal(state)
	}
	approveAPI(t, s, p, m.ID, state.Required, 200)
	if err = s.acquireMapChunk(context.Background(), p, m.ID, c.Key, "vector", nil); err != nil {
		t.Fatal(err)
	}
	sources, err := s.osmSources()
	if err != nil || len(sources) != 1 || sources[0].SHA != shaMap(pbf) {
		t.Fatal(sources, err)
	}
	h := mapAcceptanceHandler(s, p)
	route := "/local/maps/" + m.ID + "/chunks/" + c.Key + "?layer=vector"
	original := mapAPICall(t, h, route, nil, 200)
	var geo struct {
		Features []json.RawMessage `json:"features"`
		Bounds   [4]float64        `json:"requested_bounds"`
	}
	json.Unmarshal(original, &geo)
	if len(geo.Features) == 0 || geo.Bounds != c.Bounds {
		t.Fatal("real crop missing", string(original))
	}
	src, err := s.indexPBF(context.Background(), filepath.Join("testdata/maps", "delaware-latest.osm.pbf"), osmSource{}, nil)
	if err != nil || src.ID != sources[0].ID {
		t.Fatal(err)
	}
	path := s.path
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	server.Close()
	if err = s.acquireMapChunk(context.Background(), p, m.ID, c.Key, "vector", nil); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(original, mapAPICall(t, mapAcceptanceHandler(s, p), route, nil, 200)) || calls.Load() != 2 {
		t.Fatal("offline source changed", calls.Load())
	}
	t.Logf("real PBF bytes=%d nodes=%d ways=%d crop_features=%d bounds=%v revision=%s", len(pbf), src.Nodes, src.Ways, len(geo.Features), src.Bounds, src.Revision)
}

// Test-only dependency injection: production handlers, provider and ledger;
// no configurable provider or authorization bypass in the production binary.
func TestMapAcquisitionBrowserServer(t *testing.T) {
	config := os.Getenv("GNSS_ACQUISITION_SERVER_CONFIG")
	if config == "" {
		t.Skip("test harness explicitly invoked by map-acquisition-deterministic.cjs")
	}
	raw := realHGT(t)
	compressed := replayDEM(t, raw)
	s, err := Open(os.Getenv("GNSS_ACCEPTANCE_DB"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, ".hgt.gz") {
			w.Header().Set("Content-Length", strconv.Itoa(len(compressed)))
			w.Write(compressed)
			return
		}
		io.WriteString(w, `{"elements":[{"id":1,"tags":{"highway":"path","name":"Controlled geometry, not OSM evidence"},"geometry":[{"lat":58.609,"lon":-133.812},{"lat":58.612,"lon":-133.808}]}]}`)
	}))
	defer provider.Close()
	p := newMapProvider()
	p.overpass = provider.URL + "/overpass"
	p.dem = provider.URL
	p.client = provider.Client()
	p.demClient = provider.Client()
	server := httptest.NewServer(mapAcceptanceHandler(s, p))
	defer server.Close()
	rawPath := filepath.Join(filepath.Dir(config), "reference.hgt")
	if err = os.WriteFile(rawPath, raw, 0600); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(config, mustJSON(map[string]any{"local": server.URL, "raw": rawPath, "pid": os.Getpid()}), 0600); err != nil {
		t.Fatal(err)
	}
	fmt.Println("controlled provider ready", server.URL)
	timer := time.NewTimer(15 * time.Minute)
	defer timer.Stop()
	<-timer.C
}

func TestMapControlledFailurePreservesPreviouslyValidAssets(t *testing.T) {
	raw := realHGT(t)
	compressed := replayDEM(t, raw)
	for _, kind := range []string{"HTTP 502", "header timeout", "truncated", "malformed", "disk limitation"} {
		t.Run(kind, func(t *testing.T) {
			s := extensionStore(t)
			saved, err := s.saveMap("previous valid map", []byte(validOfflineMap))
			if err != nil {
				t.Fatal(err)
			}
			var address string
			provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch kind {
				case "HTTP 502":
					w.WriteHeader(502)
				case "header timeout":
					<-r.Context().Done()
				case "truncated":
					w.Write(compressed[:100])
				case "malformed":
					io.WriteString(w, "not a gzip")
				default:
					w.Header().Set("Content-Length", strconv.Itoa(len(compressed)))
					w.Write(compressed)
				}
			}))
			defer provider.Close()
			address = provider.URL
			p := newMapProvider()
			p.dem = address
			p.demClient = provider.Client()
			p.demTestBackoff = time.Millisecond
			p.demTestAttemptBudget = 50 * time.Millisecond
			m, _ := s.createMapJob(mapCircle{58.6101, -133.8101, 373}, map[string]bool{"elevation": true}, true)
			j, _ := s.readMapJob(m.ID)
			c := j.Plan.Chunks[0]
			// A validated vector chunk must survive every failed DEM operation.
			b := mustJSON(map[string]any{"type": "FeatureCollection", "features": []any{}, "requested_bounds": c.Bounds, "acquisition": "complete"})
			if err = s.writeChunk(context.Background(), m.ID, c.Key, "vector", b, chunkEvidence{Status: "complete", Bytes: len(b)}); err != nil {
				t.Fatal(err)
			}
			approveAPI(t, s, p, m.ID, 30_000_000, 200)
			if kind == "disk limitation" {
				p.demTestAttemptBudget = 5 * time.Second
				_, err = s.db.Exec(`CREATE TRIGGER dem_disk_fault BEFORE UPDATE OF terrain ON offline_map_chunks WHEN NEW.terrain IS NOT NULL BEGIN SELECT RAISE(ABORT,'injected disk full'); END`)
				if err != nil {
					t.Fatal(err)
				}
			}
			err = s.acquireMapChunk(context.Background(), p, m.ID, c.Key, "terrain", nil)
			if err == nil {
				t.Fatal("failure claimed success")
			}
			h := mapAcceptanceHandler(s, p)
			if !bytes.Equal(b, mapAPICall(t, h, "/local/maps/"+m.ID+"/chunks/"+c.Key+"?layer=vector", nil, 200)) {
				t.Fatal("failed terrain changed validated vector")
			}
			mapAPICall(t, h, "/local/maps/"+m.ID+"/chunks/"+c.Key+"?layer=terrain", nil, 404)
			if !bytes.Equal([]byte(validOfflineMap), mapAPICall(t, h, "/local/maps/"+saved.ID, nil, 200)) {
				t.Fatal("old offline map changed")
			}
			status, _ := s.mapChunkStatuses(m.ID, j)
			if status[0].Terrain.Status == "complete" || status[0].Terrain.Error == "" {
				t.Fatal(status)
			}
			t.Logf("%s: %s; valid vector and previous offline map retained", kind, err)
		})
	}
}

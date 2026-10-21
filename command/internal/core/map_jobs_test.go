package core

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// Equatorial WGS84 distances are independent analytical references (a*pi/180).
func TestRadiusEllipsoidGeometry(t *testing.T) {
	for _, r := range []float64{373, 12345, 151731} {
		p, err := planCircle(mapCircle{0, 0, r})
		t.Logf("equatorial radius=%g m chunks=%d source_tiles=%d", r, len(p.Chunks), len(p.SourceTiles))
		if err != nil || p.Unsupported != "" {
			t.Fatal(err, p.Unsupported)
		}
		if math.Abs(p.AreaM2/(math.Pi*r*r)-1) > 0.0001 {
			t.Fatal("circle area reference", p.AreaM2, r)
		}
		east := p.Circle[180]
		want := r / 6378137 * 180 / math.Pi
		if math.Abs(east[0]-want) > 1e-10 || math.Abs(east[1]) > 1e-10 {
			t.Fatal("ellipsoidal east reference", east, want)
		}
		for _, v := range p.Circle {
			covered := false
			for _, c := range p.Chunks {
				if v[0] >= c.Bounds[0]-1e-10 && v[0] <= c.Bounds[2]+1e-10 && v[1] >= c.Bounds[1]-1e-10 && v[1] <= c.Bounds[3]+1e-10 {
					covered = true
					break
				}
			}
			if !covered {
				t.Fatal("requested circle truncated", v)
			}
		}
		if r > 150000 && len(p.SourceTiles) <= 4 {
			t.Fatal("old source tile cap", len(p.SourceTiles))
		}
	}
	for _, loc := range []mapCircle{{28.6139, 77.209, 373}, {27.9999, 86.9999, 12345}, {0, 179.999, 12345}, {59.99, 10, 12345}, {84, 0, 373}} {
		p, e := planCircle(loc)
		if e != nil {
			t.Fatal(e)
		}
		if loc.Lon > 179 {
			seenW, seenE := false, false
			for _, c := range p.Chunks {
				seenW = seenW || c.Bounds[0] < -179
				seenE = seenE || c.Bounds[2] > 179
			}
			if !seenW || !seenE {
				t.Fatal("antimeridian not split")
			}
		}
		if loc.Lat > 59 && p.DEMUnsupported == "" {
			t.Fatal("source restriction hidden")
		}
	}
	for _, c := range []mapCircle{{90, 0, 1}, {85, 0, 100000}, {0, 0, 1e12}, {0, 0, 900000}} {
		p, e := planCircle(c)
		if e != nil || p.Unsupported == "" {
			t.Fatal("specific resource/source refusal missing", c, e)
		}
	}
	for _, c := range []mapCircle{{91, 0, 1}, {0, 181, 1}, {0, 0, 0}, {0, 0, -1}, {0, 0, math.Inf(1)}} {
		if _, e := planCircle(c); e == nil {
			t.Fatal("invalid request")
		}
	}
}
func TestRadiusPartialRestartIdempotenceAndProviderErrors(t *testing.T) {
	path := filepath.Join(t.TempDir(), "command.sqlite")
	s, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	m, e := s.createMapJob(mapCircle{.033, .033, 373}, nil, true)
	if e != nil {
		t.Fatal(e)
	}
	duplicate, e := s.createMapJob(mapCircle{.033, .033, 373}, nil, true)
	if e != nil || duplicate.ID != m.ID {
		t.Fatal("response-loss duplicate", e)
	}
	if err := mapDiskReserve(path, ^uint64(0)); err == nil {
		t.Fatal("disk reserve limit not enforced")
	}
	if _, err := s.createMapJob(mapCircle{.1, .1, 12345}, nil, false); err == nil {
		t.Fatal("large job confirmation missing")
	}
	j, _ := s.readMapJob(m.ID)
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.Write([]byte(`{"elements":[]}`)) }))
	defer server.Close()
	p := newMapProvider()
	p.overpass = server.URL
	if e = s.acquireMapChunk(context.Background(), p, m.ID, j.Plan.Chunks[0].Key, "vector", nil); e != nil {
		t.Fatal("empty valid data is coverage", e)
	}
	if e = s.acquireMapChunk(context.Background(), p, m.ID, j.Plan.Chunks[0].Key, "vector", nil); e != nil || calls.Load() != 1 {
		t.Fatal("complete retry redownloaded", e)
	}
	s.Close()
	s, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	statuses, e := s.mapChunkStatuses(m.ID, j)
	if e != nil || statuses[0].Vector.Status != "complete" {
		t.Fatal("restart lost evidence", e, statuses)
	}
	var data []byte
	s.db.QueryRow(`SELECT vector FROM offline_map_chunks WHERE map_id=?`, m.ID).Scan(&data)
	var root map[string]any
	json.Unmarshal(data, &root)
	if len(root["features"].([]any)) != 0 || root["acquisition"] != "complete" {
		t.Fatal("empty saved geometry dishonest", string(data))
	}
	cancelCtx, cancel := context.WithCancel(context.Background())
	cancel()
	if e = s.acquireMapChunk(cancelCtx, p, m.ID, j.Plan.Chunks[0].Key, "terrain", nil); !errors.Is(e, context.Canceled) {
		t.Fatal(e)
	}
	for _, code := range []int{403, 404, 429, 502, 503} {
		t.Run(http.StatusText(code), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Retry-After", "120")
				w.WriteHeader(code)
			}))
			defer server.Close()
			p := newMapProvider()
			p.overpass = server.URL
			_, err := p.areaBounds(context.Background(), j.Plan.Chunks[0].Bounds)
			if err == nil || !strings.Contains(err.Error(), http.StatusText(code)) && !strings.Contains(err.Error(), "HTTP") {
				t.Fatal(err)
			}
			if code == 429 && time.Until(p.nextArea) < 119*time.Second {
				t.Fatal("Retry-After ignored")
			}
		})
	}
}
func TestRadiusNativeDEMCacheAndSavedSamples(t *testing.T) {
	s, e := Open(filepath.Join(t.TempDir(), "command.sqlite"))
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	m, e := s.createMapJob(mapCircle{.1, .1, 373}, map[string]bool{"elevation": true}, true)
	if e != nil {
		t.Fatal(e)
	}
	j, _ := s.readMapJob(m.ID)
	raw := compressedDEM(t, 0, 0)
	var calls atomic.Int32
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.Write(raw) }))
	defer provider.Close()
	p := newMapProvider()
	p.dem = provider.URL
	acquisitionStarted := time.Now()
	if e = s.acquireMapChunk(context.Background(), p, m.ID, j.Plan.Chunks[0].Key, "terrain", nil); e != nil {
		t.Fatal(e)
	}
	acquisitionElapsed := time.Since(acquisitionStarted)
	reloadStarted := time.Now()
	var encoded []byte
	s.db.QueryRow(`SELECT terrain FROM offline_map_chunks WHERE map_id=?`, m.ID).Scan(&encoded)
	g, e := decodeTerrain(encoded)
	t.Logf("native fixture requests=%d transferred=%d decoded_source=%d stored=%d acquisition_and_save=%s reload_decode=%s samples=%d", calls.Load(), len(raw), demRawSize, len(encoded), acquisitionElapsed, time.Since(reloadStarted), len(g.Samples))
	if e != nil {
		t.Fatal(e)
	}
	for y := 0; y < g.Rows; y++ {
		for x := 0; x < g.Columns; x++ {
			lon := int(math.Round(g.Bounds[0]*3600)) + x
			lat := int(math.Round(g.Bounds[3]*3600)) - y
			want := float64(1000 + (lat+lon)%1000)
			if g.Samples[y*g.Columns+x] == nil || *g.Samples[y*g.Columns+x] != want {
				t.Fatal("native saved sample alignment", x, y, want, g.Samples[y*g.Columns+x], g.Bounds, g.Rows, g.Columns)
			}
		}
	}
	upgraded, err := s.createMapJob(mapCircle{.1, .1, 373}, map[string]bool{"contours": true, "hillshade": true}, true)
	if err != nil || upgraded.ID != m.ID {
		t.Fatal("adding products duplicated map", err)
	}
	if err = s.acquireMapChunk(context.Background(), p, m.ID, j.Plan.Chunks[0].Key, "terrain", nil); err != nil || calls.Load() != 1 {
		t.Fatal("saved DEM products redownloaded source", err)
	}
	var updated []byte
	s.db.QueryRow(`SELECT terrain FROM offline_map_chunks WHERE map_id=?`, m.ID).Scan(&updated)
	derived, err := decodeTerrain(updated)
	if err != nil || !derived.Components["contours"] || !derived.Components["hillshade"] {
		t.Fatal("saved source product upgrade", err)
	}
	tmp := filepath.Join(p.demCache, "N00E000.hgt.tmp")
	if err = os.WriteFile(tmp, []byte("interrupted cache publication"), 0600); err != nil {
		t.Fatal(err)
	}
	p.prepared = nil
	if _, e = p.acquireDEM(context.Background(), j.Plan.Chunks[0].Bounds, j.Components); e != nil || calls.Load() != 1 {
		t.Fatal("verified cache was not reused", e, calls.Load())
	}
	if _, err = os.Stat(tmp); !os.IsNotExist(err) {
		t.Fatal("crash temporary cache entry was not removed", err)
	}
	// Analytically georeferenced signed elevation / void fixture, independent decoder.
	source := make([]byte, 1201*1201*2)
	for y := 0; y < 1201; y++ {
		for x := 0; x < 1201; x++ {
			binary.BigEndian.PutUint16(source[2*(y*1201+x):], uint16(int16(-100+x-y)))
		}
	}
	binary.BigEndian.PutUint16(source[2*(1190*1201+10):], 32768)
	crop, e := importHGT("N00E000.hgt", source, [4]float64{.008, .008, .012, .012}, map[string]bool{"elevation": true})
	if e != nil {
		t.Fatal(e)
	}
	void := false
	for y := 0; y < crop.Rows; y++ {
		for x := 0; x < crop.Columns; x++ {
			sx := int(math.Round(crop.Bounds[0]*1200)) + x
			sy := 1200 - int(math.Round(crop.Bounds[3]*1200)) + y
			v := crop.Samples[y*crop.Columns+x]
			if sx == 10 && sy == 1190 {
				void = v == nil
			} else if v == nil || *v != float64(-100+sx-sy) {
				t.Fatal("signed native row/col", x, y, v)
			}
		}
	}
	if !void {
		t.Fatal("void invented")
	}
}

// Opt-in deterministic seed for the real-server browser acceptance. This is a
// synthetic surface, explicitly separate from real public-provider evidence.
func TestRadiusBrowserFixture(t *testing.T) {
	path := os.Getenv("GNSS_MAP_FIXTURE_DB")
	if path == "" {
		t.Skip("browser fixture seed not requested")
	}
	s, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	m, e := s.createMapJob(mapCircle{27.3739, 88.7618, 373}, map[string]bool{"elevation": true, "hillshade": true, "contours": true}, true)
	if e != nil {
		t.Fatal(e)
	}
	j, _ := s.readMapJob(m.ID)
	for _, c := range j.Plan.Chunks {
		data, _ := json.Marshal(map[string]any{"type": "FeatureCollection", "acquisition": "complete", "requested_bounds": c.Bounds, "attribution": "Synthetic georeferenced acceptance fixture; not OSM acquisition", "features": []any{map[string]any{"type": "Feature", "properties": map[string]string{"highway": "path"}, "geometry": map[string]any{"type": "LineString", "coordinates": [][2]float64{{88.757, 27.372}, {88.767, 27.377}}}}}})
		e = s.writeChunk(context.Background(), m.ID, c.Key, "vector", data, chunkEvidence{Status: "complete", Bytes: len(data), Attempts: 1})
		if e != nil {
			t.Fatal(e)
		}
		w := int(math.Floor(c.Bounds[0]*3600)) - 1
		east := int(math.Ceil(c.Bounds[2]*3600)) + 1
		south := int(math.Floor(c.Bounds[1]*3600)) - 1
		n := int(math.Ceil(c.Bounds[3]*3600)) + 1
		g := terrainGrid{Bounds: [4]float64{float64(w) / 3600, float64(south) / 3600, float64(east) / 3600, float64(n) / 3600}, Columns: east - w + 1, Rows: n - south + 1, ArcSeconds: 1, Datum: "EGM96", Source: "Synthetic georeferenced hill/valley fixture — NOT real provider", Components: j.Components}
		for y := 0; y < g.Rows; y++ {
			for x := 0; x < g.Columns; x++ {
				lon := float64(w+x) / 3600
				lat := float64(n-y) / 3600
				value := math.Round(1000 + 500*math.Exp(-((lon-88.76)*(lon-88.76)+(lat-27.375)*(lat-27.375))/.000012))
				if lon > 88.764 && lat < 27.373 {
					g.Samples = append(g.Samples, nil)
				} else {
					g.Samples = append(g.Samples, &value)
				}
			}
		}
		b, e := encodeTerrain(g)
		if e != nil {
			t.Fatal(e)
		}
		e = s.writeChunk(context.Background(), m.ID, c.Key, "terrain", b, chunkEvidence{Status: "complete", Bytes: len(b), Attempts: 1})
		if e != nil {
			t.Fatal(e)
		}
	}
	t.Log(m.ID)
}

func TestRadiusCancelNoncontiguousRecoveryAndFleet(t *testing.T) {
	s, e := Open(filepath.Join(t.TempDir(), "command.sqlite"))
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	m, e := s.createMapJob(mapCircle{.1, .1, 12345}, map[string]bool{"elevation": true}, true)
	if e != nil {
		t.Fatal(e)
	}
	j, _ := s.readMapJob(m.ID)
	for _, i := range []int{0, len(j.Plan.Chunks) - 1} {
		if e = s.writeChunk(context.Background(), m.ID, j.Plan.Chunks[i].Key, "vector", []byte(validOfflineMap), chunkEvidence{Status: "complete", Bytes: len(validOfflineMap), Attempts: 1}); e != nil {
			t.Fatal(e)
		}
	}
	started := make(chan struct{})
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		close(started)
		<-r.Context().Done()
	}))
	defer provider.Close()
	p := newMapProvider()
	p.overpass = provider.URL
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- s.acquireMapChunk(ctx, p, m.ID, j.Plan.Chunks[1].Key, "vector", nil) }()
	<-started
	start := time.Now()
	for i := 1; i <= 21; i++ {
		m := message(t, 1, 0, 0, 0, 3)
		m.Device = fmt.Sprintf("00000000-0000-4000-8000-%012d", 1000+i)
		ingest(t, s, m)
		sos := emergency(t, 2, 1)
		sos.Device = m.Device
		ingest(t, s, sos)
	}
	state := snapshot(t, s)
	elapsed := time.Since(start)
	if len(state.Devices) != 21 || len(state.Alerts) != 21 {
		t.Fatal("fleet/SOS lost", len(state.Devices), len(state.Alerts))
	}
	if elapsed > 3*time.Second {
		t.Fatal("provider blocked durable GNSS/SOS", elapsed)
	}
	t.Logf("21 devices + 21 durable SOS while provider stalled: %s", elapsed)
	cancel()
	if e = <-done; !errors.Is(e, context.Canceled) {
		t.Fatal(e)
	}
	statuses, e := s.mapChunkStatuses(m.ID, j)
	if e != nil || statuses[0].Vector.Status != "complete" || statuses[len(statuses)-1].Vector.Status != "complete" || statuses[1].Vector.Status == "complete" {
		t.Fatal("noncontiguous coverage lost/invented", e)
	}
	p.nextArea = time.Time{}
	recovery := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(`{"elements":[]}`)) }))
	defer recovery.Close()
	p.overpass = recovery.URL
	if e = s.acquireMapChunk(context.Background(), p, m.ID, j.Plan.Chunks[1].Key, "vector", nil); e != nil {
		t.Fatal("resume failed", e)
	}
	// Transaction fault injection models a failed/full SQLite write, not a real full disk.
	_, e = s.db.Exec(`CREATE TRIGGER map_write_failure BEFORE UPDATE ON offline_map_chunks BEGIN SELECT RAISE(ABORT,'database or disk is full (injected)'); END`)
	if e != nil {
		t.Fatal(e)
	}
	if e = s.writeChunk(context.Background(), m.ID, j.Plan.Chunks[0].Key, "vector", []byte("corrupt replacement"), chunkEvidence{Status: "complete"}); e == nil {
		t.Fatal("write fault not injected")
	}
	var original []byte
	s.db.QueryRow(`SELECT vector FROM offline_map_chunks WHERE map_id=? AND chunk_key=?`, m.ID, j.Plan.Chunks[0].Key).Scan(&original)
	if string(original) != validOfflineMap {
		t.Fatal("failed save destroyed valid map")
	}
}

func TestRadiusVectorEnvelopeRoundsOutwards(t *testing.T) {
	bounds := [4]float64{77.012345678, 28.012345678, 77.066666667, 28.066666667}
	var query string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		query = r.Form.Get("data")
		w.Write([]byte(`{"elements":[]}`))
	}))
	defer server.Close()
	p := newMapProvider()
	p.overpass = server.URL
	data, err := p.areaBounds(context.Background(), bounds)
	if err != nil {
		t.Fatal(err)
	}
	match := regexp.MustCompile(`way\["highway"\]\(([^)]+)\)`).FindStringSubmatch(query)
	if len(match) != 2 {
		t.Fatal(query)
	}
	fields := strings.Split(match[1], ",")
	var sent [4]float64
	for i, v := range fields {
		sent[i], err = strconv.ParseFloat(v, 64)
		if err != nil {
			t.Fatal(err)
		}
	}
	if sent[0] > bounds[1] || sent[1] > bounds[0] || sent[2] < bounds[3] || sent[3] < bounds[2] {
		t.Fatal("provider rounding truncated requested coverage", bounds, sent)
	}
	var root struct {
		ProviderBounds [4]float64 `json:"provider_bounds"`
	}
	if json.Unmarshal(data, &root) != nil || root.ProviderBounds != [4]float64{sent[1], sent[0], sent[3], sent[2]} {
		t.Fatal("actual source envelope not retained", string(data))
	}
}

// Independent missing-data reference: one native tile is wholly void; its
// neighbour is the existing coordinate-indexed fixture. No surface may bridge it.
func TestRadiusMosaicRetainsValidNeighbourOfVoidCrop(t *testing.T) {
	raw := bytes.Repeat([]byte{0x80, 0}, 3601*3601)
	var compressed bytes.Buffer
	z := gzip.NewWriter(&compressed)
	if _, err := z.Write(raw); err != nil {
		t.Fatal(err)
	}
	z.Close()
	voidTile := compressed.Bytes()
	validTile := compressedDEM(t, 0, 1)
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "E001") {
			w.Write(validTile)
		} else {
			w.Write(voidTile)
		}
	}))
	defer provider.Close()
	p := newMapProvider()
	p.dem = provider.URL
	g, err := p.acquireDEM(context.Background(), [4]float64{.999, .033, 1.001, .034}, map[string]bool{"elevation": true, "contours": true, "hillshade": true})
	if err != nil {
		t.Fatal("void crop blocked valid neighbouring data", err)
	}
	missing, valid := 0, 0
	for y := 0; y < g.Rows; y++ {
		for x := 0; x < g.Columns; x++ {
			lon := g.Bounds[0] + float64(x)/3600
			v := g.Samples[y*g.Columns+x]
			if lon < 1-1e-10 {
				if v != nil {
					t.Fatal("void surface fabricated", lon, *v)
				}
				missing++
			} else {
				if v == nil {
					t.Fatal("valid neighbour lost", lon)
				}
				valid++
			}
		}
	}
	if missing == 0 || valid == 0 || len(g.Provenance.Tiles) != 2 {
		t.Fatal("partial source footprint not preserved", missing, valid)
	}
	s, err := Open(filepath.Join(t.TempDir(), "command.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	m, err := s.createMapJob(mapCircle{.033, .033, 373}, map[string]bool{"elevation": true}, true)
	if err != nil {
		t.Fatal(err)
	}
	j, _ := s.readMapJob(m.ID)
	err = s.acquireMapChunk(context.Background(), p, m.ID, j.Plan.Chunks[0].Key, "terrain", nil)
	if !errors.Is(err, errDEMNoData) {
		t.Fatal("all-void acquired source not distinguished", err)
	}
	statuses, err := s.mapChunkStatuses(m.ID, j)
	if err != nil || !strings.Contains(statuses[0].Terrain.Status, "source acquired") || statuses[0].Terrain.Metadata == nil || statuses[0].Terrain.VoidCells == 0 {
		t.Fatal("known source void evidence lost", err, statuses[0].Terrain)
	}
	var published int
	s.db.QueryRow(`SELECT count(*) FROM offline_map_chunks WHERE map_id=? AND terrain IS NOT NULL`, m.ID).Scan(&published)
	if published != 0 {
		t.Fatal("all-void DEM published as available")
	}
}

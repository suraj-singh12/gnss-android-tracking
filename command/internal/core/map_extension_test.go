package core

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	pb "github.com/qedus/osmpbf/OSMPBF"
	"google.golang.org/protobuf/proto"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func extensionStore(t *testing.T) *Store {
	t.Helper()
	s, e := Open(filepath.Join(t.TempDir(), "maps.sqlite"))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	return s
}
func fixturePBF(t *testing.T, broken bool) []byte {
	t.Helper()
	var out bytes.Buffer
	block := func(typ string, m proto.Message) {
		raw, e := proto.Marshal(m)
		if e != nil {
			t.Fatal(e)
		}
		b, e := proto.Marshal(&pb.Blob{Data: &pb.Blob_Raw{Raw: raw}})
		if e != nil {
			t.Fatal(e)
		}
		h, e := proto.Marshal(&pb.BlobHeader{Type: proto.String(typ), Datasize: proto.Int32(int32(len(b)))})
		if e != nil {
			t.Fatal(e)
		}
		binary.Write(&out, binary.BigEndian, uint32(len(h)))
		out.Write(h)
		out.Write(b)
	}
	block("OSMHeader", &pb.HeaderBlock{Bbox: &pb.HeaderBBox{Left: proto.Int64(0), Bottom: proto.Int64(0), Right: proto.Int64(200000000), Top: proto.Int64(200000000)}, RequiredFeatures: []string{"OsmSchema-V0.6", "DenseNodes"}, Writingprogram: proto.String("bounded test fixture")})
	nodes := []*pb.Node{{Id: proto.Int64(1), Lon: proto.Int64(900000), Lat: proto.Int64(900000)}, {Id: proto.Int64(2), Lon: proto.Int64(1100000), Lat: proto.Int64(1100000)}}
	// Sparse nodes and dense nodes are both exercised; tags refer to the string table.
	refs := []int64{1, 1}
	if broken {
		refs[1] = 999
	}
	block("OSMData", &pb.PrimitiveBlock{Stringtable: &pb.StringTable{S: []string{"", "highway", "path"}}, Primitivegroup: []*pb.PrimitiveGroup{{Nodes: nodes, Dense: &pb.DenseNodes{Id: []int64{3, 1}, Lon: []int64{950000, 10000}, Lat: []int64{950000, 10000}}}, {Ways: []*pb.Way{{Id: proto.Int64(9), Keys: []uint32{1}, Vals: []uint32{2}, Refs: refs}}}}})
	return out.Bytes()
}
func writePBF(t *testing.T, b []byte) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "test.osm.pbf")
	if e := os.WriteFile(p, b, 0600); e != nil {
		t.Fatal(e)
	}
	return p
}
func TestMapPBFIndexCropFullAndInvalidReferences(t *testing.T) {
	s := extensionStore(t)
	ctx := context.Background()
	src, e := s.indexPBF(ctx, writePBF(t, fixturePBF(t, false)), osmSource{Name: "fixture"}, nil)
	if e != nil {
		t.Fatal(e)
	}
	if src.Nodes != 4 || src.Ways != 1 {
		t.Fatal(src)
	}
	for _, bounds := range [][4]float64{{.095, .095, .105, .105}, {.15, .15, .16, .16}} {
		b, e := s.sourceVectors(ctx, src, bounds)
		if e != nil {
			t.Fatal(e)
		}
		var f struct {
			Features []any `json:"features"`
		}
		json.Unmarshal(b, &f)
		want := 1
		if bounds[0] > .11 {
			want = 0
		}
		if len(f.Features) != want {
			t.Fatal("incorrect viewport extraction", string(b))
		}
	}
	full, e := planExtent(src.Bounds)
	if e != nil || len(full.Chunks) != 9 || !full.FullExtent {
		t.Fatal(full, e)
	}
	if _, e = planExtent([4]float64{-10, -10, 10, 10}); e == nil {
		t.Fatal("unbounded full extent")
	}
	again, e := s.indexPBF(ctx, writePBF(t, fixturePBF(t, false)), osmSource{Name: "fixture"}, nil)
	if e != nil || again.ID != src.ID {
		t.Fatal("duplicate index", e)
	}
	if _, e = s.indexPBF(ctx, writePBF(t, fixturePBF(t, true)), osmSource{}, nil); e == nil {
		t.Fatal("missing way reference accepted")
	}
	sources, _ := s.osmSources()
	if len(sources) != 1 {
		t.Fatal("invalid source published")
	}
	for _, b := range [][]byte{[]byte("bad"), {0xff, 0xff, 0xff, 0xff}, fixturePBF(t, false)[:40]} {
		if _, e = s.indexPBF(ctx, writePBF(t, b), osmSource{}, nil); e == nil {
			t.Fatal("corrupt PBF accepted")
		}
	}
}
func TestMapTransferCumulativeUnknownRestartAndEscalation(t *testing.T) {
	s := extensionStore(t)
	m, e := s.createMapJob(mapCircle{.1, .1, 373}, nil, true)
	if e != nil {
		t.Fatal(e)
	}
	var unknown atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if unknown.Load() {
			w.(http.Flusher).Flush()
		} else {
			w.Header().Set("Content-Length", strconv.Itoa(4_000_000))
		}
		_, _ = w.Write(bytes.Repeat([]byte("x"), 4_000_000))
	}))
	defer server.Close()
	ctx := context.WithValue(context.Background(), mapBudgetKey{}, &mapBudget{s: s, id: m.ID})
	client := mapHTTPClient(ctx, server.Client())
	get := func() (int64, error) {
		r, e := client.Get(server.URL)
		if e != nil {
			return 0, e
		}
		defer r.Body.Close()
		return io.Copy(io.Discard, r.Body)
	}
	n, e := get()
	if e != nil || n != 4_000_000 {
		t.Fatal(n, e)
	}
	if _, e = get(); !errors.Is(e, errMapConsent) {
		t.Fatal("4+4 MB consent absent", e)
	}
	state, _ := s.transferState(m.ID)
	if state.Received != 4_000_000 || state.Required != 8_000_000 {
		t.Fatal(state)
	}
	if e = s.approveMapTransfer(m.ID, 7_000_000); e == nil {
		t.Fatal("underapproval accepted")
	}
	if e = s.approveMapTransfer(m.ID, 8_000_000); e != nil {
		t.Fatal(e)
	}
	if n, e = get(); e != nil || n != 4_000_000 {
		t.Fatal(n, e)
	}
	path := s.path
	s.Close()
	s, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	state, _ = s.transferState(m.ID)
	if state.Received != 8_000_000 {
		t.Fatal("restart reset job", state)
	}
	other, _ := s.createMapJob(mapCircle{.11, .11, 373}, nil, true)
	unknown.Store(true)
	ctx = context.WithValue(context.Background(), mapBudgetKey{}, &mapBudget{s: s, id: other.ID})
	client = mapHTTPClient(ctx, server.Client())
	n, e = get()
	if e != nil || n != 4_000_000 {
		t.Fatal(n, e)
	}
	n, e = get()
	if !errors.Is(e, errMapConsent) || n != 1_000_000 {
		t.Fatal("unknown response exceeded remaining allowance", n, e)
	}
	state, _ = s.transferState(other.ID)
	if state.Received != mapConsentBytes {
		t.Fatal(state)
	}
}
func TestMapAutomaticRegionalFallbackAndReuse(t *testing.T) {
	for _, code := range []int{429, 502, 503, 504} {
		t.Run(http.StatusText(code), func(t *testing.T) {
			s := extensionStore(t)
			var pbfCalls, overpassCalls atomic.Int32
			data := fixturePBF(t, false)
			var address string
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/overpass":
					overpassCalls.Add(1)
					w.Header().Set("Retry-After", "1")
					w.WriteHeader(code)
				case "/index":
					json.NewEncoder(w).Encode(map[string]any{"features": []any{map[string]any{"properties": map[string]any{"id": "local", "name": "fixture region", "urls": map[string]string{"pbf": address + "/region.osm.pbf"}}, "geometry": json.RawMessage(`{"type":"Polygon","coordinates":[[[0,0],[0.2,0],[0.2,0.2],[0,0.2],[0,0]]]}`)}}})
				case "/region.osm.pbf":
					pbfCalls.Add(1)
					w.Write(data)
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
			m, e := s.createMapJob(mapCircle{.1, .1, 373}, nil, true)
			if e != nil {
				t.Fatal(e)
			}
			j, _ := s.readMapJob(m.ID)
			if e = s.acquireMapChunk(context.Background(), p, m.ID, j.Plan.Chunks[0].Key, "vector", nil); e != nil {
				t.Fatal(e)
			}
			other, _ := s.createMapJob(mapCircle{.11, .11, 373}, nil, true)
			j, _ = s.readMapJob(other.ID)
			if e = s.acquireMapChunk(context.Background(), p, other.ID, j.Plan.Chunks[0].Key, "vector", nil); e != nil {
				t.Fatal(e)
			}
			if pbfCalls.Load() != 1 || overpassCalls.Load() != 1 {
				t.Fatal("cached source redownloaded", pbfCalls.Load(), overpassCalls.Load())
			}
		})
	}
}
func TestMapFootprintDoesNotInferCoverageFromBoundingBox(t *testing.T) {
	polygon := json.RawMessage(`{"type":"Polygon","coordinates":[[[0,0],[10,0],[10,10],[0,10],[0,0]],[[4,4],[6,4],[6,6],[4,6],[4,4]]]}`)
	if !footprintCovers(polygon, [4]float64{1, 1, 2, 2}) || footprintCovers(polygon, [4]float64{3, 3, 7, 7}) || footprintCovers(polygon, [4]float64{9, 9, 11, 11}) {
		t.Fatal("hole or boundary falsely covered")
	}
}
func TestPortableMapRoundtripPartialIntegrityAndAtomicRollback(t *testing.T) {
	s := extensionStore(t)
	ctx := context.Background()
	m, e := s.createMapJob(mapCircle{.1, .1, 373}, map[string]bool{"contours": true}, true)
	if e != nil {
		t.Fatal(e)
	}
	j, _ := s.readMapJob(m.ID)
	b := mustJSON(map[string]any{"type": "FeatureCollection", "features": []any{}, "requested_bounds": j.Plan.Chunks[0].Bounds, "acquisition": "complete"})
	ev := chunkEvidence{Status: "complete", Bytes: len(b)}
	if e = s.writeChunk(ctx, m.ID, j.Plan.Chunks[0].Key, "vector", b, ev); e != nil {
		t.Fatal(e)
	}
	f, e := os.CreateTemp(t.TempDir(), "*.zip")
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close()
	if e = s.exportMapPackage(ctx, m.ID, f); e != nil {
		t.Fatal(e)
	}
	stat, _ := f.Stat()
	other := extensionStore(t)
	restored, e := other.importMapPackage(ctx, f, stat.Size())
	if e != nil {
		t.Fatal(e)
	}
	restored2, e := other.importMapPackage(ctx, f, stat.Size())
	if e != nil || restored.ID != restored2.ID {
		t.Fatal("non-idempotent restore", e)
	}
	rows, e := other.listMaps()
	if e != nil || len(rows) != 1 || rows[0].Chunks.Vector != 1 || rows[0].Chunks.Terrain != 0 {
		t.Fatal(rows, e)
	}
	for _, name := range []string{"../escape", "/absolute", "chunks\\escape", "manifest.json"} {
		var out bytes.Buffer
		z := zip.NewWriter(&out)
		w, _ := z.Create(name)
		w.Write([]byte("corrupt"))
		if name == "manifest.json" {
			w, _ = z.Create(name)
			w.Write([]byte("duplicate"))
		}
		z.Close()
		bad, _ := os.CreateTemp(t.TempDir(), "bad.zip")
		bad.Write(out.Bytes())
		_, e = other.importMapPackage(ctx, bad, int64(out.Len()))
		bad.Close()
		if e == nil {
			t.Fatal("unsafe package accepted", name)
		}
	}
	// A valid archive with changed asset bytes must fail CRC/hash before publication.
	z, _ := zip.NewReader(f, stat.Size())
	var out bytes.Buffer
	zw := zip.NewWriter(&out)
	for _, entry := range z.File {
		r, _ := entry.Open()
		data, _ := io.ReadAll(r)
		r.Close()
		if strings.HasPrefix(entry.Name, "chunks/") {
			data = append(data, ' ')
		}
		w, _ := zw.Create(entry.Name)
		w.Write(data)
	}
	zw.Close()
	bad, _ := os.CreateTemp(t.TempDir(), "bad.zip")
	defer bad.Close()
	bad.Write(out.Bytes())
	if _, e = other.importMapPackage(ctx, bad, int64(out.Len())); e == nil {
		t.Fatal("checksum mismatch accepted")
	}
	rows, _ = other.listMaps()
	if len(rows) != 1 {
		t.Fatal("failed import published map")
	}
}

func TestMapFallbackEscalationTimeoutAndExhaustion(t *testing.T) {
	s := extensionStore(t)
	var download atomic.Int32
	var address string
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/overpass":
			io.Copy(io.Discard, r.Body)
			<-r.Context().Done()
		case "/index":
			json.NewEncoder(w).Encode(map[string]any{"features": []any{map[string]any{"properties": map[string]any{"name": "large covering region", "urls": map[string]string{"pbf": address + "/region.osm.pbf"}}, "geometry": json.RawMessage(`{"type":"Polygon","coordinates":[[[0,0],[0.2,0],[0.2,0.2],[0,0.2],[0,0]]]}`)}}})
		case "/region.osm.pbf":
			download.Add(1)
			w.Header().Set("Content-Length", "200000000")
		default:
			w.WriteHeader(503)
		}
	}))
	defer provider.Close()
	address = provider.URL
	m, _ := s.createMapJob(mapCircle{.1, .1, 373}, nil, true)
	j, _ := s.readMapJob(m.ID)
	p := newMapProvider()
	p.overpass = address + "/overpass"
	p.catalog = address + "/index"
	p.client = &http.Client{Timeout: 100 * time.Millisecond}
	err := s.acquireMapChunk(context.Background(), p, m.ID, j.Plan.Chunks[0].Key, "vector", nil)
	if !errors.Is(err, errMapConsent) {
		t.Fatal("large regional fallback did not stop for approval", err)
	}
	state, _ := s.transferState(m.ID)
	if state.Required < 200000000 || state.Received > 5000000 || download.Load() != 1 {
		t.Fatal(state, download.Load())
	}
	statuses, _ := s.mapChunkStatuses(m.ID, j)
	if statuses[0].Vector.Status == "complete" || len(statuses[0].Vector.SourceAttempts) != 2 {
		t.Fatal("false success or missing fallback custody", statuses)
	}
	// Explicit cancellation before retry reads no provider and preserves the manifest.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err = s.acquireMapChunk(ctx, p, m.ID, j.Plan.Chunks[0].Key, "vector", nil); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
func TestMapPackageWriteFailureRollsBackAllAssets(t *testing.T) {
	s := extensionStore(t)
	m, _ := s.saveMap("fixture", []byte(validOfflineMap))
	f, _ := os.CreateTemp(t.TempDir(), "*.zip")
	defer f.Close()
	if e := s.exportMapPackage(context.Background(), m.ID, f); e != nil {
		t.Fatal(e)
	}
	stat, _ := f.Stat()
	other := extensionStore(t)
	if _, e := other.db.Exec(`CREATE TRIGGER map_import_fault BEFORE INSERT ON offline_maps BEGIN SELECT RAISE(ABORT,'injected disk full'); END`); e != nil {
		t.Fatal(e)
	}
	if _, e := other.importMapPackage(context.Background(), f, stat.Size()); e == nil {
		t.Fatal("failed publication claimed success")
	}
	maps, _ := other.listMaps()
	if len(maps) != 0 {
		t.Fatal("failed import exposed map")
	}
}

func TestMapPBFBoundedIndexWith21PartyDurableHTTPPriority(t *testing.T) {
	s := extensionStore(t)
	s.Now = func() time.Time { return base.Add(time.Second) }
	file, e := os.CreateTemp(t.TempDir(), "large-*.pbf")
	if e != nil {
		t.Fatal(e)
	}
	defer file.Close()
	// A realistic OSM framing workload: 192k native nodes, 32 independent blocks.
	data := fixturePBF(t, false)
	typ, header, e := readPBFBlock(bytes.NewReader(data))
	if e != nil {
		t.Fatal(e)
	}
	block := func(typ string, raw []byte) {
		blob, _ := proto.Marshal(&pb.Blob{Data: &pb.Blob_Raw{Raw: raw}})
		head, _ := proto.Marshal(&pb.BlobHeader{Type: proto.String(typ), Datasize: proto.Int32(int32(len(blob)))})
		binary.Write(file, binary.BigEndian, uint32(len(head)))
		file.Write(head)
		file.Write(blob)
	}
	block(typ, header)
	for part := 0; part < 32; part++ {
		nodes := make([]*pb.Node, 6000)
		for i := range nodes {
			nodes[i] = &pb.Node{Id: proto.Int64(int64(part*6000 + i + 1)), Lat: proto.Int64(1000000 + int64(i)), Lon: proto.Int64(1000000 + int64(i))}
		}
		p := &pb.PrimitiveBlock{Stringtable: &pb.StringTable{S: []string{"", "highway", "path"}}, Primitivegroup: []*pb.PrimitiveGroup{{Nodes: nodes, Ways: []*pb.Way{{Id: proto.Int64(int64(part + 1)), Keys: []uint32{1}, Vals: []uint32{2}, Refs: []int64{int64(part*6000 + 1), 1}}}}}}
		raw, _ := proto.Marshal(p)
		block("OSMData", raw)
	}
	stat, _ := file.Stat()
	started := make(chan struct{})
	done := make(chan error, 1)
	var once sync.Once
	var peak atomic.Uint64
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	begin := time.Now()
	go func() {
		_, e := s.indexPBF(ctx, file.Name(), osmSource{Name: "192k-node resource fixture"}, func(p DEMProgress) { once.Do(func() { close(started) }) })
		done <- e
	}()
	<-started
	samplingDone := make(chan struct{})
	go func() {
		defer close(samplingDone)
		ticker := time.NewTicker(5 * time.Millisecond)
		defer ticker.Stop()
		for {
			var m runtime.MemStats
			runtime.ReadMemStats(&m)
			if m.HeapAlloc > peak.Load() {
				peak.Store(m.HeapAlloc)
			}
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
	server := httptest.NewServer(s.IngestHandler())
	defer server.Close()
	latencies := []time.Duration{}
	for i := 1; i <= 21; i++ {
		m := message(t, 1, 0, 0, 0, 3)
		m.Device = fmt.Sprintf("00000000-0000-4000-8000-%012d", 3000+i)
		sos := emergency(t, 2, 1)
		sos.Device = m.Device
		for _, msg := range []Message{m, sos} {
			start := time.Now()
			r, e := http.Post(server.URL+"/api/v1/messages", "application/json", bytes.NewReader(encode(t, msg)))
			if e != nil {
				t.Fatal(e)
			}
			io.Copy(io.Discard, r.Body)
			r.Body.Close()
			if r.StatusCode != 200 {
				t.Fatal("ingestion failed", r.StatusCode)
			}
			latencies = append(latencies, time.Since(start))
		}
	}
	if e = <-done; e != nil {
		t.Fatal(e)
	}
	cancel()
	<-samplingDone
	sort.Slice(latencies, func(i, j int) bool { return latencies[i] < latencies[j] })
	state := snapshot(t, s)
	if len(state.Devices) != 21 || len(state.Alerts) != 21 {
		t.Fatal("source processing lost live/SOS")
	}
	worst := latencies[len(latencies)-1]
	if worst > 3*time.Second {
		t.Fatal("PBF indexing starved durable ACK", worst)
	}
	t.Logf("PBF bytes=%d nodes=192000 elapsed=%s sampled_process_heap_peak=%d bytes; 42 durable HTTP LIVE/SOS ACK p50=%s p95=%s worst=%s (Linux fixture, not native/provider)", stat.Size(), time.Since(begin), peak.Load(), latencies[len(latencies)/2], latencies[(len(latencies)-1)*95/100], worst)
}

func TestMapProductSelectionPreservesFullExtentAndTransfer(t *testing.T) {
	s := extensionStore(t)
	plan, e := planExtent([4]float64{0, 0, .2, .2})
	if e != nil {
		t.Fatal(e)
	}
	j := mapJob{Type: "FeatureCollection", Features: []any{}, Plan: plan, Components: map[string]bool{}, SourceID: "immutable-source", Transfer: mapTransfer{Allowance: 8000000, Received: 4000000, Charged: 4000000}}
	m, e := s.publishJob("full-product-fixture", "Full source fixture", mustJSON(j))
	if e != nil {
		t.Fatal(e)
	}
	r := httptest.NewRequest("POST", "/local/maps/"+m.ID+"/products", strings.NewReader(`{"components":{"contours":true}}`))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.LocalHandler(http.NotFoundHandler()).ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	saved, e := s.readMapJob(m.ID)
	if e != nil || !saved.Components["contours"] || saved.SourceID != j.SourceID || saved.Transfer != j.Transfer || !saved.Plan.FullExtent {
		t.Fatal("product request replaced source/transfer/coverage", saved, e)
	}
}

func TestPortableMapDoesNotTransferOperatorNetworkConsent(t *testing.T) {
	s := extensionStore(t)
	var raw map[string]any
	json.Unmarshal([]byte(validOfflineMap), &raw)
	raw["transfer"] = mapTransfer{Allowance: 200000000, Received: 4000000, Charged: 4000000}
	raw["source_bytes"] = 4000000
	m, e := s.saveMap("Legacy source with previous consent", mustJSON(raw))
	if e != nil {
		t.Fatal(e)
	}
	f, _ := os.CreateTemp(t.TempDir(), "map-*.zip")
	defer f.Close()
	if e = s.exportMapPackage(context.Background(), m.ID, f); e != nil {
		t.Fatal(e)
	}
	stat, _ := f.Stat()
	other := extensionStore(t)
	copied, e := other.importMapPackage(context.Background(), f, stat.Size())
	if e != nil {
		t.Fatal(e)
	}
	state, e := other.transferState(copied.ID)
	if e != nil || state.Allowance != mapConsentBytes || state.Received != 0 || state.Charged != 0 {
		t.Fatal("portable import carried remote consent/accounting", state, e)
	}
	original, _ := s.transferState(m.ID)
	if original.Allowance != 200000000 || original.Received != 4000000 {
		t.Fatal("export reset original logical job", original)
	}
}

func TestRegionalRedirectIdentityAndTransferConsent(t *testing.T) {
	origin := "https://download.geofabrik.de/europe/andorra-latest.osm.pbf"
	for _, target := range []string{"andorra-261021.osm.pbf", "https://download-mirror.geofabrik.de/europe/andorra-latest.osm.pbf"} {
		if _, e := regionalRedirect(origin, target); e != nil {
			t.Fatal("publisher snapshot/mirror refused", e)
		}
	}
	for _, target := range []string{"http://download.geofabrik.de/europe/andorra-latest.osm.pbf", "https://attacker.invalid/europe/andorra-latest.osm.pbf", "https://download.geofabrik.de/europe/france-latest.osm.pbf", "https://download.geofabrik.de/other/andorra-latest.osm.pbf", "https://user:secret@download.geofabrik.de/europe/andorra-latest.osm.pbf", "https://download.geofabrik.de/europe/andorra-latest.osm.pbf?token=secret"} {
		if _, e := regionalRedirect(origin, target); e == nil {
			t.Fatal("unsafe redirect accepted", target)
		}
	}
	for _, large := range []bool{false, true} {
		t.Run(fmt.Sprint("large=", large), func(t *testing.T) {
			s := extensionStore(t)
			var address string
			var attempts atomic.Int32
			provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/overpass":
					w.WriteHeader(502)
				case "/index":
					json.NewEncoder(w).Encode(map[string]any{"features": []any{map[string]any{"properties": map[string]any{"name": "fixture region", "urls": map[string]string{"pbf": address + "/region-latest.osm.pbf"}}, "geometry": json.RawMessage(`{"type":"Polygon","coordinates":[[[0,0],[0.2,0],[0.2,0.2],[0,0.2],[0,0]]]}`)}}})
				case "/region-latest.osm.pbf":
					attempts.Add(1)
					w.Header().Set("Location", "/region-261021.osm.pbf")
					w.WriteHeader(307)
				case "/region-261021.osm.pbf":
					attempts.Add(1)
					if large {
						w.Header().Set("Content-Length", "200000000")
					} else {
						w.Write(fixturePBF(t, false))
					}
				default:
					w.WriteHeader(404)
				}
			}))
			defer provider.Close()
			address = provider.URL
			p := newMapProvider()
			p.overpass = address + "/overpass"
			p.catalog = address + "/index"
			m, _ := s.createMapJob(mapCircle{.1, .1, 373}, nil, true)
			j, _ := s.readMapJob(m.ID)
			e := s.acquireMapChunk(context.Background(), p, m.ID, j.Plan.Chunks[0].Key, "vector", nil)
			if attempts.Load() != 2 {
				t.Fatal("redirect attempts", attempts.Load(), e)
			}
			if large {
				if !errors.Is(e, errMapConsent) {
					t.Fatal("redirect bypassed large transfer gate", e)
				}
				state, _ := s.transferState(m.ID)
				if state.Required < 200000000 || state.Received > mapConsentBytes {
					t.Fatal(state)
				}
			} else if e != nil {
				t.Fatal(e)
			}
		})
	}
}

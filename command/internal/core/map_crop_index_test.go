package core

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	pb "github.com/qedus/osmpbf/OSMPBF"
	"google.golang.org/protobuf/proto"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestCompactCropIndexOrderingReferencesAndBudgets(t *testing.T) {
	n, err := newCropNodeIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer n.close()
	for _, id := range []int64{9, 1, 2, 65536, 16777217} {
		if err = n.add(id, float64(id), -float64(id)); err != nil {
			t.Fatal(err)
		}
	}

	for i := int64(0); i < 40; i++ {
		if err = n.add(i*65536+100, 1, 2); err != nil {
			t.Fatal(err)
		}
	}
	if len(n.handles) > 32 {
		t.Fatal("unbounded source file handles")
	}
	if err = n.finish(context.Background()); err != nil {
		t.Fatal(err)
	}
	for i := int64(0); i < 40; i++ {
		x, y, e := n.get(context.Background(), i*65536+100)
		if e != nil || x != 1 || y != 2 {
			t.Fatal("partition eviction lost a node", i, e)
		}
	}

	for _, id := range []int64{1, 9, 65536, 16777217} {
		x, y, e := n.get(context.Background(), id)
		if e != nil || x != float64(id) || y != -float64(id) {
			t.Fatal(id, x, y, e)
		}
	}
	if _, _, err = n.get(context.Background(), 777); err == nil {
		t.Fatal("missing reference accepted")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, err = n.get(ctx, 1); err == nil {
		t.Fatal("ignored cancellation")
	}
	// Admission is about actual compact bytes, not source node count or RAM.
	other, e := newCropNodeIndex(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	defer other.close()
	other.bytes = cropIndexBytes
	if e = other.add(1, 0, 0); e == nil {
		t.Fatal("disk byte budget ignored")
	}
	other.bytes = 0
	other.sizes[0] = cropShardBytes
	if e = other.add(1, 0, 0); e == nil {
		t.Fatal("partition memory budget ignored")
	}
}
func TestCompactCropIndexDuplicateAndCleanup(t *testing.T) {
	parent := t.TempDir()
	n, err := newCropNodeIndex(parent)
	if err != nil {
		t.Fatal(err)
	}
	if err = n.add(1, 0, 0); err != nil {
		t.Fatal(err)
	}
	if err = n.add(1, 1, 1); err != nil {
		t.Fatal(err)
	}
	if err = n.finish(context.Background()); err == nil {
		t.Fatal("duplicate node accepted")
	}
	n.close()
	entries, _ := os.ReadDir(parent)
	if len(entries) != 0 {
		t.Fatal("temporary index leaked")
	}
}
func TestPBFCompactCropPreservesCrossBoundaryReferences(t *testing.T) {
	s := extensionStore(t)
	path := writePBF(t, fixturePBF(t, false))
	// Both way endpoints (0.09,0.09 and 0.11,0.11) are OUTSIDE this crop.
	// Node-in-box-only extraction would silently drop this crossing road.
	crop := [4]float64{.099, .099, .101, .101}
	src, err := s.indexPBF(context.Background(), path, osmSource{Name: "compact crop"}, nil, crop)
	if err != nil {
		t.Fatal(err)
	}
	if src.Nodes != 4 || src.Ways != 1 || src.Bounds != crop || !src.Cropped {
		t.Fatal(src)
	}
	var nodes int
	if err = s.db.QueryRow(`SELECT count(*) FROM offline_osm_nodes WHERE source=?`, src.ID).Scan(&nodes); err != nil || nodes != 0 {
		t.Fatal("crop used whole-source SQLite node rows", nodes, err)
	}
	b, err := s.sourceVectors(context.Background(), src, crop)
	if err != nil {
		t.Fatal(err)
	}
	var raw struct {
		Features []struct {
			Geometry struct{ Coordinates [][2]float64 }
		}
	}
	if err = json.Unmarshal(b, &raw); err != nil || len(raw.Features) != 1 {
		t.Fatal(string(b), err)
	}
	coords := raw.Features[0].Geometry.Coordinates
	if len(coords) != 2 || math.Abs(coords[0][0]-.09) > 1e-12 || math.Abs(coords[0][1]-.09) > 1e-12 || math.Abs(coords[1][0]-.11) > 1e-12 || math.Abs(coords[1][1]-.11) > 1e-12 {
		t.Fatal(coords)
	}
	full, err := s.indexPBF(context.Background(), path, osmSource{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if src.ID == full.ID || src.SHA != full.SHA {
		t.Fatal("crop poisoned full source identity")
	}
	if _, err = s.indexPBF(context.Background(), writePBF(t, fixturePBF(t, true)), osmSource{}, nil, crop); err == nil || !strings.Contains(err.Error(), "reference") {
		t.Fatal("missing references accepted", err)
	}
	entries, _ := filepath.Glob(filepath.Join(filepath.Dir(path), "crop-nodes-*"))
	if len(entries) != 0 {
		t.Fatal("temporary index leaked", entries)
	}
}

// Opt-in resource acceptance, generated framing and nodes: NOT a public regional
// source. Exercises the historical >5-million-node failure with a tiny crop.
func TestLargeCompactCropResourceAcceptance(t *testing.T) {
	if os.Getenv("GNSS_LARGE_CROP") != "1" {
		t.Skip("explicit large crop resource run")
	}
	path := filepath.Join(t.TempDir(), "large.osm.pbf")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	block := func(typ string, msg proto.Message) {
		data, e := proto.Marshal(msg)
		if e != nil {
			t.Fatal(e)
		}
		b, e := proto.Marshal(&pb.Blob{Data: &pb.Blob_Raw{Raw: data}})
		if e != nil {
			t.Fatal(e)
		}
		h, e := proto.Marshal(&pb.BlobHeader{Type: proto.String(typ), Datasize: proto.Int32(int32(len(b)))})
		if e != nil {
			t.Fatal(e)
		}
		if e = binary.Write(f, binary.BigEndian, uint32(len(h))); e != nil {
			t.Fatal(e)
		}
		if _, e = f.Write(h); e != nil {
			t.Fatal(e)
		}
		if _, e = f.Write(b); e != nil {
			t.Fatal(e)
		}
	}
	block("OSMHeader", &pb.HeaderBlock{Bbox: &pb.HeaderBBox{Left: proto.Int64(0), Bottom: proto.Int64(0), Right: proto.Int64(200000000), Top: proto.Int64(200000000)}, RequiredFeatures: []string{"OsmSchema-V0.6", "DenseNodes"}})
	const count = 5_100_000
	for start := int64(1); start <= count; {
		size := min(16000, int(count-start+1))
		ids := make([]int64, size)
		xs := make([]int64, size)
		ys := make([]int64, size)
		ids[0] = start
		xs[0] = 900000
		ys[0] = 900000
		for i := 1; i < size; i++ {
			ids[i] = 1
		}
		block("OSMData", &pb.PrimitiveBlock{Stringtable: &pb.StringTable{S: []string{""}}, Primitivegroup: []*pb.PrimitiveGroup{{Dense: &pb.DenseNodes{Id: ids, Lon: xs, Lat: ys}}}})
		start += int64(size)
	}
	block("OSMData", &pb.PrimitiveBlock{Stringtable: &pb.StringTable{S: []string{"", "highway", "path"}}, Primitivegroup: []*pb.PrimitiveGroup{{Ways: []*pb.Way{{Id: proto.Int64(1), Keys: []uint32{1}, Vals: []uint32{2}, Refs: []int64{1, count - 1}}}}}})
	if err = f.Close(); err != nil {
		t.Fatal(err)
	}
	s := extensionStore(t)
	started := time.Now()
	src, err := s.indexPBF(context.Background(), path, osmSource{Name: "Generated >5M node acceptance — NOT real regional provider"}, nil, [4]float64{.08, .08, .10, .10})
	if err != nil {
		t.Fatal(err)
	}
	if src.Nodes != count || src.Ways != 1 {
		t.Fatal(src)
	}
	var indexed int
	if err = s.db.QueryRow(`SELECT count(*) FROM offline_osm_nodes`).Scan(&indexed); err != nil || indexed != 0 {
		t.Fatal(indexed, err)
	}
	t.Logf("GENERATED source bytes=%d nodes=%d selected ways=%d compact node bytes=%d elapsed=%s", src.Bytes, src.Nodes, src.Ways, int64(src.Nodes)*cropRecordBytes, time.Since(started))
}

func TestOverpassLocalCooldownDoesNotSelectRegionalFallback(t *testing.T) {
	s := extensionStore(t)
	m, err := s.createMapJob(mapCircle{Lat: .1, Lon: .1, Radius: 373}, nil, true)
	if err != nil {
		t.Fatal(err)
	}
	j, err := s.readMapJob(m.ID)
	if err != nil {
		t.Fatal(err)
	}
	var contacts atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { contacts.Add(1); w.WriteHeader(502) }))
	defer server.Close()
	p := newMapProvider()
	p.overpass = server.URL + "/overpass"
	p.catalog = server.URL + "/catalog"
	p.nextArea = time.Now().Add(time.Minute)
	if _, err = s.acquireVector(context.Background(), p, m.ID, j.Plan.Chunks[0], nil); !errors.Is(err, errMapCooldown) {
		t.Fatal(err)
	}
	if contacts.Load() != 0 {
		t.Fatal("local cooldown triggered public fallback", contacts.Load())
	}
}

func TestCropWorkRecoveryRetainsVerifiedAssetsAndActiveWork(t *testing.T) {
	dir := t.TempDir()
	old := time.Now().Add(-12 * time.Minute)
	for _, name := range []string{"regional-aborted.tmp", "gnss-source-aborted.osm.pbf", "crop-nodes-aborted", "N27E077.hgt", "regions.json", "gnss-source-active.osm.pbf"} {
		path := filepath.Join(dir, name)
		if name == "crop-nodes-aborted" {
			if err := os.Mkdir(path, 0700); err != nil {
				t.Fatal(err)
			}
		} else if err := os.WriteFile(path, []byte("retained test bytes"), 0600); err != nil {
			t.Fatal(err)
		}
		if name != "gnss-source-active.osm.pbf" {
			if err := os.Chtimes(path, old, old); err != nil {
				t.Fatal(err)
			}
		}
	}
	cleanupMapWork(dir)
	for _, name := range []string{"regional-aborted.tmp", "gnss-source-aborted.osm.pbf", "crop-nodes-aborted"} {
		if _, err := os.Stat(filepath.Join(dir, name)); !os.IsNotExist(err) {
			t.Fatal("aborted work not removed", name, err)
		}
	}
	for _, name := range []string{"N27E077.hgt", "regions.json", "gnss-source-active.osm.pbf"} {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			t.Fatal("verified/active work removed", name, err)
		}
	}
}

func TestCompactCropPagedLookupEviction(t *testing.T) {
	n, err := newCropNodeIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer n.close()
	for id := int64(1); id <= 70000; id++ {
		if err = n.add(id, float64(id)/1e6, -float64(id)/1e6); err != nil {
			t.Fatal(err)
		}
	}
	if err = n.finish(context.Background()); err != nil {
		t.Fatal(err)
	}
	for _, reverse := range []bool{false, true} {
		for i := int64(0); i < 547; i++ {
			id := i*128 + 1
			if reverse {
				id = 70000 - i*128
			}
			if id < 1 || id > 70000 {
				continue
			}
			x, y, e := n.get(context.Background(), id)
			if e != nil || x != float64(id)/1e6 || y != -float64(id)/1e6 {
				t.Fatal(id, x, y, e)
			}
			if len(n.cache) > cropLookupPages || len(n.handles) > 32 {
				t.Fatal("unbounded lookup resources")
			}
		}
	}
}

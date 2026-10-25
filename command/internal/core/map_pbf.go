package core

// Bounded PBF framing uses the published OSM protobuf schema, not an unbounded
// whole-region decoder. The source index is disposable, in the existing SQLite
// file. Operational maps still own validated GeoJSON chunks, never source rows.
import (
	"bytes"
	"compress/zlib"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	pb "github.com/qedus/osmpbf/OSMPBF"
	"google.golang.org/protobuf/proto"
	"io"
	"math"
	"os"
	"path/filepath"
	"runtime"
	"time"
)

const pbfFileLimit int64 = 1_000_000_000
const pbfIndexLimit = 512 * 1024 * 1024
const pbfNodeLimit = 5_000_000

type osmSource struct {
	ID             string          `json:"id"`
	Name           string          `json:"name"`
	URL            string          `json:"url,omitempty"`
	SHA            string          `json:"sha256"`
	Revision       string          `json:"revision"`
	Bounds         [4]float64      `json:"bounds"`
	Footprint      json.RawMessage `json:"footprint,omitempty"`
	Bytes          int64           `json:"bytes"`
	Nodes          int             `json:"nodes"`
	Ways           int             `json:"ways"`
	DegenerateWays int             `json:"non_renderable_ways,omitempty"`
	Cropped        bool            `json:"selected_area,omitempty"`
	Note           string          `json:"scope"`
}

func sourceSchema(s *Store) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, e := s.db.Exec(`CREATE TABLE IF NOT EXISTS offline_osm_sources(id TEXT PRIMARY KEY,data BLOB NOT NULL);
 CREATE TABLE IF NOT EXISTS offline_osm_nodes(source TEXT NOT NULL,id INTEGER NOT NULL,lon REAL NOT NULL,lat REAL NOT NULL,PRIMARY KEY(source,id));
 CREATE TABLE IF NOT EXISTS offline_osm_ways(source TEXT NOT NULL,id INTEGER NOT NULL,w REAL,s REAL,e REAL,n REAL,data BLOB NOT NULL,PRIMARY KEY(source,id));
 CREATE INDEX IF NOT EXISTS offline_osm_way_area ON offline_osm_ways(source,w,e,s,n);
 DELETE FROM offline_osm_nodes WHERE source NOT IN (SELECT id FROM offline_osm_sources);
 DELETE FROM offline_osm_ways WHERE source NOT IN (SELECT id FROM offline_osm_sources);`)
	return e
}
func (s *Store) osmSources() ([]osmSource, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, e := s.db.Query(`SELECT data FROM offline_osm_sources`)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []osmSource{}
	for rows.Next() {
		var b []byte
		var src osmSource
		if e = rows.Scan(&b); e != nil {
			return nil, e
		}
		if e = json.Unmarshal(b, &src); e != nil {
			return nil, e
		}
		out = append(out, src)
	}
	return out, rows.Err()
}
func readPBFBlock(r io.Reader) (string, []byte, error) {
	var n uint32
	if e := binary.Read(r, binary.BigEndian, &n); e != nil {
		return "", nil, e
	}
	if n == 0 || n > 65536 {
		return "", nil, errors.New("invalid PBF header length")
	}
	h := make([]byte, n)
	if _, e := io.ReadFull(r, h); e != nil {
		return "", nil, e
	}
	var head pb.BlobHeader
	if e := proto.Unmarshal(h, &head); e != nil {
		return "", nil, e
	}
	if head.GetDatasize() <= 0 || head.GetDatasize() > 32*1024*1024 {
		return "", nil, errors.New("PBF blob exceeds 32 MiB")
	}
	b := make([]byte, head.GetDatasize())
	if _, e := io.ReadFull(r, b); e != nil {
		return "", nil, e
	}
	var blob pb.Blob
	if e := proto.Unmarshal(b, &blob); e != nil {
		return "", nil, e
	}
	const rawLimit = 16 * 1024 * 1024
	if blob.GetRawSize() < 0 || blob.GetRawSize() > rawLimit || len(blob.GetRaw()) > rawLimit {
		return "", nil, errors.New("PBF expanded block exceeds 16 MiB")
	}
	if blob.GetRaw() != nil && blob.GetZlibData() != nil {
		return "", nil, errors.New("ambiguous PBF encoding")
	}
	data := blob.GetRaw()
	if blob.GetZlibData() != nil {
		z, e := zlib.NewReader(bytes.NewReader(blob.GetZlibData()))
		if e != nil {
			return "", nil, e
		}
		data, e = io.ReadAll(io.LimitReader(z, rawLimit+1))
		z.Close()
		if e != nil {
			return "", nil, e
		}
		if len(data) > rawLimit || len(data) != int(blob.GetRawSize()) {
			return "", nil, errors.New("invalid expanded PBF size")
		}
	}
	if data == nil {
		return "", nil, errors.New("unsupported PBF compression")
	}
	return head.GetType(), data, nil
}
func pbfHeader(path string) (osmSource, error) {
	f, e := os.Open(path)
	if e != nil {
		return osmSource{}, e
	}
	defer f.Close()
	typ, b, e := readPBFBlock(f)
	if e != nil {
		return osmSource{}, e
	}
	if typ != "OSMHeader" {
		return osmSource{}, errors.New("PBF requires OSMHeader")
	}
	var h pb.HeaderBlock
	if e = proto.Unmarshal(b, &h); e != nil {
		return osmSource{}, e
	}
	for _, v := range h.RequiredFeatures {
		if v != "OsmSchema-V0.6" && v != "DenseNodes" {
			return osmSource{}, fmt.Errorf("unsupported required PBF feature %q", v)
		}
	}
	if h.Bbox == nil {
		return osmSource{}, errors.New("PBF must declare geographic bounds")
	}
	bb := h.Bbox
	bnd := [4]float64{float64(bb.GetLeft()) * 1e-9, float64(bb.GetBottom()) * 1e-9, float64(bb.GetRight()) * 1e-9, float64(bb.GetTop()) * 1e-9}
	if bnd[0] < -180 || bnd[2] > 180 || bnd[1] < -mapMercatorLimit || bnd[3] > mapMercatorLimit || bnd[0] >= bnd[2] || bnd[1] >= bnd[3] {
		return osmSource{}, errors.New("PBF extent exceeds supported non-wrapping Web Mercator coverage")
	}
	rev := fmt.Sprintf("replication %d / %s", h.GetOsmosisReplicationTimestamp(), h.GetWritingprogram())
	return osmSource{Bounds: bnd, Revision: rev, Note: "OSM tagged ways only (same feature scope as Overpass); relations, routing and source completeness beyond publisher-declared coverage are not asserted"}, nil
}
func scanPBF(ctx context.Context, path string, visit func(*pb.PrimitiveBlock) error) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	_, _, e = readPBFBlock(f)
	if e != nil {
		return e
	}
	blocks := 0
	for {
		if e = ctx.Err(); e != nil {
			return e
		}
		typ, b, e := readPBFBlock(f)
		if e == io.EOF {
			if blocks == 0 {
				return errors.New("PBF contains no OSMData blocks")
			}
			return nil
		}
		if e != nil {
			return e
		}
		if typ != "OSMData" {
			return errors.New("unexpected PBF block type")
		}
		blocks++
		var p pb.PrimitiveBlock
		if e = proto.Unmarshal(b, &p); e != nil {
			return e
		}
		if p.GetGranularity() <= 0 || p.Stringtable == nil {
			return errors.New("invalid PBF primitive metadata")
		}
		if e = visit(&p); e != nil {
			return e
		}
	}
}
func (s *Store) indexPBF(ctx context.Context, path string, supplied osmSource, emit demObserver, cropBounds ...[4]float64) (src osmSource, err error) {
	stat, err := os.Stat(path)
	if err != nil {
		return src, err
	}
	if stat.Size() > pbfFileLimit {
		return src, errors.New("PBF exceeds 1 GB source limit")
	}
	src, err = pbfHeader(path)
	if err != nil {
		return src, err
	}
	src.Name = supplied.Name
	src.URL = supplied.URL
	src.Footprint = supplied.Footprint
	src.Bytes = stat.Size()
	f, err := os.Open(path)
	if err != nil {
		return src, err
	}
	h := sha256.New()
	_, err = io.Copy(h, &demContextReader{Reader: f, ctx: ctx})
	f.Close()
	if err != nil {
		return src, err
	}
	src.SHA = hex.EncodeToString(h.Sum(nil))
	src.ID = "pbf-" + src.SHA
	var crop *[4]float64
	if len(cropBounds) > 0 {
		bounds := cropBounds[0]
		if !sourceCovers(src, bounds) {
			return src, errors.New("crop exceeds declared source bounds")
		}
		crop = &bounds
		src.Bounds = bounds
		src.Cropped = true
		src.Note += "; selected-area extraction; retained ways intersect the declared crop, with original full references"
		b, _ := json.Marshal(bounds)
		src.ID += "-crop-" + shaMap(b)[:16]
	}
	sources, err := s.osmSources()
	if err != nil {
		return src, err
	}
	for _, previous := range sources {
		if previous.ID == src.ID || (previous.SHA == src.SHA && sourceCovers(previous, src.Bounds)) {
			return previous, nil
		}
	}
	// Total source/index resource admission is conservative; no eviction of saved maps.
	if len(sources) >= 8 {
		return src, errors.New("source cache full (8 datasets); existing maps retained")
	}
	var cached int64
	priorNodes := 0
	for _, v := range sources {
		cached += v.Bytes
		if !v.Cropped {
			priorNodes += v.Nodes
		}
	}
	if cached+src.Bytes > pbfFileLimit {
		return src, errors.New("source cache exceeds 1 GB")
	}
	defer func() {
		if err != nil {
			s.mu.Lock()
			_, _ = s.db.Exec(`DELETE FROM offline_osm_nodes WHERE source=?`, src.ID)
			_, _ = s.db.Exec(`DELETE FROM offline_osm_ways WHERE source=?`, src.ID)
			s.mu.Unlock()
		}
	}()
	// Interrupted unpromoted indexes are safely rebuilt. No source is visible until verified.
	s.mu.Lock()
	_, err = s.db.Exec(`DELETE FROM offline_osm_nodes WHERE source=?;`, src.ID)
	if err == nil {
		_, err = s.db.Exec(`DELETE FROM offline_osm_ways WHERE source=?`, src.ID)
	}
	s.mu.Unlock()
	if err != nil {
		return src, err
	}
	emitDEM(emit, DEMProgress{Stage: "indexing PBF nodes on disk"})
	var compact *cropNodeIndex
	if crop != nil {
		compact, err = newCropNodeIndex(filepath.Dir(path))
		if err != nil {
			return src, err
		}
		defer compact.close()
		emitDEM(emit, DEMProgress{Stage: "indexing source nodes in bounded compact disk partitions"})
		err = scanPBF(ctx, path, func(p *pb.PrimitiveBlock) error {
			return visitPBFNodes(ctx, p, func(id, lon, lat int64) error {
				x := 1e-9 * (float64(p.GetLonOffset()) + float64(p.GetGranularity())*float64(lon))
				y := 1e-9 * (float64(p.GetLatOffset()) + float64(p.GetGranularity())*float64(lat))
				if id <= 0 || math.Abs(x) > 180 || math.Abs(y) > 90 {
					return errors.New("invalid PBF node")
				}
				src.Nodes++
				return compact.add(id, x, y)
			})
		})
		if err == nil {
			err = compact.finish(ctx)
		}
	} else {
		err = scanPBF(ctx, path, func(p *pb.PrimitiveBlock) error {
			s.mu.Lock()
			defer s.mu.Unlock()
			tx, e := s.db.BeginTx(ctx, nil)
			if e != nil {
				return e
			}
			defer func() {
				if tx != nil {
					_ = tx.Rollback()
				}
			}()
			stmt, e := tx.Prepare(`INSERT INTO offline_osm_nodes(source,id,lon,lat) VALUES(?,?,?,?)`)
			if e != nil {
				return e
			}
			defer func() {
				if stmt != nil {
					_ = stmt.Close()
				}
			}()
			add := func(id, lon, lat int64) error {
				src.Nodes++
				if src.Nodes+priorNodes > pbfNodeLimit {
					return errors.New("PBF exceeds 5 million node index budget")
				}
				x := 1e-9 * (float64(p.GetLonOffset()) + float64(p.GetGranularity())*float64(lon))
				y := 1e-9 * (float64(p.GetLatOffset()) + float64(p.GetGranularity())*float64(lat))
				if math.Abs(x) > 180 || math.Abs(y) > 90 || id <= 0 {
					return errors.New("invalid PBF node")
				}
				_, e := stmt.ExecContext(ctx, src.ID, id, x, y)
				if e == nil && src.Nodes%256 == 0 {
					stmt.Close()
					if e = tx.Commit(); e != nil {
						return e
					}
					s.mu.Unlock()
					runtime.Gosched()
					s.mu.Lock()
					tx, e = s.db.BeginTx(ctx, nil)
					if e != nil {
						return e
					}
					stmt, e = tx.Prepare(`INSERT INTO offline_osm_nodes(source,id,lon,lat) VALUES(?,?,?,?)`)
				}
				return e
			}
			if e = visitPBFNodes(ctx, p, add); e != nil {
				return e
			}
			return tx.Commit()
		})
	}
	if err != nil {
		return src, err
	}
	emitDEM(emit, DEMProgress{Stage: "extracting tagged PBF ways; bounded source index"})
	var stored int64
	readDB := s.viewDB
	if readDB == nil {
		readDB = s.db
	}
	if err = readDB.QueryRow(`SELECT coalesce(sum(length(data)),0) FROM offline_osm_ways`).Scan(&stored); err != nil {
		return src, err
	}
	err = scanPBF(ctx, path, func(p *pb.PrimitiveBlock) error {
		var e error
		db := s.viewDB
		if db == nil {
			db = s.db
		}
		for _, g := range p.Primitivegroup {
			for _, w := range g.Ways {
				if len(w.Keys) != len(w.Vals) {
					return errors.New("PBF way tags mismatch")
				}
				tags := map[string]string{}
				for i, k := range w.Keys {
					v := w.Vals[i]
					if int(k) >= len(p.Stringtable.S) || int(v) >= len(p.Stringtable.S) {
						return errors.New("PBF string reference out of bounds")
					}
					tags[string(p.Stringtable.S[k])] = string(p.Stringtable.S[v])
				}
				if tags["highway"] == "" && tags["building"] == "" && tags["waterway"] == "" && tags["natural"] == "" && tags["landuse"] == "" {
					continue
				}
				if len(w.Refs) > 100000 {
					return fmt.Errorf("PBF way coordinate budget exceeded (%d refs)", len(w.Refs))
				}
				coords := [][2]float64{}
				bnd := [4]float64{180, 90, -180, -90}
				var id int64
				for _, d := range w.Refs {
					var valid bool
					id, valid = mapDelta(id, d)
					if !valid {
						return errors.New("PBF way reference delta overflow")
					}
					var x, y float64
					if compact != nil {
						x, y, e = compact.get(ctx, id)
					} else {
						e = db.QueryRowContext(ctx, `SELECT lon,lat FROM offline_osm_nodes WHERE source=? AND id=?`, src.ID, id).Scan(&x, &y)
					}
					if e != nil {
						return fmt.Errorf("incomplete PBF way node reference: %w", e)
					}
					if math.Abs(y) > mapMercatorLimit {
						return errors.New("way outside renderer latitude support")
					}
					if len(coords) > 0 && math.Abs(coords[len(coords)-1][0]-x) > 180 {
						return errors.New("PBF way crosses unsupported dateline geometry; use split source")
					}
					coords = append(coords, [2]float64{x, y})
					bnd[0] = min(bnd[0], x)
					bnd[1] = min(bnd[1], y)
					bnd[2] = max(bnd[2], x)
					bnd[3] = max(bnd[3], y)
				}
				// Validate present references even when a way cannot form a line.
				if len(coords) < 2 {
					src.DegenerateWays++
					continue
				}
				if crop != nil && (bnd[0] > crop[2] || bnd[2] < crop[0] || bnd[1] > crop[3] || bnd[3] < crop[1]) {
					continue
				}
				feature := osmWayFeature(w.GetId(), tags, coords)
				b, e := json.Marshal(feature)
				if e != nil {
					return e
				}
				stored += int64(len(b))
				src.Ways++
				if stored > pbfIndexLimit || src.Ways > 1_000_000 {
					return errors.New("PBF exceeds 512 MiB / 1 million supported-way index")
				}
				s.mu.Lock()
				_, e = s.db.ExecContext(ctx, `INSERT INTO offline_osm_ways(source,id,w,s,e,n,data) VALUES(?,?,?,?,?,?,?)`, src.ID, w.GetId(), bnd[0], bnd[1], bnd[2], bnd[3], b)
				s.mu.Unlock()
				if e != nil {
					return e
				}
			}
		}
		return nil
	})
	if err != nil {
		return src, err
	}
	if src.DegenerateWays > 0 {
		src.Note += fmt.Sprintf("; %d tagged ways have fewer than two nodes and cannot form renderable lines/polygons", src.DegenerateWays)
	}
	b, _ := json.Marshal(src)
	s.mu.Lock()
	_, err = s.db.Exec(`INSERT INTO offline_osm_sources(id,data) VALUES(?,?)`, src.ID, b)
	s.mu.Unlock()
	return src, err
}
func osmWayFeature(id int64, tags map[string]string, coords [][2]float64) any {
	typ := "LineString"
	var geometry any = coords
	if len(coords) >= 4 && coords[0] == coords[len(coords)-1] && tags["area"] != "no" && (tags["building"] != "" || tags["landuse"] != "" || tags["natural"] != "") {
		typ = "Polygon"
		geometry = [][][2]float64{coords}
	}
	return map[string]any{"type": "Feature", "id": fmt.Sprintf("way/%d", id), "properties": tags, "geometry": map[string]any{"type": typ, "coordinates": geometry}}
}
func (s *Store) sourceVectors(ctx context.Context, src osmSource, bounds [4]float64) ([]byte, error) {
	db := s.viewDB
	if db == nil {
		db = s.db
	}
	rows, e := db.QueryContext(ctx, `SELECT data FROM offline_osm_ways WHERE source=? AND w<=? AND e>=? AND s<=? AND n>=? LIMIT 10001`, src.ID, bounds[2], bounds[0], bounds[3], bounds[1])
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	features := []json.RawMessage{}
	total := 0
	for rows.Next() {
		var b []byte
		if e = rows.Scan(&b); e != nil {
			return nil, e
		}
		total += len(b)
		if len(features) >= 10000 || total > mapLimit-65536 {
			return nil, errors.New("viewport chunk feature budget exceeded; source retained")
		}
		features = append(features, b)
	}
	if e = rows.Err(); e != nil {
		return nil, e
	}
	provenance := src
	provenance.Footprint = nil // publisher polygon validated before extraction; avoid repeating a regional polygon in every chunk
	b, e := json.Marshal(map[string]any{"type": "FeatureCollection", "features": features, "requested_bounds": bounds, "acquisition": "complete", "source": provenance, "provider": src.URL, "attribution": "© OpenStreetMap contributors · ODbL; regional extract distributor: Geofabrik / source publisher", "prepared_at": time.Now().UTC().Format(wireTime)})
	if e == nil {
		e = validateMap(b)
	}
	return b, e
}

func mapDelta(a, b int64) (int64, bool) {
	if b > 0 && a > math.MaxInt64-b || b < 0 && a < math.MinInt64-b {
		return 0, false
	}
	return a + b, true
}

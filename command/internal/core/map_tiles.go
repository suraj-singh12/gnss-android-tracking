package core

// Format 2 enhanced maps: typed immutable tile products inside the existing map
// library. One authoritative library, no competing storage authority. Tiles live
// in the shared database beside existing map assets and share its capacity budget.
import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Enhanced product types admitted in Format 2. The numeric ground-elevation
// product reuses the existing offline_terrain table and GTERR001 validation.
const (
	productVector    = "vector"    // MVT gzip, EPSG:3857 tile pyramid
	productDEM       = "dem"       // Terrarium RGB PNG pyramid (display hillshade)
	productLandcover = "landcover" // RGB PNG pyramid
	productGterr     = "terrain-gterr"
)

var tileProducts = map[string]bool{productVector: true, productDEM: true, productLandcover: true}

// enhancedMapDoc is the immutable descriptor stored in offline_maps.data for a
// Format 2 map. It is not GeoJSON and never enters the legacy renderer.
type enhancedMapDoc struct {
	Type        string                            `json:"type"` // always "GNSS-enhanced-map"
	Version     int                               `json:"version"`
	Revision    string                            `json:"revision"`
	Style       string                            `json:"style"`
	Requested   json.RawMessage                   `json:"requested,omitempty"`
	Products    map[string]enhancedProduct        `json:"products"`
	Missing     []string                          `json:"missing,omitempty"`
	Provenance  map[string]string                 `json:"provenance,omitempty"`
	Attribution string                            `json:"attribution"`
}

type enhancedProduct struct {
	Encoding string    `json:"encoding"` // mvt-gzip | png
	CRS      string    `json:"crs"`      // EPSG:3857 tile matrix
	Minzoom  int       `json:"minzoom"`
	Maxzoom  int       `json:"maxzoom"`
	Bounds   [4]float64 `json:"bounds"` // actual coverage w,s,e,n
	Tiles    int       `json:"tiles"`
	Bytes    int       `json:"bytes"`
	SHA      string    `json:"sha256"` // canonical tile-set hash
}

func enhancedDoc(b []byte) (*enhancedMapDoc, error) {
	var d enhancedMapDoc
	if e := json.Unmarshal(b, &d); e != nil {
		return nil, e
	}
	if d.Type != "GNSS-enhanced-map" || d.Version != 2 {
		return nil, errors.New("not an enhanced map descriptor")
	}
	return &d, nil
}

func validBounds(b [4]float64) bool {
	for _, v := range b {
		if math.IsNaN(v) || math.IsInf(v, 0) {
			return false
		}
	}
	return b[0] >= -180 && b[2] <= 180 && b[0] < b[2] && b[1] >= -mapMercatorLimit && b[3] <= mapMercatorLimit && b[1] < b[3]
}

func validEnhancedProduct(name string, p enhancedProduct) error {
	if !tileProducts[name] {
		return errors.New("unsupported tile product: " + name)
	}
	wantEncoding := "png"
	if name == productVector {
		wantEncoding = "mvt-gzip"
	}
	if p.Encoding != wantEncoding || p.CRS != "EPSG:3857" {
		return errors.New("unsupported product encoding or CRS")
	}
	if p.Minzoom < 0 || p.Maxzoom > 22 || p.Minzoom > p.Maxzoom {
		return errors.New("invalid product zoom range")
	}
	if !validBounds(p.Bounds) || p.Tiles < 1 || p.Bytes < 1 || len(p.SHA) != 64 {
		return errors.New("invalid product coverage or tile accounting")
	}
	return nil
}

// tileSetHash is the canonical content identity of one product's tiles:
// sha256 over sorted (z,x,y,sha256(tile)) rows. Order-independent, insert-order
// independent, verified identically at import and export.
func tileSetHash(rows [][5]any) string {
	type entry struct {
		z, x, y int
		sha     string
	}
	es := make([]entry, 0, len(rows))
	for _, r := range rows {
		es = append(es, entry{r[0].(int), r[1].(int), r[2].(int), r[3].(string)})
	}
	sort.Slice(es, func(i, j int) bool {
		if es[i].z != es[j].z {
			return es[i].z < es[j].z
		}
		if es[i].x != es[j].x {
			return es[i].x < es[j].x
		}
		return es[i].y < es[j].y
	})
	h := sha256.New()
	for _, e := range es {
		fmt.Fprintf(h, "%d/%d/%d/%s\n", e.z, e.x, e.y, e.sha)
	}
	return hex.EncodeToString(h.Sum(nil))
}

func enhancedRevision(products map[string]enhancedProduct) string {
	names := make([]string, 0, len(products))
	for n := range products {
		names = append(names, n)
	}
	sort.Strings(names)
	h := sha256.New()
	for _, n := range names {
		p := products[n]
		fmt.Fprintf(h, "%s|%s|%d|%d|%d|%d|%s\n", n, p.Encoding, p.Minzoom, p.Maxzoom, p.Tiles, p.Bytes, p.SHA)
	}
	return hex.EncodeToString(h.Sum(nil))[:32]
}

// readEnhanced resolves a map's immutable descriptor and revision. It returns
// nil error with nil doc for legacy maps.
func (s *Store) readEnhanced(ctx context.Context, id string) (*enhancedMapDoc, error) {
	var raw []byte
	s.mu.Lock()
	e := s.db.QueryRowContext(ctx, `SELECT data FROM offline_maps WHERE id=?`, id).Scan(&raw)
	s.mu.Unlock()
	if e != nil {
		return nil, e
	}
	d, e := enhancedDoc(raw)
	if e != nil {
		return nil, nil // legacy map: not an error
	}
	return d, nil
}

var errMapReferenced = errors.New("map revision is referenced by recordings or historical sessions; release references before deletion")

// pinMapRevision records an immutable whole-revision reference for recording,
// Travel Back or historical sessions (integrated in later scopes). Idempotent.
func (s *Store) pinMapRevision(ctx context.Context, id, kind string) (string, error) {
	if len(kind) == 0 || len(kind) > 64 {
		return "", errors.New("invalid pin kind")
	}
	d, e := s.readEnhanced(ctx, id)
	if e != nil {
		return "", e
	}
	if d == nil {
		return "", errors.New("not an enhanced map revision")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	_, e = s.db.ExecContext(ctx, `INSERT OR IGNORE INTO offline_map_pins(map_id,revision,kind,created) VALUES(?,?,?,?)`, id, d.Revision, kind, s.Now().UTC().Format(wireTime))
	return d.Revision, e
}

func (s *Store) releaseMapRevision(ctx context.Context, id, revision, kind string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, e := s.db.ExecContext(ctx, `DELETE FROM offline_map_pins WHERE map_id=? AND revision=? AND kind=?`, id, revision, kind)
	return e
}

// mapPinned reports whether any live reference protects the map revision.
// The delete path consults it before removing assets; pins are whole-revision,
// so deletion is refused while any reference exists.
func (s *Store) mapPinnedTx(tx *sql.Tx, id string) (int, error) {
	var n int
	e := tx.QueryRow(`SELECT count(*) FROM offline_map_pins WHERE map_id=?`, id).Scan(&n)
	return n, e
}

// ---- tile delivery ----

func tileMIME(product string) (string, string) {
	switch product {
	case productVector:
		return "application/x-protobuf", "gzip"
	case productDEM, productLandcover:
		return "image/png", ""
	}
	return "", ""
}

// mapTileRequest serves immutable tiles for validated enhanced maps.
// Local same-origin, read-only, bounded coordinates; no filesystem paths.
func (s *Store) mapTileRequest(w http.ResponseWriter, r *http.Request) bool {
	// /local/maps/{id}/tiles/{product}/{z}/{x}/{y}
	if r.Method != "GET" || !strings.HasPrefix(r.URL.Path, "/local/maps/") || !strings.Contains(r.URL.Path, "/tiles/") {
		return false
	}
	rest := strings.TrimPrefix(r.URL.Path, "/local/maps/")
	parts := strings.SplitN(rest, "/tiles/", 2)
	if len(parts) != 2 {
		return false
	}
	id := parts[0]
	segs := strings.Split(parts[1], "/")
	if len(segs) != 4 {
		failure(w, 404, "tile_unavailable", errors.New("invalid tile path"))
		return true
	}
	product := segs[0]
	mimeType, encoding := tileMIME(product)
	if mimeType == "" {
		failure(w, 404, "tile_unavailable", errors.New("unknown tile product"))
		return true
	}
	nums := make([]int, 3)
	for i, seg := range segs[1:] {
		v, e := strconv.Atoi(seg)
		if e != nil || v < 0 {
			failure(w, 404, "tile_unavailable", errors.New("invalid tile coordinate"))
			return true
		}
		nums[i] = v
	}
	z, x, y := nums[0], nums[1], nums[2]
	if z > 22 || x >= (1<<z) || y >= (1<<z) {
		failure(w, 404, "tile_unavailable", errors.New("tile coordinate outside valid range"))
		return true
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	d, e := s.readEnhanced(ctx, id)
	if e != nil || d == nil {
		failure(w, 404, "tile_unavailable", errors.New("map unavailable or not enhanced"))
		return true
	}
	p, ok := d.Products[product]
	if !ok || z < p.Minzoom || z > p.Maxzoom {
		failure(w, 404, "tile_unavailable", errors.New("product or zoom not covered"))
		return true
	}
	var data []byte
	s.mu.Lock()
	e = s.db.QueryRowContext(ctx, `SELECT data FROM offline_map_tiles WHERE map_id=? AND product=? AND z=? AND x=? AND y=?`, id, product, z, x, y).Scan(&data)
	s.mu.Unlock()
	if e != nil {
		failure(w, 404, "tile_unavailable", errors.New("tile not present"))
		return true
	}
	w.Header().Set("Content-Type", mimeType)
	if encoding != "" {
		w.Header().Set("Content-Encoding", encoding)
	}
	w.Header().Set("Cache-Control", "private, immutable, max-age=31536000")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(data)
	return true
}

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
	"io"
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
	Type        string                     `json:"type"` // always "GNSS-enhanced-map"
	Version     int                        `json:"version"`
	Revision    string                     `json:"revision"`
	Style       string                     `json:"style"`
	Requested   json.RawMessage            `json:"requested,omitempty"`
	Products    map[string]enhancedProduct `json:"products"`
	Missing     []string                   `json:"missing,omitempty"`
	Provenance  map[string]string          `json:"provenance,omitempty"`
	Attribution string                     `json:"attribution"`
}

type enhancedProduct struct {
	Encoding string     `json:"encoding"` // mvt-gzip | png
	CRS      string     `json:"crs"`      // EPSG:3857 tile matrix
	Minzoom  int        `json:"minzoom"`
	Maxzoom  int        `json:"maxzoom"`
	Bounds   [4]float64 `json:"bounds"` // actual coverage w,s,e,n
	Tiles    int        `json:"tiles"`
	Bytes    int        `json:"bytes"`
	SHA      string     `json:"sha256"` // canonical tile-set hash
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
	// Existence verification and pin insertion must be atomic with respect to
	// deletion: both run inside one transaction under the store mutex, which the
	// delete path also holds for its check-and-delete transaction.
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, e := s.db.BeginTx(ctx, nil)
	if e != nil {
		return "", e
	}
	defer tx.Rollback()
	var raw []byte
	e = tx.QueryRowContext(ctx, `SELECT data FROM offline_maps WHERE id=?`, id).Scan(&raw)
	if e != nil {
		return "", errors.New("not an enhanced map revision")
	}
	d, e := enhancedDoc(raw)
	if e != nil || d == nil {
		return "", errors.New("not an enhanced map revision")
	}
	if _, e = tx.ExecContext(ctx, `INSERT OR IGNORE INTO offline_map_pins(map_id,revision,kind,created) VALUES(?,?,?,?)`, id, d.Revision, kind, s.Now().UTC().Format(wireTime)); e != nil {
		return "", e
	}
	if e = tx.Commit(); e != nil {
		return "", e
	}
	return d.Revision, nil
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

// enhancedMapPinRequest acquires and releases ephemeral Travel Back pins
// (#96). Acquisition verifies existence and pins atomically under the store
// mutex — the deletion transaction holds the same mutex for its check, so a
// pin and a delete can never interleave. Release is idempotent. Travel pins
// use the reserved kind 'travel' and are swept at startup because travel
// sessions never survive a restart.
func (s *Store) enhancedMapPinRequest(w http.ResponseWriter, r *http.Request) bool {
	if r.URL.Path != "/local/enhanced-map-pin" {
		return false
	}
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed", errors.New("use POST"))
		return true
	}
	if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
		failure(w, 403, "forbidden", errors.New("same-origin controls only"))
		return true
	}
	var in struct {
		MapID   string `json:"map_id"`
		Release bool   `json:"release"`
	}
	b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 4096))
	if err == nil {
		err = json.Unmarshal(b, &in)
	}
	if err == nil && (in.MapID == "" || len(in.MapID) > 256) {
		err = errors.New("valid map_id required")
	}
	if err != nil {
		failure(w, 400, "invalid_pin_request", err)
		return true
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	if in.Release {
		// Release every travel pin for the map; the single live travel session
		// pins at most one revision per map and exits idempotently.
		if err = s.releaseMapRevisionKind(ctx, in.MapID, "travel"); err == nil {
			respond(w, 200, map[string]bool{"released": true})
			return true
		}
	} else {
		var revision string
		revision, err = s.pinMapRevision(ctx, in.MapID, "travel")
		if err == nil {
			respond(w, 200, map[string]any{"pinned": true, "revision": revision})
			return true
		}
	}
	failure(w, 409, "pin_unavailable", err)
	return true
}

// enhancedRevisionRequest discloses the current immutable revision of an
// enhanced map so playback can validate recorded revision evidence exactly
// before reconstruction (#96): a replaced or missing revision degrades
// honestly instead of silently rendering substituted geography.
func (s *Store) enhancedRevisionRequest(w http.ResponseWriter, r *http.Request) bool {
	if r.URL.Path != "/local/enhanced-revision" {
		return false
	}
	if r.Method != "GET" {
		failure(w, 405, "method_not_allowed", errors.New("use GET"))
		return true
	}
	id := r.URL.Query().Get("id")
	if id == "" || len(id) > 256 {
		failure(w, 400, "invalid_revision_request", errors.New("valid id required"))
		return true
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	d, err := s.readEnhanced(ctx, id)
	if err != nil || d == nil {
		failure(w, 404, "revision_unavailable", errors.New("enhanced map revision unavailable"))
		return true
	}
	respond(w, 200, map[string]string{"map_id": id, "revision": d.Revision})
	return true
}

func (s *Store) releaseMapRevisionKind(ctx context.Context, id, kind string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, e := s.db.ExecContext(ctx, `DELETE FROM offline_map_pins WHERE map_id=? AND kind=?`, id, kind)
	return e
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
	// /local/enhanced-gated is the playback availability gate's suppression
	// target (#96): tiles recorded as unavailable at capture are rewritten here
	// and receive honest absence, exactly as a tile that never existed.
	if r.Method == "GET" && r.URL.Path == "/local/enhanced-gated" {
		failure(w, 404, "tile_unavailable", errors.New("tile unavailable at capture"))
		return true
	}
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

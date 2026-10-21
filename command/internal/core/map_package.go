package core

// Standalone operational maps; recording snapshots remain Issue #38.
import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

type mapPackageAsset struct {
	Path     string          `json:"path"`
	SHA      string          `json:"sha256"`
	Bytes    int             `json:"bytes"`
	Key      string          `json:"chunk,omitempty"`
	Layer    string          `json:"layer"`
	Evidence json.RawMessage `json:"evidence,omitempty"`
}
type mapPackage struct {
	Version     int               `json:"version"`
	Format      string            `json:"format"`
	Source      string            `json:"name"`
	Map         json.RawMessage   `json:"map"`
	Assets      []mapPackageAsset `json:"assets"`
	Attribution string            `json:"attribution"`
	Derivation  string            `json:"derivation"`
	Partial     bool              `json:"partial"`
}

func shaMap(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func mustJSON(v any) []byte  { b, _ := json.Marshal(v); return b }
func anyComponents(m map[string]bool) bool {
	for _, v := range m {
		if v {
			return true
		}
	}
	return false
}
func (s *Store) publishJob(id, source string, b []byte) (savedMap, error) {
	m := savedMap{ID: id, Source: source, Created: s.Now().UTC().Format(wireTime), Bytes: len(b)}
	s.mu.Lock()
	defer s.mu.Unlock()
	var count, total int
	if e := s.db.QueryRow(`SELECT count(*),coalesce(sum(length(data)),0) FROM offline_maps`).Scan(&count, &total); e != nil {
		return m, e
	}
	if count >= 64 || total+len(b) > mapAssetBudget {
		return m, errors.New("map library resource limit")
	}
	_, e := s.db.Exec(`INSERT OR IGNORE INTO offline_maps(id,source,data,created) VALUES(?,?,?,?)`, id, source, b, m.Created)
	return m, e
}
func (s *Store) exportMapPackage(ctx context.Context, id string, f *os.File) error {
	var raw []byte
	var source string
	s.mu.Lock()
	e := s.db.QueryRowContext(ctx, `SELECT data,source FROM offline_maps WHERE id=?`, id).Scan(&raw, &source)
	s.mu.Unlock()
	if e != nil {
		return e
	}
	var portable mapJob
	if json.Unmarshal(raw, &portable) == nil && len(portable.Plan.Chunks) > 0 {
		portable.SourceID = ""
		portable.Transfer = mapTransfer{}
		raw = mustJSON(portable)
	}
	p := mapPackage{Version: 1, Format: "GNSS-Command-map", Source: source, Map: raw, Assets: []mapPackageAsset{}, Attribution: "© OpenStreetMap contributors · ODbL; DEM: " + demAttribution, Derivation: "GTERR001 native DEM; compatible Command Horn hillshade/marching-square contours; voids transparent. Checksums show byte integrity, not publisher authenticity."}
	j, e := validatePortableJob(raw)
	if e != nil {
		return e
	}
	z := zip.NewWriter(f)
	defer z.Close()
	total := len(raw)
	add := func(name, key, layer string, b, ev []byte) error {
		total += len(b)
		if total > mapAssetBudget {
			return errors.New("map package exceeds 256 MiB")
		}
		if layer == "terrain" {
			if _, e := decodeTerrain(b); e != nil {
				return e
			}
		} else if e := validateMap(b); e != nil {
			return e
		}
		w, e := z.Create(name)
		if e != nil {
			return e
		}
		if _, e = w.Write(b); e != nil {
			return e
		}
		p.Assets = append(p.Assets, mapPackageAsset{name, shaMap(b), len(b), key, layer, ev})
		return nil
	}
	if len(j.Plan.Chunks) > 0 {
		for _, c := range j.Plan.Chunks {
			for _, layer := range []string{"vector", "terrain"} {
				var b []byte
				var ev string
				s.mu.Lock()
				e = s.db.QueryRowContext(ctx, `SELECT `+layer+`,`+layer+`_evidence FROM offline_map_chunks WHERE map_id=? AND chunk_key=?`, id, c.Key).Scan(&b, &ev)
				s.mu.Unlock()
				if e != nil && e != sql.ErrNoRows {
					return e
				}
				if len(b) == 0 {
					if layer == "vector" || anyComponents(j.Components) {
						p.Partial = true
					}
					continue
				}
				if e = add("chunks/"+c.Key+"."+layer, c.Key, layer, b, []byte(ev)); e != nil {
					return e
				}
			}
		}
	} else {
		var b []byte
		s.mu.Lock()
		e = s.db.QueryRowContext(ctx, `SELECT data FROM offline_terrain WHERE map_id=?`, id).Scan(&b)
		s.mu.Unlock()
		if e != nil && e != sql.ErrNoRows {
			return e
		}
		if len(b) > 0 {
			if e = add("terrain.gterrain", "", "terrain", b, nil); e != nil {
				return e
			}
		}
	}
	manifest := mustJSON(p)
	if len(manifest) > 2*1024*1024 {
		return errors.New("map manifest exceeds 2 MiB portability limit; use a smaller saved region")
	}
	w, e := z.Create("manifest.json")
	if e == nil {
		_, e = w.Write(manifest)
	}
	if e == nil {
		e = z.Close()
	}
	return e
}

var mapChunkKey = regexp.MustCompile(`^-?[0-9]{1,5}_-?[0-9]{1,5}$`)

func validatePortableJob(raw []byte) (mapJob, error) {
	var j mapJob
	if e := json.Unmarshal(raw, &j); e != nil {
		return j, e
	}
	if len(j.Plan.Chunks) == 0 {
		return j, validateMap(raw)
	}
	if len(raw) > 2*1024*1024 || len(j.Plan.Chunks) > maxMapChunks || j.Type != "FeatureCollection" || len(j.Features) > 0 {
		return j, errors.New("invalid portable map plan")
	}
	var canonical mapPlan
	var err error
	if j.Plan.FullExtent {
		canonical, err = planExtent(j.Plan.Bounds)
	} else {
		canonical, err = planCircle(j.Plan.Request)
	}
	if err != nil || canonical.Unsupported != "" || len(canonical.Chunks) != len(j.Plan.Chunks) {
		return j, errors.New("portable plan is not supported")
	}
	for i, c := range j.Plan.Chunks {
		if c.Key != canonical.Chunks[i].Key || !mapBoundsEqual(c.Bounds, canonical.Chunks[i].Bounds) {
			return j, errors.New("portable chunk coverage differs from requested plan")
		}
	}
	if !mapBoundsEqual(j.Plan.Bounds, canonical.Bounds) || j.Plan.Request != canonical.Request {
		return j, errors.New("portable requested coverage differs from canonical plan")
	}
	if len(j.Plan.Circle) != len(canonical.Circle) {
		return j, errors.New("portable requested outline differs from canonical plan")
	}
	for i, p := range j.Plan.Circle {
		if math.Abs(p[0]-canonical.Circle[i][0]) > 1e-9 || math.Abs(p[1]-canonical.Circle[i][1]) > 1e-9 {
			return j, errors.New("portable outline is not the requested geographic circle")
		}
	}
	if len(j.Plan.Circle) > 721 {
		return j, errors.New("portable circle exceeds geometry budget")
	}
	keys := map[string]bool{}
	for _, c := range j.Plan.Chunks {
		b := c.Bounds
		if !mapChunkKey.MatchString(c.Key) || keys[c.Key] || b[0] < -180 || b[2] > 180 || b[1] < -mapMercatorLimit || b[3] > mapMercatorLimit || b[0] >= b[2] || b[1] >= b[3] {
			return j, errors.New("invalid chunk bounds/key")
		}
		keys[c.Key] = true
	}
	for k := range j.Components {
		if k != "elevation" && k != "contours" && k != "hillshade" {
			return j, errors.New("unsupported map product")
		}
	}
	return j, nil
}
func (s *Store) importMapPackage(ctx context.Context, f *os.File, size int64) (savedMap, error) {
	fail := func(e error) (savedMap, error) { return savedMap{}, e }
	z, e := zip.NewReader(f, size)
	if e != nil {
		return fail(e)
	}
	if len(z.File) > 2*maxMapChunks+2 {
		return fail(errors.New("too many archive entries"))
	}
	files := map[string]*zip.File{}
	var expanded uint64
	for _, v := range z.File {
		if files[v.Name] != nil || strings.Contains(v.Name, "..") || strings.ContainsAny(v.Name, "\\:\x00") || strings.HasPrefix(v.Name, "/") || v.Mode()&os.ModeType != 0 {
			return fail(errors.New("unsafe or duplicate archive entry"))
		}
		if v.UncompressedSize64 > mapLimit+2*1024*1024 {
			return fail(errors.New("oversized archive entry"))
		}
		expanded += v.UncompressedSize64
		if expanded > mapAssetBudget {
			return fail(errors.New("expanded archive exceeds 256 MiB"))
		}
		files[v.Name] = v
	}
	read := func(name string, limit int) ([]byte, error) {
		v := files[name]
		if v == nil {
			return nil, errors.New("missing map asset: " + name)
		}
		if v.UncompressedSize64 > uint64(limit) {
			return nil, errors.New("map entry exceeds stage limit")
		}
		r, e := v.Open()
		if e != nil {
			return nil, e
		}
		defer r.Close()
		b, e := io.ReadAll(io.LimitReader(&demContextReader{Reader: r, ctx: ctx}, int64(limit)+1))
		if len(b) > limit {
			return nil, errors.New("expanded entry exceeds limit")
		}
		return b, e
	}
	b, e := read("manifest.json", 2*1024*1024)
	if e != nil {
		return fail(e)
	}
	var p mapPackage
	if e = json.Unmarshal(b, &p); e != nil {
		return fail(e)
	}
	if p.Version != 1 || p.Format != "GNSS-Command-map" || len(p.Source) > 4096 || len(p.Attribution) > 16384 {
		return fail(errors.New("unsupported package metadata"))
	}
	j, e := validatePortableJob(p.Map)
	if e != nil {
		return fail(e)
	}
	if len(files) != len(p.Assets)+1 {
		return fail(errors.New("undeclared or missing archive entries"))
	}
	stageDir, e := os.MkdirTemp("", "gnss-map-stage-*")
	if e != nil {
		return fail(e)
	}
	defer os.RemoveAll(stageDir)
	staged := map[string]string{}
	declared := map[string]bool{}
	keys := map[string]bool{}
	chunkBounds := map[string][4]float64{}
	for _, c := range j.Plan.Chunks {
		chunkBounds[c.Key] = c.Bounds
		keys[c.Key] = true
	}
	for _, a := range p.Assets {
		if declared[a.Path] || a.Path == "manifest.json" {
			return fail(errors.New("duplicate manifest asset"))
		}
		declared[a.Path] = true
		if a.Layer != "vector" && a.Layer != "terrain" {
			return fail(errors.New("unsupported asset layer"))
		}
		if len(j.Plan.Chunks) > 0 && (!keys[a.Key] || a.Path != "chunks/"+a.Key+"."+a.Layer) {
			return fail(errors.New("asset outside declared plan"))
		}
		if len(j.Plan.Chunks) == 0 && (a.Key != "" || a.Layer != "terrain" || a.Path != "terrain.gterrain") {
			return fail(errors.New("invalid legacy map asset"))
		}
		b, e = read(a.Path, mapLimit)
		if e != nil {
			return fail(e)
		}
		if len(b) != a.Bytes || shaMap(b) != a.SHA {
			return fail(errors.New("asset checksum mismatch"))
		}
		stagePath := filepath.Join(stageDir, shaMap([]byte(a.Path)))
		if e = os.WriteFile(stagePath, b, 0600); e != nil {
			return fail(e)
		}
		staged[a.Path] = stagePath
		if a.Layer == "terrain" {
			var grid terrainGrid
			grid, e = decodeTerrain(b)
			if e == nil && a.Key != "" {
				bounds := chunkBounds[a.Key]
				if grid.Bounds[0] > bounds[0]+1e-9 || grid.Bounds[1] > bounds[1]+1e-9 || grid.Bounds[2] < bounds[2]-1e-9 || grid.Bounds[3] < bounds[3]-1e-9 {
					e = errors.New("terrain does not cover declared chunk envelope")
				}
			}
		} else {
			e = validateMap(b)
			if e == nil {
				var vector struct {
					Bounds      [4]float64 `json:"requested_bounds"`
					Acquisition string     `json:"acquisition"`
				}
				if json.Unmarshal(b, &vector) != nil || vector.Bounds != chunkBounds[a.Key] || vector.Acquisition != "complete" {
					e = errors.New("vector coverage differs from declared chunk envelope")
				}
			}
		}
		if e != nil {
			return fail(e)
		}
		if len(j.Plan.Chunks) > 0 {
			var ev chunkEvidence
			if json.Unmarshal(a.Evidence, &ev) != nil || ev.Status != "complete" || ev.Bytes != len(b) {
				return fail(errors.New("asset evidence inconsistent with bytes"))
			}
		}
	}
	partial := false
	for _, c := range j.Plan.Chunks {
		if !declared["chunks/"+c.Key+".vector"] || (anyComponents(j.Components) && !declared["chunks/"+c.Key+".terrain"]) {
			partial = true
		}
	}
	if partial != p.Partial {
		return fail(errors.New("inconsistent missing-layer disclosure"))
	}
	id := "portable-" + shaMap(mustJSON(p))[:32]
	m := savedMap{ID: id, Source: p.Source, Created: s.Now().UTC().Format(wireTime), Bytes: len(p.Map)}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, e := s.db.BeginTx(ctx, nil)
	if e != nil {
		return fail(e)
	}
	defer tx.Rollback()
	var exists int
	if e = tx.QueryRow(`SELECT count(*) FROM offline_maps WHERE id=?`, id).Scan(&exists); e != nil {
		return fail(e)
	}
	if exists > 0 {
		return m, nil
	}
	var count, total int
	if e = tx.QueryRow(`SELECT count(*),coalesce(sum(length(data)),0)+(SELECT coalesce(sum(coalesce(length(vector),0)+coalesce(length(terrain),0)),0) FROM offline_map_chunks) FROM offline_maps`).Scan(&count, &total); e != nil {
		return fail(e)
	}
	if count >= 64 || int64(total)+int64(expanded) > mapAssetBudget {
		return fail(errors.New("map library capacity exceeded"))
	}
	if len(j.Plan.Chunks) > 0 {
		j.SourceID = ""
		j.Transfer = mapTransfer{}
		p.Map = mustJSON(j)
	}
	if _, e = tx.Exec(`INSERT INTO offline_maps(id,source,data,created) VALUES(?,?,?,?)`, id, m.Source, []byte(p.Map), m.Created); e != nil {
		return fail(e)
	}
	for _, a := range p.Assets {
		b, e = os.ReadFile(staged[a.Path])
		if e != nil {
			return fail(e)
		}
		if a.Key == "" {
			_, e = tx.Exec(`INSERT INTO offline_terrain(map_id,data) VALUES(?,?)`, id, b)
		} else {
			_, e = tx.Exec(`INSERT OR IGNORE INTO offline_map_chunks(map_id,chunk_key) VALUES(?,?)`, id, a.Key)
			if e == nil {
				_, e = tx.Exec(`UPDATE offline_map_chunks SET `+a.Layer+`=?,`+a.Layer+`_evidence=? WHERE map_id=? AND chunk_key=?`, b, string(a.Evidence), id, a.Key)
			}
		}
		if e != nil {
			return fail(e)
		}
	}
	e = tx.Commit()
	return m, e
}
func (s *Store) mapPackageRequest(w http.ResponseWriter, r *http.Request) bool {
	export := strings.HasPrefix(r.URL.Path, "/local/maps/") && strings.HasSuffix(r.URL.Path, "/export")
	if !export && r.URL.Path != "/local/map-package" {
		return false
	}
	if (export && r.Method != "GET") || (!export && r.Method != "POST") {
		failure(w, 405, "method_not_allowed", errors.New("unsupported method"))
		return true
	}
	if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
		failure(w, 403, "forbidden", errors.New("same-origin controls only"))
		return true
	}
	if !terrainPreparation.TryLock() {
		failure(w, 409, "map_busy", errors.New("map preparation active"))
		return true
	}
	defer terrainPreparation.Unlock()
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Minute)
	defer cancel()
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(2 * time.Minute))
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(2 * time.Minute))
	if e := mapDiskReserve(s.path, mapAssetBudget*2); e != nil {
		failure(w, 503, "storage_unavailable", e)
		return true
	}
	f, e := os.CreateTemp("", "gnss-map-*.zip")
	if e != nil {
		failure(w, 503, "storage_unavailable", e)
		return true
	}
	defer os.Remove(f.Name())
	defer f.Close()
	if export {
		id := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/local/maps/"), "/export")
		e = s.exportMapPackage(ctx, id, f)
		if e == nil {
			_, e = f.Seek(0, 0)
		}
		if e != nil {
			failure(w, 503, "map_export_failed", e)
			return true
		}
		w.Header().Set("Content-Type", "application/zip")
		w.Header().Set("Content-Disposition", `attachment; filename="offline-map.gmap.zip"`)
		_, _ = io.Copy(w, f)
		return true
	}
	n, e := io.Copy(f, http.MaxBytesReader(w, r.Body, mapAssetBudget+2*1024*1024))
	if e != nil {
		failure(w, 400, "invalid_package", e)
		return true
	}
	m, e := s.importMapPackage(ctx, f, n)
	if e != nil {
		failure(w, 400, "invalid_package", e)
	} else {
		respond(w, 200, m)
	}
	return true
}

func mapBoundsEqual(a, b [4]float64) bool {
	for i := range a {
		if math.Abs(a[i]-b[i]) > 1e-9 {
			return false
		}
	}
	return true
}

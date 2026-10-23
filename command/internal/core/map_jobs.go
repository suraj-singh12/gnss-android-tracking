package core

// The offline_maps row owns the request; bounded child assets use existing GeoJSON
// and GTERR001 formats. Each chunk is an independent atomic retry boundary.
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
	"strings"
	"time"
)

const mapAssetBudget = 256 * 1024 * 1024

type mapJob struct {
	Transfer    mapTransfer     `json:"transfer,omitempty"`
	SourceID    string          `json:"source_id,omitempty"`
	Type        string          `json:"type"`
	Features    []any           `json:"features"`
	Plan        mapPlan         `json:"plan"`
	Components  map[string]bool `json:"components"`
	Attribution string          `json:"attribution"`
}
type chunkEvidence struct {
	Reused         bool               `json:"reused_verified_product,omitempty"`
	SourceAttempts []mapSourceAttempt `json:"source_attempts,omitempty"`
	ValidBounds    []float64          `json:"valid_dem_cell_bounds,omitempty"`
	SourceURL      string             `json:"source_url,omitempty"`
	SourceBytes    int64              `json:"observed_transfer_body_bytes,omitempty"`
	ElapsedMS      float64            `json:"elapsed_ms,omitempty"`
	Status         string             `json:"status"`
	Attempts       int                `json:"attempts"`
	Bytes          int                `json:"stored_bytes"`
	At             string             `json:"acquired_at,omitempty"`
	Error          string             `json:"error,omitempty"`
	ValidCells     int                `json:"valid_dem_cells,omitempty"`
	VoidCells      int                `json:"void_dem_cells,omitempty"`
	Metadata       *terrainGrid       `json:"dem_metadata,omitempty"`
	Report         *DEMReport         `json:"transfer,omitempty"`
}
type chunkStatus struct {
	mapChunk
	Vector  chunkEvidence `json:"vector"`
	Terrain chunkEvidence `json:"terrain"`
}

func (s *Store) readMapJob(id string) (mapJob, error) {
	var j mapJob
	var b []byte
	s.mu.Lock()
	err := s.db.QueryRow(`SELECT data FROM offline_maps WHERE id=?`, id).Scan(&b)
	s.mu.Unlock()
	if err == nil {
		err = json.Unmarshal(b, &j)
	}
	if err == nil {
		j.Transfer, err = s.transferState(id)
	}
	if err == nil && len(j.Plan.Chunks) == 0 {
		err = errors.New("saved map is not a chunked radius acquisition")
	}
	return j, err
}
func (s *Store) createMapJob(c mapCircle, components map[string]bool, confirmed bool) (savedMap, error) {
	p, err := planCircle(c)
	if err != nil {
		return savedMap{}, err
	}
	if p.Unsupported != "" {
		return savedMap{}, errors.New(p.Unsupported)
	}
	if p.Confirmation && !confirmed {
		return savedMap{}, errors.New("confirm the displayed source/chunk and storage estimate before acquisition")
	}
	for k := range components {
		if k != "hillshade" && k != "contours" && k != "elevation" {
			return savedMap{}, errors.New("unknown terrain component")
		}
	}
	j := mapJob{Type: "FeatureCollection", Features: []any{}, Plan: p, Components: components, Attribution: "© OpenStreetMap contributors · ODbL; DEM: " + demAttribution}
	b, err := json.Marshal(j)
	if err != nil {
		return savedMap{}, err
	}
	// Same request and selections always resolve to the same saved map after response loss.
	canonical := c
	canonical.Lon = wrapLongitude(c.Lon)
	if canonical.Lon == 0 {
		canonical.Lon = 0
	}
	if canonical.Lat == 0 {
		canonical.Lat = 0
	}
	identity, _ := json.Marshal(canonical)
	h := sha256.Sum256(identity)
	mapID := "radius-" + hex.EncodeToString(h[:16])
	m := savedMap{ID: mapID, Source: fmt.Sprintf("OSM %.7f, %.7f · %g m radius", c.Lat, c.Lon, c.Radius), Created: s.Now().UTC().Format(wireTime), Bytes: len(b), Bounds: p.Bounds[:]}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return m, err
	}
	defer tx.Rollback()
	var count int
	err = tx.QueryRow(`SELECT count(*) FROM offline_maps WHERE id<>?`, mapID).Scan(&count)
	if err != nil {
		return m, err
	}
	if count >= 64 {
		return m, errors.New("map library full (64 maps); existing maps retained")
	}
	var existing []byte
	previousErr := tx.QueryRow(`SELECT data,source,created FROM offline_maps WHERE id=?`, mapID).Scan(&existing, &m.Source, &m.Created)
	if previousErr == nil {
		var saved mapJob
		if err = json.Unmarshal(existing, &saved); err != nil {
			return m, err
		}
		if saved.Components == nil {
			saved.Components = map[string]bool{}
		}
		for kind, selected := range components {
			if selected {
				saved.Components[kind] = true
			}
		}
		m.Bounds = saved.Plan.Bounds[:]
		b, err = json.Marshal(saved)
		m.Bytes = len(b)
		if err != nil {
			return m, err
		}
		_, err = tx.Exec(`UPDATE offline_maps SET data=? WHERE id=?`, b, mapID)
		if err == nil {
			err = tx.Commit()
		}
		return m, err
	} else if !errors.Is(previousErr, sql.ErrNoRows) {
		return m, previousErr
	}
	_, err = tx.Exec(`INSERT OR IGNORE INTO offline_maps(id,source,data,created) VALUES(?,?,?,?)`, mapID, m.Source, b, m.Created)
	if err == nil {
		err = tx.Commit()
	}
	return m, err
}
func (s *Store) mapChunkStatuses(id string, j mapJob) ([]chunkStatus, error) {
	result := make([]chunkStatus, len(j.Plan.Chunks))
	indices := map[string]int{}
	for i, c := range j.Plan.Chunks {
		result[i] = chunkStatus{mapChunk: c, Vector: chunkEvidence{Status: "not acquired"}, Terrain: chunkEvidence{Status: "not acquired"}}
		indices[c.Key] = i
		if c.Bounds[1] < -56 || c.Bounds[3] > 60 {
			result[i].Terrain.Status = "source unsupported (56°S–60°N)"
		}
	}
	db := s.viewDB
	if db == nil {
		s.mu.Lock()
		defer s.mu.Unlock()
		db = s.db
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	rows, err := db.QueryContext(ctx, `SELECT chunk_key,vector_evidence,terrain_evidence FROM offline_map_chunks WHERE map_id=?`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var key, v, t string
		if err = rows.Scan(&key, &v, &t); err != nil {
			return nil, err
		}
		i, ok := indices[key]
		if !ok {
			return nil, errors.New("stored chunk outside requested plan")
		}
		if err = json.Unmarshal([]byte(v), &result[i].Vector); err != nil {
			return nil, err
		}
		if err = json.Unmarshal([]byte(t), &result[i].Terrain); err != nil {
			return nil, err
		}
	}
	return result, rows.Err()
}
func (s *Store) writeChunk(ctx context.Context, mapID, key, layer string, b []byte, e chunkEvidence) error {
	encoded, err := json.Marshal(e)
	if err != nil {
		return err
	}
	column := "vector"
	if layer == "terrain" {
		column = "terrain"
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if len(b) > 0 {
		var total int
		err = tx.QueryRowContext(ctx, `SELECT coalesce(sum(length(vector)),0)+coalesce(sum(length(terrain)),0)-coalesce((SELECT length(`+column+`) FROM offline_map_chunks WHERE map_id=? AND chunk_key=?),0) FROM offline_map_chunks`, mapID, key).Scan(&total)
		if err != nil {
			return err
		}
		if total+len(b) > mapAssetBudget {
			return errors.New("chunk library storage budget exceeded (256 MiB); previous valid assets retained")
		}
	}
	_, err = tx.ExecContext(ctx, `INSERT OR IGNORE INTO offline_map_chunks(map_id,chunk_key) VALUES(?,?)`, mapID, key)
	if err == nil {
		if b != nil {
			_, err = tx.ExecContext(ctx, `UPDATE offline_map_chunks SET `+column+`=?,`+column+`_evidence=? WHERE map_id=? AND chunk_key=?`, b, string(encoded), mapID, key)
		} else {
			_, err = tx.ExecContext(ctx, `UPDATE offline_map_chunks SET `+column+`_evidence=? WHERE map_id=? AND chunk_key=?`, string(encoded), mapID, key)
		}
	}
	if err == nil {
		err = tx.Commit()
	}
	return err
}
func (s *Store) acquireMapChunk(ctx context.Context, p *mapProvider, id, key, layer string, emit demObserver) error {
	budget := &mapBudget{s: s, id: id, emit: emit}
	ctx = context.WithValue(ctx, mapBudgetKey{}, budget)
	beforeTransfer, _ := s.transferState(id)
	j, err := s.readMapJob(id)
	if err != nil {
		return err
	}
	var chunk *mapChunk
	for i := range j.Plan.Chunks {
		if j.Plan.Chunks[i].Key == key {
			chunk = &j.Plan.Chunks[i]
			break
		}
	}
	if chunk == nil {
		return errors.New("chunk not in requested plan")
	}
	previous := chunkEvidence{}
	column := "vector_evidence"
	if layer == "terrain" {
		column = "terrain_evidence"
	}
	var evidence string
	s.mu.Lock()
	err = s.db.QueryRow(`SELECT `+column+` FROM offline_map_chunks WHERE map_id=? AND chunk_key=?`, id, key).Scan(&evidence)
	s.mu.Unlock()
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if err == nil {
		if err = json.Unmarshal([]byte(evidence), &previous); err != nil {
			return err
		}
	}
	if previous.Status == "complete" {
		needsComponents := false
		if layer == "terrain" {
			for k, v := range j.Components {
				if v && (previous.Metadata == nil || !previous.Metadata.Components[k]) {
					needsComponents = true
				}
			}
		}
		if !needsComponents {
			return nil
		} // completed source reused, including after lost response
		var encoded []byte
		s.mu.Lock()
		err = s.db.QueryRow(`SELECT terrain FROM offline_map_chunks WHERE map_id=? AND chunk_key=?`, id, key).Scan(&encoded)
		s.mu.Unlock()
		if err != nil {
			return err
		}
		g, e := decodeTerrain(encoded)
		if e != nil {
			return e
		}
		g.Components = j.Components
		encoded, err = encodeTerrain(g)
		if err != nil {
			return err
		}
		g.Samples = nil
		previous.Metadata = &g
		previous.Bytes = len(encoded)
		return s.writeChunk(ctx, id, key, layer, encoded, previous)
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if layer == "terrain" {
		cached, proof, e := s.reuseTerrain(ctx, id, *chunk, j.Components)
		if e != nil {
			return e
		}
		if cached != nil {
			emitDEM(emit, DEMProgress{Stage: "reusing verified saved DEM and deriving selected products"})
			return s.writeChunk(ctx, id, key, layer, cached, proof)
		}
	}
	if err = mapDiskReserve(s.path, 168*1024*1024); err != nil {
		return err
	}
	e := chunkEvidence{Status: "interrupted; retry safely", Attempts: previous.Attempts + 1, Error: "request started; no validated commit yet"}
	if err = s.writeChunk(ctx, id, key, layer, nil, e); err != nil {
		return err
	}
	started := time.Now()
	var b []byte
	if layer == "vector" {
		e.SourceURL = p.overpass
		b, err = s.acquireVector(ctx, p, id, *chunk, emit)
		if err == nil {
			var source struct {
				Provider string `json:"provider"`
				Bytes    int64  `json:"source_bytes"`
			}
			if json.Unmarshal(b, &source) == nil {
				e.SourceURL = source.Provider
				e.SourceBytes = source.Bytes
				emitDEM(emit, DEMProgress{Stage: "vector acquired", Bytes: e.SourceBytes})
			}
		}
	} else {
		p.demCache = ""
		if s.path != ":memory:" {
			p.demCache = s.path + ".map-cache"
		}
		report := DEMReport{MapID: id, Revision: BuildRevision(), Bounds: chunk.Bounds, Centre: [2]float64{(chunk.Bounds[0] + chunk.Bounds[2]) / 2, (chunk.Bounds[1] + chunk.Bounds[3]) / 2}, AreaMetres: [2]float64{(chunk.Bounds[2] - chunk.Bounds[0]) * 111320 * math.Cos((chunk.Bounds[1]+chunk.Bounds[3])/2*math.Pi/180), (chunk.Bounds[3] - chunk.Bounds[1]) * 111320}, Policy: demPolicy, Started: started.UTC().Format(wireTime), Provider: "AWS Open Data Terrain Tiles", Dataset: "Mapzen Skadi composite / WGS84 / EGM96", Components: j.Components, Attempts: []DEMAttempt{}}
		var g terrainGrid
		g, err = p.acquireDEMReport(ctx, chunk.Bounds, j.Components, &report, emit)
		if err == nil || errors.Is(err, errDEMNoData) {
			for y := 0; y < g.Rows-1; y++ {
				for x := 0; x < g.Columns-1; x++ {
					a := y*g.Columns + x
					if g.Samples[a] != nil && g.Samples[a+1] != nil && g.Samples[a+g.Columns] != nil && g.Samples[a+g.Columns+1] != nil {
						e.ValidCells++
						step := g.ArcSeconds / 3600
						w, n := g.Bounds[0]+float64(x)*step, g.Bounds[3]-float64(y)*step
						if e.ValidBounds == nil {
							e.ValidBounds = []float64{w, n - step, w + step, n}
						} else {
							e.ValidBounds[0] = math.Min(e.ValidBounds[0], w)
							e.ValidBounds[1] = math.Min(e.ValidBounds[1], n-step)
							e.ValidBounds[2] = math.Max(e.ValidBounds[2], w+step)
							e.ValidBounds[3] = math.Max(e.ValidBounds[3], n)
						}
					} else {
						e.VoidCells++
					}
				}
			}
			meta := g
			meta.Samples = nil
			e.Metadata = &meta
			if err == nil {
				b, err = encodeTerrain(g)
			}
		}
		if len(report.Attempts) > 0 {
			report.Stage = report.Attempts[len(report.Attempts)-1].Stage
		}
		report.ElapsedMS = *milliseconds(time.Since(started))
		report.Outcome = "complete"
		if err != nil {
			report.Outcome = "failed"
			report.Error = err.Error()
		}
		e.Report = &report
		e.SourceURL = p.dem
		for _, attempt := range report.Attempts {
			if attempt.CompressedBytes != nil {
				e.SourceBytes += *attempt.CompressedBytes
			}
		}
	}
	e.SourceAttempts = budget.attempts
	afterTransfer, _ := s.transferState(id)
	e.SourceBytes = afterTransfer.Received - beforeTransfer.Received
	e.ElapsedMS = float64(time.Since(started)) / float64(time.Millisecond)
	if err == nil {
		err = ctx.Err()
	}
	if err == nil {
		e.Status = "complete"
		e.Error = ""
		e.At = s.Now().UTC().Format(wireTime)
		e.Bytes = len(b)
		emitDEM(emit, DEMProgress{Stage: "validating and saving verified " + layer + " chunk"})
		saveCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		err = s.writeChunk(saveCtx, id, key, layer, b, e)
		cancel()
	}
	if err != nil {
		e.Status = "failed; retry safely"
		if errors.Is(err, errDEMNoData) {
			e.Status = "source acquired; DEM unavailable (voids)"
		}
		e.Error = err.Error()
		e.Bytes = 0
		recovery, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_ = s.writeChunk(recovery, id, key, layer, nil, e)
		cancel()
	}
	return err
}
func (s *Store) mapJobRequest(w http.ResponseWriter, r *http.Request, p *mapProvider) bool {
	path := r.URL.Path
	global := path == "/local/map-plan" || path == "/local/map-jobs"
	parts := strings.Split(strings.TrimPrefix(path, "/local/maps/"), "/")
	local := strings.HasPrefix(path, "/local/maps/") && ((len(parts) == 2 && parts[1] == "manifest") || (len(parts) == 3 && parts[1] == "chunks"))
	if !global && !local {
		return false
	}
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == "GET" && local {
		j, err := s.readMapJob(parts[0])
		if err != nil {
			failure(w, 404, "map_unavailable", err)
			return true
		}
		if parts[1] == "manifest" {
			chunks, err := s.mapChunkStatuses(parts[0], j)
			if err != nil {
				failure(w, 503, "storage_unavailable", err)
			} else {
				respond(w, 200, map[string]any{"transfer": j.Transfer, "plan": j.Plan, "components": j.Components, "chunks": chunks, "attribution": j.Attribution, "coverage_note": "Chunk envelopes are conservative. No geographic percentage inferred from cells or sparse features. DEM valid cells exclude voids; contour/hillshade rendering availability is reported by the browser."})
			}
			return true
		}
		layer := r.URL.Query().Get("layer")
		if layer != "vector" && layer != "terrain" {
			failure(w, 400, "invalid_layer", errors.New("choose vector or terrain"))
			return true
		}
		var b []byte
		db := s.viewDB
		var unlock func()
		if db == nil {
			s.mu.Lock()
			unlock = s.mu.Unlock
			db = s.db
		} else {
			unlock = func() {}
		}
		readCtx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		err = db.QueryRowContext(readCtx, `SELECT `+layer+` FROM offline_map_chunks WHERE map_id=? AND chunk_key=?`, parts[0], parts[2]).Scan(&b)
		cancel()
		unlock()
		if err != nil || len(b) == 0 {
			failure(w, 404, "layer_unavailable", errors.New("chunk not acquired"))
		} else if layer == "terrain" {
			if r.URL.Query().Get("format") == "gterrain" {
				w.Header().Set("Content-Type", "application/octet-stream")
				w.Write(b)
				return true
			}
			g, e := decodeTerrain(b)
			if e != nil {
				failure(w, 503, "invalid_terrain", e)
			} else {
				respond(w, 200, g)
			}
		} else {
			w.Header().Set("Content-Type", "application/geo+json")
			w.Write(b)
		}
		return true
	}
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed", errors.New("use GET or POST"))
		return true
	}
	if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
		failure(w, 403, "forbidden", errors.New("same-origin controls only"))
		return true
	}
	if strings.Split(r.Header.Get("Content-Type"), ";")[0] != "application/json" {
		failure(w, 415, "unsupported_media_type", errors.New("use application/json"))
		return true
	}
	var in struct {
		mapCircle
		Components map[string]bool `json:"components"`
		Confirmed  bool            `json:"confirmed"`
		Layer      string          `json:"layer"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 65536)).Decode(&in); err != nil {
		failure(w, 400, "invalid_map", err)
		return true
	}
	if global {
		if path == "/local/map-plan" {
			plan, err := planCircle(in.mapCircle)
			if err != nil {
				failure(w, 400, "invalid_area", err)
			} else {
				respond(w, 200, plan)
			}
		} else {
			m, err := s.createMapJob(in.mapCircle, in.Components, in.Confirmed)
			if err != nil {
				failure(w, 400, "invalid_map", err)
			} else {
				respond(w, 200, m)
			}
		}
		return true
	}
	if len(parts) != 3 {
		failure(w, 405, "method_not_allowed", errors.New("manifest is read only"))
		return true
	}
	if in.Layer != "vector" && in.Layer != "terrain" {
		failure(w, 400, "invalid_layer", errors.New("choose vector or terrain"))
		return true
	}
	if !terrainPreparation.TryLock() {
		failure(w, 409, "map_busy", errors.New("one bounded map preparation at a time; retry after current request"))
		return true
	}
	defer terrainPreparation.Unlock()
	controller := http.NewResponseController(w)
	_ = controller.SetWriteDeadline(time.Now().Add(10*time.Minute + 30*time.Second))
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Minute)
	defer cancel()
	stream := strings.Contains(r.Header.Get("Accept"), "application/x-ndjson")
	emit := func(progress DEMProgress) {
		if stream {
			w.Header().Set("Content-Type", "application/x-ndjson")
			if json.NewEncoder(w).Encode(progress) != nil || controller.Flush() != nil {
				cancel()
			}
		}
	}
	err := s.acquireMapChunk(ctx, p, parts[0], parts[2], in.Layer, emit)
	if stream {
		stage := "complete"
		message := ""
		if err != nil {
			stage = "failed"
			message = err.Error()
		}
		emit(DEMProgress{Stage: stage, Error: message})
	} else if err != nil {
		if errors.Is(err, errMapConsent) {
			transfer, _ := s.transferState(parts[0])
			respond(w, http.StatusConflict, map[string]any{"error": "transfer_approval_required", "message": err.Error(), "map_id": parts[0], "chunk_id": parts[2], "layer": in.Layer, "transfer": transfer})
		} else {
			failure(w, 502, "chunk_acquisition_failed", err)
		}
	} else {
		respond(w, 200, map[string]bool{"complete": true})
	}
	return true
}

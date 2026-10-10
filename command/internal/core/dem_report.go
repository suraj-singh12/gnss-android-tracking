package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"time"
)

// Streaming is the existing bounded request, not a job queue/background worker.
// The browser aborts the request to cancel; ingestion deadlines stay unchanged.
func (s *Store) downloadTerrainResponse(w http.ResponseWriter, r *http.Request, p *mapProvider, id string, bounds [4]float64, components map[string]bool) {
	started := time.Now()
	report := DEMReport{Revision: BuildRevision(), Provider: "AWS Open Data Terrain Tiles", Dataset: "Mapzen Skadi composite / WGS84 / EGM96", MapID: id, Started: started.UTC().Format(time.RFC3339), Bounds: bounds, Centre: [2]float64{(bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2}, Components: components, Attempts: []DEMAttempt{}, Policy: "TCP connection 10s; TLS 10s; response headers 20s; each tile attempt 120s; overall acquisition 6min; bounded processing 10s; save 5s; at most 3 attempts/tile, backoff 1s/2s, Retry-After <=30s respected; null measurements unavailable", Stage: "connecting"}
	stream := r.Header.Get("Accept") == "application/x-ndjson"
	report.AreaMetres = [2]float64{(bounds[2] - bounds[0]) * 111320 * math.Cos(report.Centre[1]*math.Pi/180), (bounds[3] - bounds[1]) * 111320}
	if stream {
		w.Header().Set("Content-Type", "application/x-ndjson")
	}
	controller := http.NewResponseController(w)
	// Narrow route-only extension; not a blanket increase to ingestion/SOS APIs.
	_ = controller.SetWriteDeadline(started.Add(demOverallBudget + 30*time.Second))
	var streamErr error
	ctx, cancel := context.WithTimeout(r.Context(), demOverallBudget)
	defer cancel()
	emit := func(event DEMProgress) {
		report.Stage = event.Stage
		if !stream || streamErr != nil {
			return
		}
		w.Header().Set("Content-Type", "application/x-ndjson")
		streamErr = json.NewEncoder(w).Encode(event)
		if streamErr == nil {
			streamErr = controller.Flush()
		}
		if streamErr != nil {
			cancel()
		}
	}
	g, err := p.acquireDEMReport(ctx, bounds, components, &report, emit)
	var encoded []byte
	if err == nil {
		emit(DEMProgress{Stage: "processing terrain"})
		encodeStarted := time.Now()
		encoded, err = encodeTerrain(g)
		processing := time.Since(encodeStarted)
		if report.ProcessingMS != nil {
			processing += time.Duration(*report.ProcessingMS * float64(time.Millisecond))
		}
		report.ProcessingMS = milliseconds(processing)
		if processing > demProcessingBudget {
			err = errors.New("terrain processing deadline exceeded")
		}
	}
	if err == nil {
		err = ctx.Err()
	}
	if err == nil {
		emit(DEMProgress{Stage: "saving"})
		saveStart := time.Now()
		saveCtx, saveCancel := context.WithTimeout(ctx, 5*time.Second)
		s.mu.Lock()
		var total int
		err = s.db.QueryRowContext(saveCtx, `SELECT coalesce(sum(length(data)),0) FROM offline_terrain WHERE map_id<>?`, id).Scan(&total)
		if err == nil && total+len(encoded) > 16*1024*1024 {
			err = errors.New("terrain library exceeds 16 MB")
		}
		if err == nil {
			_, err = s.db.ExecContext(saveCtx, `INSERT INTO offline_terrain(map_id,data) VALUES(?,?) ON CONFLICT(map_id) DO UPDATE SET data=excluded.data`, id, encoded)
		}
		s.mu.Unlock()
		saveCancel()
		report.SaveMS = milliseconds(time.Since(saveStart))
		if err != nil {
			err = fmt.Errorf("local terrain save failed: %w", err)
		} else {
			p.mu.Lock()
			p.prepared = nil
			p.mu.Unlock()
		}
	}
	report.ElapsedMS = *milliseconds(time.Since(started))
	report.Outcome = "ready"
	if err == nil {
		report.Stage = "ready"
	}
	if err != nil {
		report.Outcome = "failed"
		report.Error = err.Error()
		if errors.Is(err, context.Canceled) {
			report.Outcome = "cancelled"
		}
	}
	// Journal this final report even when the client cancelled the transfer.
	// No provider I/O or long computation occurs while the Store mutex is held.
	s.mu.Lock()
	tx, journalErr := s.db.Begin()
	if journalErr == nil {
		journalErr = writeEvidence(tx, Evidence{At: s.Now().UTC().Format(wireTime), Kind: "terrain_download", Download: &report})
		if journalErr == nil {
			_, journalErr = tx.Exec(`DELETE FROM field_evidence WHERE ordinal IN (SELECT ordinal FROM field_evidence WHERE json_extract(data,'$.kind')='terrain_download' ORDER BY ordinal DESC LIMIT -1 OFFSET 20)`)
		}
		if journalErr == nil {
			journalErr = tx.Commit()
		} else {
			tx.Rollback()
		}
	}
	s.mu.Unlock()
	if journalErr != nil {
		report.Error += " (download report could not be persisted)"
	}
	if stream {
		event := DEMProgress{Stage: report.Outcome, Report: &report, Error: report.Error}
		if streamErr == nil {
			json.NewEncoder(w).Encode(event)
			controller.Flush()
		}
	} else if err != nil {
		failure(w, 400, "terrain_download_failed", err)
	} else {
		respond(w, 200, g)
	}
}

func (s *Store) downloadReport(w http.ResponseWriter, id string) {
	s.mu.Lock()
	var data string
	err := s.db.QueryRow(`SELECT data FROM field_evidence WHERE json_extract(data,'$.kind')='terrain_download' AND json_extract(data,'$.terrain_download.map_id')=? ORDER BY ordinal DESC LIMIT 1`, id).Scan(&data)
	s.mu.Unlock()
	if err != nil {
		failure(w, 404, "report_unavailable", errors.New("no download report for this map"))
		return
	}
	var e Evidence
	if err = json.Unmarshal([]byte(data), &e); err != nil {
		failure(w, 503, "report_unavailable", err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Disposition", `attachment; filename="terrain-download-report.json"`)
	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "  ")
	encoder.Encode(e.Download)
}

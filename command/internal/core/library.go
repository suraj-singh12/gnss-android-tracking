package core

// Library metadata is intentionally separate from sealed #27 receipts. Deletion
// retires library visibility and releases snapshot-owned rows and unshared pins.
import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
)

func (s *Store) libraryHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == "GET" {
		rows, err := s.db.QueryContext(r.Context(), `SELECT r.metadata,l.metadata,(SELECT COUNT(*) FROM saved_presentation WHERE snapshot=r.id AND json_extract(data,'$.Kind') IN ('pause','browser_unavailable','command_unavailable')),COALESCE(json_extract(r.metadata,'$.snapshot_status'),(SELECT json_extract(data,'$.Data.status') FROM saved_presentation WHERE snapshot=r.id AND json_extract(data,'$.Kind') IN ('start','pause','resume','stop') ORDER BY ordinal DESC LIMIT 1),'unknown') FROM saved_recordings r LEFT JOIN saved_library l ON l.id=r.id WHERE COALESCE(l.deleted,0)=0 ORDER BY r.rowid DESC`)
		if err != nil {
			failure(w, 503, "storage_unavailable", err)
			return
		}
		defer rows.Close()
		out := []map[string]any{}
		for rows.Next() {
			var original string
			var overlay sql.NullString
			var interruptions int
			var status string
			if err = rows.Scan(&original, &overlay, &interruptions, &status); err != nil {
				break
			}
			var m map[string]any
			if err = json.Unmarshal([]byte(original), &m); err != nil {
				break
			}
			if overlay.Valid {
				var descriptive map[string]any
				if err = json.Unmarshal([]byte(overlay.String), &descriptive); err != nil {
					break
				}
				for k, v := range descriptive {
					m[k] = v
				}
			}
			m["captured_interruptions"] = interruptions
			m["snapshot_status"] = status
			out = append(out, m)
		}
		if err == nil {
			err = rows.Err()
		}
		if err != nil {
			failure(w, 503, "storage_unavailable", err)
			return
		}
		respond(w, 200, out)
		return
	}
	if r.Method != "POST" || (r.Header.Get("Origin") != "http://"+r.Host && r.Header.Get("Origin") != "https://"+r.Host) || !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") || r.Header.Get("Sec-Fetch-Site") == "cross-site" {
		failure(w, 403, "same_origin_required", errors.New("same-origin JSON required"))
		return
	}
	var req struct {
		ID          string `json:"id"`
		Action      string `json:"action"`
		Name        string `json:"name"`
		Description string `json:"description"`
		DisplayDate string `json:"display_date"`
		Confirmed   bool   `json:"confirmed"`
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil {
		failure(w, 400, "invalid_request", err)
		return
	}
	if req.Action != "edit" && req.Action != "delete" || req.Action == "delete" && !req.Confirmed || req.Action == "edit" && (strings.TrimSpace(req.Name) == "" || len(req.Name) > 256 || len(req.Description) > 8192 || len(req.DisplayDate) > 256) {
		failure(w, 400, "invalid_metadata", errors.New("valid edit or confirmed delete required"))
		return
	}
	s.saveMu.Lock()
	defer s.saveMu.Unlock()
	s.mu.Lock()
	locked := true
	defer func() {
		if locked {
			s.mu.Unlock()
		}
	}()
	tx, err := s.db.BeginTx(r.Context(), nil)
	if err != nil {
		failure(w, 503, "storage_unavailable", err)
		return
	}
	defer tx.Rollback()
	var exists string
	err = tx.QueryRow(`SELECT id FROM saved_recordings WHERE id=? AND NOT EXISTS(SELECT 1 FROM saved_library WHERE id=? AND deleted=1)`, req.ID, req.ID).Scan(&exists)
	if err != nil {
		failure(w, 404, "snapshot_not_found", err)
		return
	}
	deleted := 0
	if req.Action == "delete" {
		deleted = 1
	}
	metadata := marshal(map[string]string{"name": strings.TrimSpace(req.Name), "description": req.Description, "display_date": req.DisplayDate})
	_, err = tx.Exec(`INSERT INTO saved_library VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET metadata=excluded.metadata,deleted=excluded.deleted`, req.ID, metadata, deleted)
	if err == nil {
		_, err = tx.Exec(`INSERT INTO saved_library_revisions SELECT ?,COALESCE(MAX(revision),0)+1,?,?,? FROM saved_library_revisions WHERE id=?`, req.ID, metadata, deleted, s.Now().UTC().Format("2006-01-02T15:04:05.000000000Z"), req.ID)
	}
	if err == nil {
		err = tx.Commit()
	}
	if err != nil {
		failure(w, 503, "storage_unavailable", err)
		return
	}
	s.mu.Unlock()
	locked = false
	if deleted == 1 {
		if e := s.reclaimSnapshot(req.ID); e != nil {
			respond(w, 200, map[string]any{"committed": true, "cleanup_pending": true, "cleanup_error": e.Error()})
			return
		}
	}
	respond(w, 200, map[string]bool{"committed": true})
}

var errSnapshotAssetsInUse = errors.New("snapshot removed; asset cleanup waits for the live view or current recording to release its pins")

// Candidates are captured durably before removing snapshot references. Cleanup
// never sweeps unrelated response pins or blobs, and resumes safely after death.
// saveMu protects Save staging; each short mu section yields to phone receipt.
func (s *Store) reclaimSnapshot(sid string) error {
	s.mu.Lock()
	tx, e := s.db.Begin()
	if e == nil {
		var n int
		e = tx.QueryRow(`SELECT COUNT(*) FROM saved_gc_phase WHERE snapshot=?`, sid).Scan(&n)
		if e == nil && n == 0 {
			_, e = tx.Exec(`INSERT OR IGNORE INTO saved_gc_candidates SELECT snapshot,hash,'asset' FROM saved_assets WHERE snapshot=?`, sid)
			if e == nil {
				_, e = tx.Exec(`INSERT OR IGNORE INTO saved_gc_candidates WITH RECURSIVE owned(hash) AS (
      SELECT json_extract(data,'$.Data.hash') FROM saved_presentation WHERE snapshot=? AND json_extract(data,'$.Kind')='display'
      UNION SELECT l.base FROM presentation_blob_links l JOIN owned r ON l.hash=r.hash WHERE l.base!=''
     ) SELECT ?,hash,'blob' FROM owned WHERE hash IS NOT NULL`, sid, sid)
			}
			if e == nil {
				_, e = tx.Exec(`INSERT INTO saved_gc_phase VALUES(?,0)`, sid)
			}
		}
		if e == nil {
			e = tx.Commit()
		} else {
			tx.Rollback()
		}
	}
	s.mu.Unlock()
	if e != nil {
		return e
	}
	for _, table := range []string{"saved_visibility", "saved_visibility_ready", "saved_presentation", "saved_originals", "saved_assets"} {
		for {
			s.mu.Lock()
			result, e := s.db.Exec("DELETE FROM "+table+" WHERE rowid IN (SELECT rowid FROM "+table+" WHERE snapshot=? LIMIT 256)", sid)
			s.mu.Unlock()
			if e != nil {
				return e
			}
			n, e := result.RowsAffected()
			if e != nil {
				return e
			}
			if n == 0 {
				break
			}
		}
	}
	for {
		s.mu.Lock()
		result, e := s.db.Exec(`DELETE FROM presentation_assets WHERE hash IN (
   SELECT c.hash FROM saved_gc_candidates c WHERE snapshot=? AND kind='asset'
   AND NOT EXISTS(SELECT 1 FROM saved_assets WHERE hash=c.hash)
   AND NOT EXISTS(SELECT 1 FROM presentation_asset_live WHERE hash=c.hash)
   AND NOT EXISTS(SELECT 1 FROM presentation_asset_refs WHERE hash=c.hash AND recording=(SELECT json_extract(data,'$.recording.recording_id') FROM state WHERE id=1)) LIMIT 64)`, sid)
		s.mu.Unlock()
		if e != nil {
			return e
		}
		n, e := result.RowsAffected()
		if e != nil {
			return e
		}
		if n == 0 {
			break
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, e = s.db.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var stage int
	if e = tx.QueryRow(`SELECT stage FROM saved_gc_phase WHERE snapshot=?`, sid).Scan(&stage); e != nil {
		return e
	}
	if stage == 0 {
		_, e = tx.Exec(`INSERT OR IGNORE INTO presentation_gc WITH RECURSIVE live(hash) AS (
   SELECT json_extract(data,'$.hash') FROM presentation_journal WHERE kind='display'
   UNION SELECT json_extract(data,'$.Data.hash') FROM saved_presentation WHERE json_extract(data,'$.Kind')='display'
   UNION SELECT l.base FROM presentation_blob_links l JOIN live r ON l.hash=r.hash WHERE l.base!=''
   ) SELECT hash FROM saved_gc_candidates WHERE snapshot=? AND kind='blob' AND hash NOT IN (SELECT hash FROM live WHERE hash IS NOT NULL)`, sid)
		if e != nil {
			return e
		}
		for _, q := range []string{`DELETE FROM presentation_blobs WHERE hash IN (SELECT hash FROM presentation_gc)`, `DELETE FROM presentation_blob_links WHERE hash IN (SELECT hash FROM presentation_gc)`, `DELETE FROM presentation_gc`, `UPDATE presentation_usage SET bytes=(SELECT COALESCE(SUM(length(data)),0) FROM presentation_blobs) WHERE id=1`} {
			if _, e = tx.Exec(q); e != nil {
				return e
			}
		}
		if _, e = tx.Exec(`UPDATE saved_gc_phase SET stage=1 WHERE snapshot=?`, sid); e != nil {
			return e
		}
	}
	var pending int
	// Other saved recordings take over ownership; live/unsaved use postpones final
	// reclamation rather than erasing bytes still needed by an open browser.
	e = tx.QueryRow(`SELECT COUNT(*) FROM saved_gc_candidates c JOIN presentation_assets a ON a.hash=c.hash WHERE c.snapshot=? AND c.kind='asset' AND NOT EXISTS(SELECT 1 FROM saved_assets WHERE hash=c.hash)`, sid).Scan(&pending)
	if e != nil {
		return e
	}
	if pending == 0 {
		if _, e = tx.Exec(`INSERT OR IGNORE INTO saved_gc_done VALUES(?)`, sid); e != nil {
			return e
		}
		if _, e = tx.Exec(`DELETE FROM saved_gc_candidates WHERE snapshot=?`, sid); e != nil {
			return e
		}
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	if pending != 0 {
		return errSnapshotAssetsInUse
	}
	return nil
}

package core

// Library metadata is intentionally separate from sealed #27 receipts. Deletion
// retires library visibility; sealed evidence and shared pins remain immutable.
import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
)

func (s *Store) libraryHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == "GET" {
		rows, err := s.db.QueryContext(r.Context(), `SELECT r.metadata,l.metadata FROM saved_recordings r LEFT JOIN saved_library l ON l.id=r.id WHERE COALESCE(l.deleted,0)=0 ORDER BY r.rowid DESC`)
		if err != nil {
			failure(w, 503, "storage_unavailable", err)
			return
		}
		defer rows.Close()
		out := []map[string]any{}
		for rows.Next() {
			var original string
			var overlay sql.NullString
			if err = rows.Scan(&original, &overlay); err != nil {
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
	s.mu.Lock()
	defer s.mu.Unlock()
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
		err = tx.Commit()
	}
	if err != nil {
		failure(w, 503, "storage_unavailable", err)
		return
	}
	respond(w, 200, map[string]bool{"committed": true})
}

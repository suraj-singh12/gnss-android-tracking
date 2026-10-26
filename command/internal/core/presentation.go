package core

// Presentation is evidence supplied by the exclusive local browser, not another
// GNSS authority. All tables live in the existing FULL/WAL Command database.
import (
	"bytes"
	"compress/zlib"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const presentationLimit = 4 << 20
const presentationQuota = 1 << 30

type presentationLease struct {
	LastDisplay time.Time `json:"last_display_at"`
	Token       string    `json:"token"`
	Epoch       string    `json:"epoch"`
	Seen        time.Time `json:"seen_at"`
	Closed      bool      `json:"closed"`
	Revision    int64     `json:"revision"`
}
type displayState struct {
	Version int                        `json:"version"`
	Common  json.RawMessage            `json:"common"`
	Parties map[string]json.RawMessage `json:"parties"`
	MapID   string                     `json:"map_id"`
	Assets  []string                   `json:"assets"`
}
type presentationRequest struct {
	Reason      string       `json:"reason"`
	From        string       `json:"from"`
	Until       string       `json:"until"`
	OperatorAt  string       `json:"operator_at"`
	RequestID   string       `json:"request_id"`
	Token       string       `json:"token"`
	Epoch       string       `json:"epoch"`
	Revision    int64        `json:"revision"`
	Presented   string       `json:"presented_at"`
	Opportunity string       `json:"opportunity_at"`
	Action      string       `json:"action"`
	State       displayState `json:"state"`
	Name        string       `json:"name"`
	Description string       `json:"description"`
	DisplayDate string       `json:"display_date"`
	Included    []string     `json:"included"`
	Confirmed   bool         `json:"confirmed"`
}

func marshal(v any) string   { b, _ := json.Marshal(v); return string(b) }
func digest(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func compressed(b []byte) []byte {
	var out bytes.Buffer
	w := zlib.NewWriter(&out)
	_, _ = w.Write(b)
	_ = w.Close()
	return out.Bytes()
}
func (s *Store) initPresentation() error {
	for _, q := range []string{
		`CREATE TABLE IF NOT EXISTS presentation_runtime(id INTEGER PRIMARY KEY CHECK(id=1),last_alive TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS presentation_participants(recording TEXT NOT NULL,device TEXT NOT NULL,party TEXT NOT NULL,PRIMARY KEY(recording,device))`,
		`CREATE TABLE IF NOT EXISTS presentation_usage(id INTEGER PRIMARY KEY CHECK(id=1),bytes INTEGER NOT NULL)`,

		`CREATE TABLE IF NOT EXISTS presentation_requests(request TEXT PRIMARY KEY,snapshot TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS presentation_meta(id INTEGER PRIMARY KEY CHECK(id=1),lease TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS presentation_blobs(hash TEXT PRIMARY KEY, data BLOB NOT NULL, bytes INTEGER NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS presentation_journal(ordinal INTEGER PRIMARY KEY AUTOINCREMENT,recording TEXT NOT NULL,kind TEXT NOT NULL,at TEXT NOT NULL,epoch TEXT NOT NULL DEFAULT '',revision INTEGER NOT NULL DEFAULT 0,data TEXT NOT NULL,UNIQUE(epoch,revision,kind))`,
		`CREATE INDEX IF NOT EXISTS presentation_recording ON presentation_journal(recording,ordinal)`,
		`CREATE TABLE IF NOT EXISTS saved_recordings(id TEXT PRIMARY KEY,metadata TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS saved_presentation(snapshot TEXT NOT NULL,ordinal INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(snapshot,ordinal))`,
		`CREATE TABLE IF NOT EXISTS saved_originals(snapshot TEXT NOT NULL,device TEXT NOT NULL,message TEXT NOT NULL,wire BLOB NOT NULL,known TEXT NOT NULL,received TEXT NOT NULL,PRIMARY KEY(snapshot,device,message))`,
		`CREATE TABLE IF NOT EXISTS presentation_assets(hash TEXT PRIMARY KEY,data BLOB NOT NULL,bytes INTEGER NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS presentation_asset_refs(recording TEXT NOT NULL,ordinal INTEGER NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(recording,ordinal,hash))`,
		`INSERT OR IGNORE INTO presentation_usage SELECT 1,COALESCE(SUM(bytes),0) FROM presentation_blobs`,
		`CREATE TABLE IF NOT EXISTS saved_assets(snapshot TEXT NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(snapshot,hash))`,
	} {
		if _, e := s.db.Exec(q); e != nil {
			return e
		}
	}
	for _, table := range []string{"saved_recordings", "saved_presentation", "saved_originals", "saved_assets", "presentation_blobs", "presentation_assets", "presentation_journal"} {
		for _, op := range []string{"UPDATE", "DELETE"} {
			if _, e := s.db.Exec(fmt.Sprintf(`CREATE TRIGGER IF NOT EXISTS immutable_%s_%s BEFORE %s ON %s BEGIN SELECT RAISE(ABORT,'immutable recording evidence'); END`, table, op, op, table)); e != nil {
				return e
			}
		}
	}
	tx, e := s.db.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var old string
	e = tx.QueryRow(`SELECT lease FROM presentation_meta WHERE id=1`).Scan(&old)
	if e != nil && !errors.Is(e, sql.ErrNoRows) {
		return e
	}
	st, e := loadReceiptState(tx)
	if e != nil {
		return e
	}
	if st.Recording != nil {
		// Legacy stopped windows were resumable: migrate them to deliberate Pause.
		if st.Recording.Status == "" {
			if st.Recording.Active {
				st.Recording.Status = "active"
			} else {
				st.Recording.Status = "paused"
			}
			if len(st.Recording.Windows) > 0 {
				st.Recording.Started = st.Recording.Windows[0].Start
			}
			if e = saveReceiptState(tx, st); e != nil {
				return e
			}
		}
		if e = presentationParticipants(tx, st); e != nil {
			return e
		}
		var lastAlive string
		_ = tx.QueryRow(`SELECT last_alive FROM presentation_runtime WHERE id=1`).Scan(&lastAlive)
		if lastAlive != "" {
			if _, e = journal(tx, st.Recording.ID, "command_unavailable", s.Now(), "", 0, map[string]any{"from": lastAlive, "until": s.Now(), "boundary_uncertain": true, "reason": "last durable process heartbeat to restart; process death within heartbeat uncertainty"}); e != nil {
				return e
			}
		}
	}
	if _, e = tx.Exec(`INSERT INTO presentation_runtime VALUES(1,?) ON CONFLICT(id) DO UPDATE SET last_alive=excluded.last_alive`, s.Now().UTC().Format(time.RFC3339Nano)); e != nil {
		return e
	}
	_, e = tx.Exec(`INSERT INTO presentation_meta VALUES(1,?) ON CONFLICT(id) DO UPDATE SET lease=excluded.lease`, marshal(presentationLease{Epoch: id(), Seen: s.Now().UTC(), Closed: true}))
	if e != nil {
		return e
	}
	return tx.Commit()
}
func journal(tx *sql.Tx, recording, kind string, at time.Time, epoch string, rev int64, data any) (int64, error) {
	// Non-browser facts have unique epochs so multiple lifecycle actions can coexist.
	if epoch == "" {
		epoch = id()
	}
	r, e := tx.Exec(`INSERT INTO presentation_journal(recording,kind,at,epoch,revision,data) VALUES(?,?,?,?,?,?)`, recording, kind, at.UTC().Format("2006-01-02T15:04:05.000000000Z"), epoch, rev, marshal(data))
	if e != nil {
		return 0, e
	}
	return r.LastInsertId()
}
func presentationAction(tx *sql.Tx, r *Recording, action string, now, receivedAt time.Time) error {
	if r == nil {
		return nil
	}
	_, e := journal(tx, r.ID, action, now, "", 0, map[string]any{"status": r.Status, "mode": r.Mode, "operator_at": now, "operator_received_at": receivedAt, "presentation": "awaiting browser confirmation"})
	return e
}
func getLease(tx *sql.Tx) (presentationLease, error) {
	var l presentationLease
	var b string
	e := tx.QueryRow(`SELECT lease FROM presentation_meta WHERE id=1`).Scan(&b)
	if e == nil {
		e = json.Unmarshal([]byte(b), &l)
	}
	return l, e
}
func putLease(tx *sql.Tx, l presentationLease) error {
	_, e := tx.Exec(`UPDATE presentation_meta SET lease=? WHERE id=1`, marshal(l))
	return e
}
func owner(l presentationLease, r presentationRequest, now time.Time) bool {
	return l.Token != "" && r.Token == l.Token && r.Epoch == l.Epoch && !l.Closed && now.Sub(l.Seen) < 15*time.Second
}
func (s *Store) presentationHTTP(w http.ResponseWriter, r *http.Request) bool {
	if !strings.HasPrefix(r.URL.Path, "/local/presentation/") {
		return false
	}
	if r.Method == "GET" {
		if r.URL.Path == "/local/presentation/saved" {
			s.savedDiagnostics(w, r)
			return true
		}
		failure(w, 405, "method_not_allowed", errors.New("POST required"))
		return true
	}
	// Require browser Origin (unlike compatibility local control endpoints). JSON
	// plus Fetch Metadata prevents form/image/remote-site capture publication.
	if r.Method != "POST" || (r.Header.Get("Origin") != "http://"+r.Host && r.Header.Get("Origin") != "https://"+r.Host) || !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") || r.Header.Get("Sec-Fetch-Site") == "cross-site" {
		failure(w, 403, "forbidden", errors.New("same-origin browser required"))
		return true
	}
	var req presentationRequest
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, presentationLimit))
	dec.DisallowUnknownFields()
	if e := dec.Decode(&req); e != nil {
		failure(w, 400, "invalid_presentation", e)
		return true
	}
	result, e := s.presentationOperation(strings.TrimPrefix(r.URL.Path, "/local/presentation/"), req)
	if e != nil {
		failure(w, 409, "presentation_unconfirmed", e)
	} else {
		respond(w, 200, result)
	}
	return true
}
func (s *Store) presentationOperation(op string, r presentationRequest) (any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, e := s.db.Begin()
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	now := s.Now().UTC()
	l, e := getLease(tx)
	if e != nil {
		return nil, e
	}
	st, e := loadReceiptState(tx)
	if e != nil {
		return nil, e
	}
	var result any
	if op == "claim" {
		if !l.Closed && now.Sub(l.Seen) < 15*time.Second {
			return nil, errors.New("Command is occupied by another browser; close it or wait for its lease to expire")
		}
		if st.Recording != nil && st.Recording.Status != "stopped" {
			kind := "unverified"
			if l.Closed {
				kind = "browser_unavailable"
			}
			if _, e = journal(tx, st.Recording.ID, kind, now, "", 0, map[string]any{"from": l.Seen, "until": now, "boundary_uncertain": true, "reason": "ownership recovery; timeout alone cannot establish browser closure"}); e != nil {
				return nil, e
			}
		}
		l = presentationLease{Token: id() + id(), Epoch: id(), Seen: now}
		result = l
	} else {
		if !owner(l, r, now) {
			return nil, errors.New("browser ownership expired or fenced; reacquire before capture")
		}
		switch op {
		case "gap":
			if st.Recording == nil {
				return nil, ErrAction
			}
			_, e = journal(tx, st.Recording.ID, "unverified", now, "", 0, map[string]any{"from": r.From, "until": r.Until, "reason": r.Reason})
			result = map[string]any{"committed": true}
		case "heartbeat":
			l.Seen = now
			result = l
		case "close":
			l.Closed = true
			l.Seen = now
			if st.Recording != nil {
				_, e = journal(tx, st.Recording.ID, "browser_unavailable", now, "", 0, map[string]any{"from": now, "reason": "browser pagehide; refresh may immediately reconnect"})
			}
			result = map[string]any{"closed": true}
		case "confirm":
			if st.Recording == nil || !st.Recording.Active || st.Recording.Status == "stopped" {
				return nil, ErrAction
			}
			if r.Revision <= 0 || r.State.Version != 1 || len(r.State.Common) == 0 {
				return nil, errors.New("versioned applied display state required")
			}
			b := []byte(marshal(r.State))
			hash := digest(b)
			// Retry only the exact same immutable event; conflicting sequence reuse fails.
			var previous string
			err := tx.QueryRow(`SELECT data FROM presentation_journal WHERE epoch=? AND revision=? AND kind='display'`, r.Epoch, r.Revision).Scan(&previous)
			if err == nil {
				var p map[string]any
				_ = json.Unmarshal([]byte(previous), &p)
				if p["hash"] != hash {
					return nil, ErrConflict
				}
				result = p
				break
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return nil, err
			}
			if r.Revision <= l.Revision {
				return nil, errors.New("out-of-order presentation revision")
			}
			presented, err := time.Parse(time.RFC3339Nano, r.Presented)
			if err != nil {
				return nil, err
			}
			opportunity, err := time.Parse(time.RFC3339Nano, r.Opportunity)
			if err != nil || opportunity.Before(presented) || opportunity.After(now.Add(5*time.Second)) {
				return nil, errors.New("invalid browser rendering opportunity timestamps")
			}
			if presented.Before(st.Recording.Started) {
				return nil, errors.New("presentation predates Start")
			}
			if r.Revision != l.Revision+1 {
				_, e = journal(tx, st.Recording.ID, "unverified", now, "", 0, map[string]any{"reason": "missing browser revisions", "after_revision": l.Revision, "before_revision": r.Revision})
				if e != nil {
					return nil, e
				}
			}
			var used int64
			if e = tx.QueryRow(`SELECT bytes FROM presentation_usage WHERE id=1`).Scan(&used); e != nil {
				return nil, e
			}
			if used+int64(len(b)) > presentationQuota {
				return nil, errors.New("presentation quota reached; capture incomplete; GNSS remains available")
			}
			inserted, err := tx.Exec(`INSERT OR IGNORE INTO presentation_blobs VALUES(?,?,?)`, hash, compressed(b), len(b))
			e = err
			if e == nil {
				n, _ := inserted.RowsAffected()
				if n > 0 {
					_, e = tx.Exec(`UPDATE presentation_usage SET bytes=bytes+? WHERE id=1`, len(b))
				}
			}
			if e != nil {
				return nil, e
			}
			if !l.LastDisplay.IsZero() && presented.Sub(l.LastDisplay) > 5*time.Second {
				if _, e = journal(tx, st.Recording.ID, "unverified", now, "", 0, map[string]any{"from": l.LastDisplay, "until": presented, "reason": "no durable display confirmation in interval"}); e != nil {
					return nil, e
				}
			}
			l.LastDisplay = presented
			data := map[string]any{"hash": hash, "presented_at": r.Presented, "render_opportunity_at": r.Opportunity, "committed_at": now, "revision": r.Revision, "epoch": r.Epoch, "timing_uncertainty": "browser clock and rendering-opportunity bounds; no OS scanout or human attention claim"}
			var ordinal int64
			ordinal, e = journal(tx, st.Recording.ID, "display", now, r.Epoch, r.Revision, data)
			if e != nil {
				return nil, e
			}
			if e = pinPresentationMap(tx, st.Recording.ID, ordinal, r.State.MapID); e != nil {
				return nil, e
			}
			l.Revision = r.Revision
			l.Seen = now
			result = data
		case "save":
			if r.RequestID != "" {
				var metadata string
				err := tx.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=(SELECT snapshot FROM presentation_requests WHERE request=?)`, r.RequestID).Scan(&metadata)
				if err == nil {
					var v map[string]any
					_ = json.Unmarshal([]byte(metadata), &v)
					result = v
					break
				}
				if !errors.Is(err, sql.ErrNoRows) {
					return nil, err
				}
			}
			result, e = sealRecording(tx, st, r, now)
		default:
			return nil, errors.New("unknown presentation operation")
		}
	}
	if e != nil {
		return nil, e
	}
	if e = putLease(tx, l); e != nil {
		return nil, e
	}
	if e = tx.Commit(); e != nil {
		return nil, e
	}
	return result, nil
}
func pinPresentationMap(tx *sql.Tx, recording string, ordinal int64, mapID string) error {
	if mapID == "" {
		return nil
	}
	// Retain immutable bytes at confirmed presentation, before ordinary library edits.
	rows, e := tx.Query(`SELECT data FROM offline_maps WHERE id=? UNION ALL SELECT data FROM offline_terrain WHERE map_id=? UNION ALL SELECT vector FROM offline_map_chunks WHERE map_id=? AND vector IS NOT NULL UNION ALL SELECT terrain FROM offline_map_chunks WHERE map_id=? AND terrain IS NOT NULL`, mapID, mapID, mapID, mapID)
	if e != nil {
		return e
	}
	var assets [][]byte
	for rows.Next() {
		var b []byte
		if e = rows.Scan(&b); e != nil {
			break
		}
		assets = append(assets, b)
	}
	err := rows.Err()
	rows.Close()
	if e != nil {
		return e
	}
	if err != nil {
		return err
	}
	if len(assets) == 0 {
		_, e = journal(tx, recording, "missing_map_asset", time.Now(), "", 0, map[string]any{"map_id": mapID})
		return e
	}
	for _, b := range assets {
		h := digest(b)
		if _, e = tx.Exec(`INSERT OR IGNORE INTO presentation_assets VALUES(?,?,?)`, h, b, len(b)); e != nil {
			return e
		}
		if _, e = tx.Exec(`INSERT OR IGNORE INTO presentation_asset_refs VALUES(?,?,?)`, recording, ordinal, h); e != nil {
			return e
		}
	}
	return nil
}
func sealRecording(tx *sql.Tx, st State, r presentationRequest, now time.Time) (any, error) {
	rec := st.Recording
	if rec == nil {
		return nil, ErrAction
	}
	if strings.TrimSpace(r.Name) == "" || len(r.Name) > 200 || len(r.Description) > 16000 || len(r.Included) == 0 {
		return nil, errors.New("name and at least one participating party required")
	}
	selected := map[string]bool{}
	for _, d := range r.Included {
		var exists int
		_ = tx.QueryRow(`SELECT COUNT(*) FROM presentation_participants WHERE recording=? AND device=?`, rec.ID, d).Scan(&exists)
		if exists == 0 {
			return nil, errors.New("unknown included party")
		}
		selected[d] = true
	}
	sid := id()
	cutoff := now
	if r.OperatorAt != "" {
		at, e := time.Parse(time.RFC3339Nano, r.OperatorAt)
		if e != nil || at.After(now.Add(5*time.Second)) || now.Sub(at) > 30*time.Second {
			return nil, errors.New("invalid Save operator timestamp")
		}
		cutoff = at.UTC()
	}
	if rec.Stopped != nil {
		cutoff = *rec.Stopped
	}
	// Freeze manifest from all participants known by the boundary, not current UI visibility.
	manifest := map[string]any{}
	participants, e := tx.Query(`SELECT device,party FROM presentation_participants WHERE recording=? ORDER BY device`, rec.ID)
	if e != nil {
		return nil, e
	}
	for participants.Next() {
		var d, party string
		if e = participants.Scan(&d, &party); e != nil {
			participants.Close()
			return nil, e
		}
		manifest[d] = map[string]any{"party": json.RawMessage(party), "included": selected[d]}
	}
	if e = participants.Err(); e != nil {
		participants.Close()
		return nil, e
	}
	participants.Close()
	rows, e := tx.Query(`SELECT ordinal,kind,at,epoch,revision,data FROM presentation_journal WHERE recording=? AND at<=? ORDER BY ordinal`, rec.ID, cutoff.UTC().Format("2006-01-02T15:04:05.000000000Z"))
	if e != nil {
		return nil, e
	}
	type event struct {
		Ordinal         int64 `json:"ordinal"`
		Kind, At, Epoch string
		Revision        int64
		Data            json.RawMessage
	}
	defer rows.Close()
	confirmed := 0
	stream := sha256.New()
	streamBytes := 0
	for rows.Next() {
		var v event
		var encoded string
		if e = rows.Scan(&v.Ordinal, &v.Kind, &v.At, &v.Epoch, &v.Revision, &encoded); e != nil {
			return nil, e
		}
		v.Data = json.RawMessage(encoded)
		if v.Kind == "display" {
			var detail map[string]any
			_ = json.Unmarshal(v.Data, &detail)
			var packed []byte
			if e = tx.QueryRow(`SELECT data FROM presentation_blobs WHERE hash=?`, detail["hash"]).Scan(&packed); e != nil {
				return nil, e
			}
			rd, err := zlib.NewReader(bytes.NewReader(packed))
			if err != nil {
				return nil, err
			}
			b, err := io.ReadAll(io.LimitReader(rd, presentationLimit+1))
			rd.Close()
			if err != nil || len(b) > presentationLimit {
				return nil, errors.New("invalid checkpoint")
			}
			var display displayState
			if e = json.Unmarshal(b, &display); e != nil {
				return nil, e
			}
			for d := range display.Parties {
				if !selected[d] {
					delete(display.Parties, d)
				}
			}
			detail["state"] = display
			v.Data = json.RawMessage(marshal(detail))
			confirmed++
		}
		b := marshal(v)
		_, _ = stream.Write([]byte(b + "\n"))
		streamBytes += len(b) + 1
		if _, e = tx.Exec(`INSERT INTO saved_presentation VALUES(?,?,?)`, sid, v.Ordinal, b); e != nil {
			return nil, e
		}
	}
	if e = rows.Err(); e != nil {
		return nil, e
	}
	rows.Close()
	for d := range selected {
		if _, e = tx.Exec(`INSERT INTO saved_originals SELECT ?,device,message,wire,known,received FROM raw WHERE device=?`, sid, d); e != nil {
			return nil, e
		}
	}
	if _, e = tx.Exec(`INSERT INTO saved_assets SELECT DISTINCT ?,hash FROM presentation_asset_refs WHERE recording=? AND ordinal IN (SELECT ordinal FROM presentation_journal WHERE recording=? AND at<=?)`, sid, rec.ID, rec.ID, cutoff.UTC().Format("2006-01-02T15:04:05.000000000Z")); e != nil {
		return nil, e
	}
	var assetBytes, rawBytes int64
	if e = tx.QueryRow(`SELECT COALESCE(SUM(bytes),0) FROM presentation_assets WHERE hash IN (SELECT hash FROM saved_assets WHERE snapshot=?)`, sid).Scan(&assetBytes); e != nil {
		return nil, e
	}
	if e = tx.QueryRow(`SELECT COALESCE(SUM(length(wire)),0) FROM saved_originals WHERE snapshot=?`, sid).Scan(&rawBytes); e != nil {
		return nil, e
	}
	metadata := map[string]any{"version": 1, "id": sid, "recording_id": rec.ID, "name": strings.TrimSpace(r.Name), "description": r.Description, "display_date": r.DisplayDate, "source_mode": rec.Mode, "started_at": rec.Started, "cutoff_at": cutoff, "saved_at": now, "operator_save_at": r.OperatorAt, "stopped_at": rec.Stopped, "total_parties": len(manifest), "participation": manifest, "included": r.Included, "operator_selected_subset": len(selected) < len(manifest), "confirmed_events": confirmed, "stream_sha256": hex.EncodeToString(stream.Sum(nil)), "presentation_bytes": streamBytes, "original_bytes": rawBytes, "pinned_asset_bytes": assetBytes, "incomplete_evidence": true, "asset_revision_verification": "Library bytes pinned at display confirmation; concurrent library replacement versus already-loaded browser assets remains unverified.", "limitations": []string{"Rendering opportunities confirm browser-applied state, not OS pixel scanout or human attention.", "Unconfirmed transitions and process-death boundaries remain uncertain; no later GNSS backfill repairs presentation evidence."}}
	if _, e = tx.Exec(`INSERT INTO saved_recordings VALUES(?,?)`, sid, marshal(metadata)); e != nil {
		return nil, e
	}
	if r.RequestID != "" {
		if _, e = tx.Exec(`INSERT INTO presentation_requests VALUES(?,?)`, r.RequestID, sid); e != nil {
			return nil, e
		}
	}
	if _, e = journal(tx, rec.ID, "save", now, "", 0, metadata); e != nil {
		return nil, e
	}
	return metadata, nil
}
func (s *Store) savedDiagnostics(w http.ResponseWriter, r *http.Request) {
	db := s.viewDB
	if db == nil {
		db = s.db
	}
	if sid := r.URL.Query().Get("id"); sid != "" {
		var meta string
		if e := db.QueryRowContext(r.Context(), `SELECT metadata FROM saved_recordings WHERE id=?`, sid).Scan(&meta); e != nil {
			failure(w, 404, "snapshot_not_found", e)
			return
		}
		rows, e := db.QueryContext(r.Context(), `SELECT data FROM saved_presentation WHERE snapshot=? ORDER BY ordinal`, sid)
		if e != nil {
			failure(w, 503, "storage_unavailable", e)
			return
		}
		h := sha256.New()
		count := 0
		for rows.Next() {
			var b string
			if e = rows.Scan(&b); e != nil {
				break
			}
			_, _ = h.Write([]byte(b + "\n"))
			count++
		}
		err := rows.Err()
		rows.Close()
		if e != nil || err != nil {
			failure(w, 503, "storage_unavailable", errors.Join(e, err))
			return
		}
		var m map[string]any
		_ = json.Unmarshal([]byte(meta), &m)
		m["verified_stream_sha256"] = hex.EncodeToString(h.Sum(nil))
		m["stream_integrity_valid"] = m["stream_sha256"] == m["verified_stream_sha256"]
		m["events"] = count
		respond(w, 200, m)
		return
	}
	rows, e := db.QueryContext(r.Context(), `SELECT metadata FROM saved_recordings ORDER BY rowid DESC LIMIT 100`)
	if e != nil {
		failure(w, 503, "storage_unavailable", e)
		return
	}
	defer rows.Close()
	out := []json.RawMessage{}
	for rows.Next() {
		var b string
		if e = rows.Scan(&b); e != nil {
			break
		}
		out = append(out, json.RawMessage(b))
	}
	if e == nil {
		e = rows.Err()
	}
	if e != nil {
		failure(w, 503, "storage_unavailable", e)
		return
	}
	respond(w, 200, out)
}

func presentationParticipants(tx receiptSQL, st State) error {
	if st.Recording == nil || st.Recording.Status == "stopped" {
		return nil
	}
	for d, v := range st.Devices {
		if _, e := tx.Exec(`INSERT OR IGNORE INTO presentation_participants VALUES(?,?,?)`, st.Recording.ID, d, marshal(v.Snapshot.Party)); e != nil {
			return e
		}
	}
	return nil
}

// Independent process liveness: a backgrounded browser is not a stopped Command.
// Death is bounded by the last committed heartbeat, never assigned its timestamp
// with false precision. SQLite failure leaves the older uncertainty bound intact.
func (s *Store) presentationRuntime() {
	defer close(s.presentationDone)
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.stop:
			return
		case <-ticker.C:
			_, _ = s.db.Exec(`UPDATE presentation_runtime SET last_alive=? WHERE id=1`, time.Now().UTC().Format(time.RFC3339Nano))
		}
	}
}

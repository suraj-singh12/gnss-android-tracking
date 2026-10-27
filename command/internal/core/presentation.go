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
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const presentationLimit = 4 << 20
const presentationQuota = 64 << 30

type presentationLease struct {
	GapFrom     time.Time `json:"gap_from,omitempty"`
	GapKind     string    `json:"gap_kind,omitempty"`
	Hash        string    `json:"state_hash"`
	Recording   string    `json:"recording_id"`
	LastDisplay time.Time `json:"last_display_at"`
	Token       string    `json:"token"`
	Epoch       string    `json:"epoch"`
	Seen        time.Time `json:"seen_at"`
	Closed      bool      `json:"closed"`
	Revision    int64     `json:"revision"`
}
type displayState struct {
	AssetWarnings []string                   `json:"asset_warnings,omitempty"`
	Version       int                        `json:"version"`
	Common        json.RawMessage            `json:"common"`
	Parties       map[string]json.RawMessage `json:"parties"`
	MapID         string                     `json:"map_id"`
	Assets        []string                   `json:"assets"`
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
		`CREATE TABLE IF NOT EXISTS presentation_blob_links(hash TEXT PRIMARY KEY,base TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS presentation_gc(hash TEXT PRIMARY KEY)`,
		`CREATE TABLE IF NOT EXISTS presentation_blobs(hash TEXT PRIMARY KEY, data BLOB NOT NULL, bytes INTEGER NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS presentation_journal(ordinal INTEGER PRIMARY KEY AUTOINCREMENT,recording TEXT NOT NULL,kind TEXT NOT NULL,at TEXT NOT NULL,epoch TEXT NOT NULL DEFAULT '',revision INTEGER NOT NULL DEFAULT 0,data TEXT NOT NULL,UNIQUE(epoch,revision,kind))`,
		`CREATE INDEX IF NOT EXISTS presentation_recording ON presentation_journal(recording,ordinal)`,
		`CREATE TABLE IF NOT EXISTS saved_recordings(id TEXT PRIMARY KEY,metadata TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS saved_library(id TEXT PRIMARY KEY,metadata TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)`,
		`CREATE TABLE IF NOT EXISTS saved_gc_done(snapshot TEXT PRIMARY KEY)`,
		`CREATE TABLE IF NOT EXISTS saved_library_revisions(id TEXT,revision INTEGER,metadata TEXT,deleted INTEGER,at TEXT,PRIMARY KEY(id,revision))`,
		`CREATE TABLE IF NOT EXISTS saved_presentation(snapshot TEXT NOT NULL,ordinal INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(snapshot,ordinal))`,
		`DROP INDEX IF EXISTS saved_display_clock`,
		`CREATE INDEX IF NOT EXISTS saved_display_clock_v2 ON saved_presentation(snapshot,json_extract(data,'$.Kind'),julianday(COALESCE(json_extract(data,'$.Data.effective_at'),json_extract(data,'$.Data.presented_at'),json_extract(data,'$.Data.render_opportunity_at'),json_extract(data,'$.At'))),ordinal)`,
		`CREATE TABLE IF NOT EXISTS saved_originals(snapshot TEXT NOT NULL,device TEXT NOT NULL,message TEXT NOT NULL,wire BLOB NOT NULL,known TEXT NOT NULL,received TEXT NOT NULL,PRIMARY KEY(snapshot,device,message))`,
		`CREATE TABLE IF NOT EXISTS presentation_assets(hash TEXT PRIMARY KEY,data BLOB NOT NULL,bytes INTEGER NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS presentation_asset_refs(recording TEXT NOT NULL,ordinal INTEGER NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(recording,ordinal,hash))`,
		`INSERT INTO presentation_usage SELECT 1,COALESCE(SUM(length(data)),0) FROM presentation_blobs WHERE true ON CONFLICT(id) DO UPDATE SET bytes=excluded.bytes`,
		`CREATE TRIGGER IF NOT EXISTS presentation_blob_usage AFTER INSERT ON presentation_blobs BEGIN UPDATE presentation_usage SET bytes=bytes+length(NEW.data) WHERE id=1; END`,
		`CREATE TABLE IF NOT EXISTS saved_assets(snapshot TEXT NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(snapshot,hash))`,
		`CREATE INDEX IF NOT EXISTS saved_asset_hash ON saved_assets(hash)`,
		`CREATE INDEX IF NOT EXISTS recording_asset_hash ON presentation_asset_refs(hash,recording)`,
		`CREATE TRIGGER IF NOT EXISTS immutable_library_revision_update BEFORE UPDATE ON saved_library_revisions BEGIN SELECT RAISE(ABORT,'immutable metadata revision'); END`,
		`CREATE TRIGGER IF NOT EXISTS immutable_library_revision_delete BEFORE DELETE ON saved_library_revisions BEGIN SELECT RAISE(ABORT,'immutable metadata revision'); END`,
	} {
		if _, e := s.db.Exec(q); e != nil {
			return e
		}
	}
	for _, table := range []string{"saved_recordings", "saved_presentation", "saved_originals", "saved_assets", "presentation_blobs", "presentation_assets", "presentation_journal"} {
		for _, op := range []string{"UPDATE", "DELETE"} {
			if _, e := s.db.Exec(fmt.Sprintf("DROP TRIGGER IF EXISTS immutable_%s_%s", table, op)); e != nil {
				return e
			}
			condition := ""
			if strings.HasPrefix(table, "saved_") && table != "saved_recordings" {
				condition = " WHEN EXISTS(SELECT 1 FROM saved_recordings WHERE id=OLD.snapshot)"
				if op == "DELETE" {
					condition = " WHEN NOT EXISTS(SELECT 1 FROM saved_library WHERE id=OLD.snapshot AND deleted=1) AND EXISTS(SELECT 1 FROM saved_recordings WHERE id=OLD.snapshot)"
				}
			}
			if table == "presentation_blobs" && op == "DELETE" {
				condition = " WHEN NOT EXISTS(SELECT 1 FROM presentation_gc WHERE hash=OLD.hash)"
			}
			if table == "presentation_assets" && op == "DELETE" {
				condition = " WHEN EXISTS(SELECT 1 FROM saved_assets WHERE hash=OLD.hash) OR EXISTS(SELECT 1 FROM presentation_asset_refs WHERE hash=OLD.hash AND recording=(SELECT json_extract(data,'$.recording.recording_id') FROM state WHERE id=1))"
			}
			if _, e := s.db.Exec(fmt.Sprintf(`CREATE TRIGGER IF NOT EXISTS immutable_%s_%s BEFORE %s ON %s%s BEGIN SELECT RAISE(ABORT,'immutable recording evidence'); END`, table, op, op, table, condition)); e != nil {
				return e
			}
		}
	}
	for _, table := range []string{"saved_presentation", "saved_originals", "saved_assets"} {
		if _, e := s.db.Exec("DELETE FROM " + table + " WHERE NOT EXISTS(SELECT 1 FROM saved_recordings WHERE id=" + table + ".snapshot)"); e != nil {
			return e
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
	var priorLease presentationLease
	_ = json.Unmarshal([]byte(old), &priorLease)
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
			if alive, err := time.Parse(time.RFC3339Nano, lastAlive); err == nil && priorLease.Seen.After(alive) {
				lastAlive = priorLease.Seen.Format(time.RFC3339Nano)
			}
			if priorLease.Closed && priorLease.Token != "" {
				if end, err := time.Parse(time.RFC3339Nano, lastAlive); err == nil && !end.Before(priorLease.Seen) {
					if _, e = journal(tx, st.Recording.ID, "browser_unavailable", s.Now(), "", 0, map[string]any{"from": priorLease.Seen, "until": end, "boundary_uncertain": true, "reason": "known pagehide before Command last durable heartbeat"}); e != nil {
						return e
					}
				}
			}
			if _, e = journal(tx, st.Recording.ID, "command_unavailable", s.Now(), "", 0, map[string]any{"from": lastAlive, "until": s.Now(), "boundary_uncertain": true, "reason": "last durable process heartbeat to restart; process death within heartbeat uncertainty"}); e != nil {
				return e
			}
		}
	}
	if _, e = tx.Exec(`INSERT INTO presentation_runtime VALUES(1,?) ON CONFLICT(id) DO UPDATE SET last_alive=excluded.last_alive`, s.Now().UTC().Format(time.RFC3339Nano)); e != nil {
		return e
	}
	_, e = tx.Exec(`INSERT INTO presentation_meta VALUES(1,?) ON CONFLICT(id) DO UPDATE SET lease=excluded.lease`, marshal(presentationLease{Epoch: id(), Seen: s.Now().UTC(), Closed: priorLease.Closed}))
	if e != nil {
		return e
	}
	return tx.Commit()
}
func journal(tx interface {
	Exec(string, ...any) (sql.Result, error)
}, recording, kind string, at time.Time, epoch string, rev int64, data any) (int64, error) {
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
	return l.Token != "" && r.Token == l.Token && r.Epoch == l.Epoch && !l.Closed && now.Sub(l.Seen) >= 0 && now.Sub(l.Seen) < 15*time.Second
}
func (s *Store) presentationHTTP(w http.ResponseWriter, r *http.Request) bool {
	if !strings.HasPrefix(r.URL.Path, "/local/presentation/") {
		return false
	}
	host := r.Host
	if name, _, err := net.SplitHostPort(host); err == nil {
		host = name
	}
	host = strings.Trim(host, "[]")
	if !strings.EqualFold(host, "localhost") && net.ParseIP(host) == nil {
		failure(w, 403, "untrusted_dashboard_host", errors.New("Open Command using localhost or its IP address; arbitrary hostnames cannot access presentation evidence"))
		return true
	}
	if r.URL.Path == "/local/presentation/playback" {
		s.playbackHTTP(w, r)
		return true
	}
	if r.URL.Path == "/local/presentation/library" {
		s.libraryHTTP(w, r)
		return true
	}
	if r.Method == "GET" {
		if r.URL.Path == "/local/presentation/participants" {
			db := s.viewDB
			if db == nil {
				db = s.db
			}
			rows, e := db.QueryContext(r.Context(), `SELECT device,party FROM presentation_participants WHERE recording=? ORDER BY device`, r.URL.Query().Get("recording_id"))
			if e != nil {
				failure(w, 503, "storage_unavailable", e)
				return true
			}
			defer rows.Close()
			parties := map[string]json.RawMessage{}
			for rows.Next() {
				var device, party string
				if e = rows.Scan(&device, &party); e != nil {
					break
				}
				parties[device] = json.RawMessage(party)
			}
			if e == nil {
				e = rows.Err()
			}
			if e != nil {
				failure(w, 503, "storage_unavailable", e)
				return true
			}
			respond(w, 200, parties)
			return true
		}
		if r.URL.Path == "/local/presentation/summary" {
			db := s.viewDB
			if db == nil {
				db = s.db
			}
			rows, e := db.QueryContext(r.Context(), `SELECT kind,COUNT(*) FROM presentation_journal WHERE recording=? GROUP BY kind`, r.URL.Query().Get("recording_id"))
			if e != nil {
				failure(w, 503, "storage_unavailable", e)
				return true
			}
			defer rows.Close()
			counts := map[string]int{}
			for rows.Next() {
				var kind string
				var count int
				if e = rows.Scan(&kind, &count); e != nil {
					break
				}
				counts[kind] = count
			}
			if e == nil {
				e = rows.Err()
			}
			if e != nil {
				failure(w, 503, "storage_unavailable", e)
				return true
			}
			respond(w, 200, counts)
			return true
		}
		if r.URL.Path == "/local/presentation/status" {
			db := s.liveDB
			if db == nil {
				db = s.db
			}
			var data string
			err := db.QueryRowContext(r.Context(), `SELECT data FROM presentation_journal WHERE kind='display' ORDER BY ordinal DESC LIMIT 1`).Scan(&data)
			if errors.Is(err, sql.ErrNoRows) {
				respond(w, 200, map[string]any{"confirmed": false})
				return true
			}
			if err != nil {
				failure(w, 503, "storage_unavailable", err)
				return true
			}
			respond(w, 200, json.RawMessage(data))
			return true
		}
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
	if op == "confirm" {
		if e := mapDiskReserve(s.path, 136*1024*1024); e != nil {
			return nil, e
		}
	}
	if op == "save" && s.path != ":memory:" {
		return s.stagedSave(r)
	}
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
		if l.Token != "" && !l.Closed && now.Sub(l.Seen) >= 0 && now.Sub(l.Seen) < 15*time.Second {
			return nil, errors.New("Command is occupied by another browser; close it or wait for its lease to expire")
		}
		gapKind := "unverified"
		if l.Closed {
			gapKind = "browser_unavailable"
		}
		gapFrom := l.Seen
		l = presentationLease{Token: id() + id(), Epoch: id(), Seen: now, GapFrom: gapFrom, GapKind: gapKind}
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
		case "present":
			opportunity, err := time.Parse(time.RFC3339Nano, r.Opportunity)
			if err != nil || opportunity.After(now.Add(5*time.Second)) {
				return nil, errors.New("invalid browser availability confirmation")
			}
			if e = finishPresentationGap(tx, st.Recording, &l, opportunity, now); e != nil {
				return nil, e
			}
			l.Seen = now
			result = map[string]any{"presentation_available": true}
		case "heartbeat":
			l.Seen = now
			result = l
		case "close":
			l.Closed = true
			l.Seen = now
			if st.Recording != nil {
				_, e = journal(tx, st.Recording.ID, "browser_closed", now, "", 0, map[string]any{"from": now, "reason": "browser pagehide; refresh may immediately reconnect"})
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
			if !l.LastDisplay.IsZero() && presented.Before(l.LastDisplay) {
				return nil, errors.New("browser presentation clock moved backwards; evidence unverified")
			}
			if presented.Before(st.Recording.Started) {
				return nil, errors.New("presentation predates Start")
			}
			if e = finishPresentationGap(tx, st.Recording, &l, opportunity, now); e != nil {
				return nil, e
			}
			if r.Revision != l.Revision+1 {
				_, e = journal(tx, st.Recording.ID, "unverified", now, "", 0, map[string]any{"reason": "missing browser revisions", "after_revision": l.Revision, "before_revision": r.Revision})
				if e != nil {
					return nil, e
				}
			}
			hash, e = putPresentationBlob(tx, b, l.Hash, "")
			if e != nil {
				return nil, e
			}
			l.Hash = hash
			if l.Recording != st.Recording.ID {
				gapFrom := st.Recording.Started
				var prior string
				if err := tx.QueryRow(`SELECT data FROM presentation_journal WHERE recording=? AND kind='display' ORDER BY ordinal DESC LIMIT 1`, st.Recording.ID).Scan(&prior); err == nil {
					var previous map[string]any
					_ = json.Unmarshal([]byte(prior), &previous)
					if stamp, ok := previous["render_opportunity_at"].(string); ok {
						if at, err := time.Parse(time.RFC3339Nano, stamp); err == nil {
							gapFrom = at
						}
					}
				}
				if _, e = journal(tx, st.Recording.ID, "unverified", now, "", 0, map[string]any{"from": gapFrom, "until": presented, "reason": "display unverified since previous confirmation or operator Start; newly confirmed state only"}); e != nil {
					return nil, e
				}
				l.Recording = st.Recording.ID
			}
			if !l.LastDisplay.IsZero() && presented.Sub(l.LastDisplay) > 5*time.Second {
				if _, e = journal(tx, st.Recording.ID, "unverified", now, "", 0, map[string]any{"from": l.LastDisplay, "until": presented, "reason": "no durable display confirmation in interval"}); e != nil {
					return nil, e
				}
			}
			l.LastDisplay = presented
			var common map[string]any
			_ = json.Unmarshal(r.State.Common, &common)
			data := map[string]any{"displayed_receipt_version": common["receipt_version"], "hash": hash, "presented_at": r.Presented, "render_opportunity_at": r.Opportunity, "committed_at": now, "revision": r.Revision, "epoch": r.Epoch, "timing_uncertainty": "browser clock and rendering-opportunity bounds; no OS scanout or human attention claim"}
			var ordinal int64
			ordinal, e = journal(tx, st.Recording.ID, "display", now, r.Epoch, r.Revision, data)
			if e != nil {
				return nil, e
			}
			if e = pinPresentationAssets(tx, st.Recording.ID, ordinal, r.State.MapID, r.State.Assets, r.State.AssetWarnings, now); e != nil {
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
					if prior, ok := v["request_sha256"].(string); ok && prior != saveRequestHash(r) {
						return nil, ErrConflict
					}
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
func pinPresentationAssets(tx *sql.Tx, recording string, ordinal int64, mapID string, assets []string, warnings []string, now time.Time) error {
	if mapID == "" {
		return nil
	}
	if len(warnings) > 0 {
		if _, e := journal(tx, recording, "missing_map_asset", now, "", 0, map[string]any{"map_id": mapID, "warnings": warnings}); e != nil {
			return e
		}
	}
	if len(assets) == 0 {
		_, e := journal(tx, recording, "missing_map_asset", now, "", 0, map[string]any{"map_id": mapID, "reason": "browser supplied no immutable asset response revisions"})
		return e
	}
	if len(assets) > 8192 {
		return errors.New("asset reference limit exceeded")
	}
	for _, hash := range assets {
		var present int
		if e := tx.QueryRow(`SELECT COUNT(*) FROM presentation_assets WHERE hash=?`, hash).Scan(&present); e != nil {
			return e
		}
		if present == 0 {
			_, e := journal(tx, recording, "missing_map_asset", now, "", 0, map[string]any{"hash": hash, "map_id": mapID})
			if e != nil {
				return e
			}
			continue
		}
		if _, e := tx.Exec(`INSERT OR IGNORE INTO presentation_asset_refs VALUES(?,?,?)`, recording, ordinal, hash); e != nil {
			return e
		}
	}
	return nil
}
func sealRecording(tx snapshotSQL, st State, r presentationRequest, now time.Time) (any, error) {
	rec := st.Recording
	if rec == nil {
		return nil, ErrAction
	}
	if strings.TrimSpace(r.Name) == "" || utf8.RuneCountInString(r.Name) > 200 || utf8.RuneCountInString(r.Description) > 16000 || len(r.Included) == 0 {
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
	assetWarnings := 0
	var lastPresentation time.Time
	stream := sha256.New()
	streamBytes := 0
	eventBatch := presentationBatch{db: tx, table: "saved_presentation", columns: 3}
	previousFiltered := ""
	ids := append([]string(nil), r.Included...)
	sort.Strings(ids)
	scope := ""
	if len(selected) < len(manifest) {
		scope = digest([]byte(marshal(ids)))
	}
	for rows.Next() {
		var v event
		var encoded string
		if e = rows.Scan(&v.Ordinal, &v.Kind, &v.At, &v.Epoch, &v.Revision, &encoded); e != nil {
			return nil, e
		}
		v.Data = json.RawMessage(encoded)
		if v.Kind == "missing_map_asset" {
			assetWarnings++
		}
		if v.Kind == "display" {
			var detail map[string]any
			_ = json.Unmarshal(v.Data, &detail)
			for _, key := range []string{"render_opportunity_at", "effective_at"} {
				if stamp, ok := detail[key].(string); ok {
					if at, err := time.Parse(time.RFC3339Nano, stamp); err == nil && at.After(lastPresentation) {
						lastPresentation = at
					}
				}
			}
			originalHash, ok := detail["hash"].(string)
			if !ok {
				return nil, errors.New("invalid display reference")
			}
			b, _, _, err := readPresentationBlob(tx, originalHash)
			if err != nil {
				return nil, err
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
			filtered, e := putPresentationBlob(tx, []byte(marshal(display)), previousFiltered, scope)
			if e != nil {
				return nil, e
			}
			previousFiltered = filtered
			detail["original_display_sha256"] = originalHash
			detail["hash"] = filtered
			detail["state_encoding"] = "content-addressed-v2"
			v.Data = json.RawMessage(marshal(detail))
			confirmed++
		}
		b := marshal(v)
		_, _ = stream.Write([]byte(b + "\n"))
		streamBytes += len(b) + 1
		if e = eventBatch.add(sid, v.Ordinal, b); e != nil {
			return nil, e
		}
	}
	if e = rows.Err(); e != nil {
		return nil, e
	}
	rows.Close()
	if e = eventBatch.flush(); e != nil {
		return nil, e
	}
	originalBatch := presentationBatch{db: tx, table: "saved_originals", columns: 6}
	for d := range selected {
		originals, e := tx.Query(`SELECT device,message,wire,known,received FROM raw WHERE device=? ORDER BY sequence`, d)
		if e != nil {
			return nil, e
		}
		for originals.Next() {
			var device, message, known, received string
			var wire []byte
			if e = originals.Scan(&device, &message, &wire, &known, &received); e != nil {
				originals.Close()
				return nil, e
			}
			if e = originalBatch.add(sid, device, message, wire, known, received); e != nil {
				originals.Close()
				return nil, e
			}
		}
		if e = originals.Err(); e != nil {
			originals.Close()
			return nil, e
		}
		originals.Close()
	}
	if e = originalBatch.flush(); e != nil {
		return nil, e
	}
	assetRows, e := tx.Query(`SELECT DISTINCT hash FROM presentation_asset_refs WHERE recording=? AND ordinal IN (SELECT ordinal FROM presentation_journal WHERE recording=? AND at<=?)`, rec.ID, rec.ID, cutoff.UTC().Format("2006-01-02T15:04:05.000000000Z"))
	if e != nil {
		return nil, e
	}
	for assetRows.Next() {
		var hash string
		if e = assetRows.Scan(&hash); e != nil {
			assetRows.Close()
			return nil, e
		}
		if _, e = tx.Exec(`INSERT INTO saved_assets VALUES(?,?)`, sid, hash); e != nil {
			assetRows.Close()
			return nil, e
		}
	}
	if e = assetRows.Err(); e != nil {
		assetRows.Close()
		return nil, e
	}
	assetRows.Close()
	var assetBytes, rawBytes int64
	if e = tx.QueryRow(`SELECT COALESCE(SUM(bytes),0) FROM presentation_assets WHERE hash IN (SELECT hash FROM saved_assets WHERE snapshot=?)`, sid).Scan(&assetBytes); e != nil {
		return nil, e
	}
	if e = tx.QueryRow(`SELECT COALESCE(SUM(length(wire)),0) FROM saved_originals WHERE snapshot=?`, sid).Scan(&rawBytes); e != nil {
		return nil, e
	}
	metadata := map[string]any{"version": 2, "id": sid, "recording_id": rec.ID, "name": strings.TrimSpace(r.Name), "description": r.Description, "display_date": r.DisplayDate, "source_mode": rec.Mode, "snapshot_status": rec.Status, "started_at": rec.Started, "cutoff_at": cutoff, "saved_at": now, "operator_save_at": r.OperatorAt, "stopped_at": rec.Stopped, "total_parties": len(manifest), "participation": manifest, "included": r.Included, "operator_selected_subset": len(selected) < len(manifest), "confirmed_events": confirmed, "stream_sha256": hex.EncodeToString(stream.Sum(nil)), "presentation_reference_bytes": streamBytes, "presentation_encoding": "bounded-delta-v2", "original_bytes": rawBytes, "pinned_asset_bytes": assetBytes, "incomplete_evidence": true, "asset_revision_verification": "Exact successful map/terrain/manifest response bytes are pinned before delivery; missing browser revision references are disclosed as gaps.", "limitations": []string{"Rendering opportunities confirm browser-applied state, not OS pixel scanout or human attention.", "Unconfirmed transitions and process-death boundaries remain uncertain; no later GNSS backfill repairs presentation evidence."}}
	if len(rec.Windows) > 0 {
		window := rec.Windows[len(rec.Windows)-1]
		from := lastPresentation
		if from.Before(window.Start) {
			from = window.Start
		}
		until := cutoff
		if !rec.Active && window.Stop != nil && window.Stop.Before(until) {
			until = *window.Stop
		}
		if until.After(from) {
			metadata["trailing_unverified_interval"] = map[string]any{"from": from, "until": until, "reason": "no later durably confirmed presentation through the frozen active-clip boundary"}
		}
	}
	metadata["map_fidelity_warning_events"] = assetWarnings
	metadata["request_sha256"] = saveRequestHash(r)
	metadata["supporting_projection"] = map[string]any{"policy": st.Policy, "raw_version": st.RawVersion, "projection_version": st.ProjectionVersion, "pending": st.ProjectionPending, "audit_only": true}
	if e = indexVisibility(tx, sid); e != nil {
		return nil, e
	}
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
		var deleted int
		_ = db.QueryRowContext(r.Context(), `SELECT deleted FROM saved_library WHERE id=?`, sid).Scan(&deleted)
		if deleted == 1 {
			failure(w, 404, "snapshot_not_found", errors.New("recording deleted"))
			return
		}
		var meta string
		if e := db.QueryRowContext(r.Context(), `SELECT metadata FROM saved_recordings WHERE id=?`, sid).Scan(&meta); e != nil {
			failure(w, 404, "snapshot_not_found", e)
			return
		}
		if hash := r.URL.Query().Get("asset"); hash != "" {
			var data []byte
			if e := db.QueryRowContext(r.Context(), `SELECT a.data FROM presentation_assets a JOIN saved_assets s ON a.hash=s.hash WHERE s.snapshot=? AND a.hash=?`, sid, hash).Scan(&data); e != nil {
				failure(w, 404, "snapshot_asset_not_found", e)
				return
			}
			if digest(data) != hash {
				failure(w, 503, "snapshot_asset_integrity_error", errors.New("pinned asset hash mismatch"))
				return
			}
			w.Header().Set("Content-Type", "application/octet-stream")
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("X-Presentation-Asset", hash)
			_, _ = w.Write(data)
			return
		}
		if r.URL.Query().Has("event_cursor") {
			cursor, e := strconv.ParseInt(r.URL.Query().Get("event_cursor"), 10, 64)
			if e != nil || cursor < 0 {
				failure(w, 400, "invalid_cursor", errors.New("non-negative event cursor required"))
				return
			}
			var ordinal int64
			var encoded string
			e = db.QueryRowContext(r.Context(), `SELECT ordinal,data FROM saved_presentation WHERE snapshot=? AND ordinal>? ORDER BY ordinal LIMIT 1`, sid, cursor).Scan(&ordinal, &encoded)
			if errors.Is(e, sql.ErrNoRows) {
				respond(w, 200, map[string]any{"snapshot_id": sid, "event": nil, "next_cursor": ""})
				return
			}
			if e != nil {
				failure(w, 503, "storage_unavailable", e)
				return
			}
			var event struct {
				Kind string
				Data map[string]json.RawMessage
			}
			if e = json.Unmarshal([]byte(encoded), &event); e != nil {
				failure(w, 503, "snapshot_integrity_error", e)
				return
			}
			result := map[string]any{"snapshot_id": sid, "event": json.RawMessage(encoded), "next_cursor": strconv.FormatInt(ordinal, 10)}
			if event.Kind == "display" {
				if inline := event.Data["state"]; len(inline) > 0 {
					result["checkpoint"] = inline
				} else {
					var hash string
					_ = json.Unmarshal(event.Data["hash"], &hash)
					state, _, _, e := readPresentationBlob(db, hash)
					if e != nil {
						failure(w, 503, "snapshot_integrity_error", e)
						return
					}
					result["checkpoint"] = json.RawMessage(state)
				}
			}
			if event.Kind == "display" {
				raw, _ := json.Marshal(result["checkpoint"])
				var checkpoint displayState
				if e = json.Unmarshal(raw, &checkpoint); e != nil {
					failure(w, 503, "snapshot_integrity_error", e)
					return
				}
				gatePresentation(&checkpoint)
				result["checkpoint"] = checkpoint
				result["visibility_gated"] = true
				var safe map[string]json.RawMessage
				_ = json.Unmarshal([]byte(encoded), &safe)
				delete(event.Data, "state")
				safe["Data"] = json.RawMessage(marshal(event.Data))
				result["event"] = json.RawMessage(marshal(safe))
			}
			respond(w, 200, result)
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
	rows, e := db.QueryContext(r.Context(), `SELECT metadata FROM saved_recordings WHERE NOT EXISTS(SELECT 1 FROM saved_library WHERE id=saved_recordings.id AND deleted=1) ORDER BY rowid DESC LIMIT 100`)
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
		if _, e := tx.Exec(`INSERT INTO presentation_participants VALUES(?,?,?) ON CONFLICT(recording,device) DO UPDATE SET party=excluded.party`, st.Recording.ID, d, marshal(v.Snapshot.Party)); e != nil {
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

// Freeze the exact successful asset response before delivering its bytes to the
// browser. This closes the load-versus-library-replacement race: the browser names
// the response hash it used, not merely the mutable map library identity.
func (s *Store) pinServedAsset(b []byte) (string, error) {
	if len(b) > mapLimit+65536 {
		return "", errors.New("asset response exceeds recording pin bound")
	}
	if e := mapDiskReserve(s.path, uint64(len(b))+136*1024*1024); e != nil {
		return "", e
	}
	hash := digest(b)
	s.mu.Lock()
	defer s.mu.Unlock()
	_, e := s.db.Exec(`INSERT OR IGNORE INTO presentation_assets VALUES(?,?,?)`, hash, b, len(b))
	return hash, e
}
func presentationAssetPath(r *http.Request) bool {
	if r.Method != "GET" || !strings.HasPrefix(r.URL.Path, "/local/maps/") {
		return false
	}
	tail := strings.TrimPrefix(r.URL.Path, "/local/maps/")
	parts := strings.Split(tail, "/")
	return len(parts) == 1 || (len(parts) == 2 && (parts[1] == "terrain" || parts[1] == "manifest")) || (len(parts) == 3 && parts[1] == "chunks")
}

// Source reads retain one WAL snapshot. Staging writes are individually committed
// and invisible to consumers until the immutable header is atomically published.
// No Save transaction holds the receiver mutex while scanning a recording.
type snapshotSQL interface {
	Exec(string, ...any) (sql.Result, error)
	Query(string, ...any) (*sql.Rows, error)
	QueryRow(string, ...any) *sql.Row
}
type stagedStatement struct {
	query string
	args  []any
}
type stagedSnapshot struct {
	store    *Store
	source   *sql.Tx
	publish  []stagedStatement
	snapshot string
}
type stagedResult struct{}

func (stagedResult) LastInsertId() (int64, error) { return 0, nil }
func (stagedResult) RowsAffected() (int64, error) { return 1, nil }
func (v *stagedSnapshot) Query(q string, args ...any) (*sql.Rows, error) {
	return v.source.Query(q, args...)
}
func (v *stagedSnapshot) QueryRow(q string, args ...any) *sql.Row {
	if strings.Contains(q, "saved_") || strings.Contains(q, "presentation_blobs") || strings.Contains(q, "presentation_usage") {
		return v.store.db.QueryRow(q, args...)
	}
	return v.source.QueryRow(q, args...)
}
func (v *stagedSnapshot) Exec(q string, args ...any) (sql.Result, error) {
	if strings.HasPrefix(q, "INSERT INTO saved_") && len(args) > 0 {
		v.snapshot, _ = args[0].(string)
	}
	if strings.HasPrefix(q, "INSERT INTO saved_recordings") || strings.HasPrefix(q, "INSERT INTO presentation_requests") || strings.HasPrefix(q, "INSERT INTO presentation_journal") {
		v.publish = append(v.publish, stagedStatement{q, args})
		return stagedResult{}, nil
	}
	if e := mapDiskReserve(v.store.path, 136*1024*1024); e != nil {
		return nil, e
	}
	v.store.mu.Lock()
	defer v.store.mu.Unlock()
	return v.store.db.Exec(q, args...)
}
func (s *Store) stagedSave(r presentationRequest) (any, error) {
	if e := mapDiskReserve(s.path, 136*1024*1024); e != nil {
		return nil, e
	}
	if !s.saveMu.TryLock() {
		return nil, errors.New("another snapshot is being sealed; retry after it completes")
	}
	defer s.saveMu.Unlock()
	now := s.Now().UTC()
	dsn, e := privateDSN(s.path)
	if e != nil {
		return nil, e
	}
	reader, e := sql.Open("sqlite3", dsn)
	if e != nil {
		return nil, e
	}
	defer reader.Close()
	reader.SetMaxOpenConns(1)
	if _, e = reader.Exec(`PRAGMA query_only=ON`); e != nil {
		return nil, e
	}
	source, e := reader.Begin()
	if e != nil {
		return nil, e
	}
	defer source.Rollback()
	l, e := getLease(source)
	if e != nil {
		return nil, e
	}
	if !owner(l, r, s.Now().UTC()) {
		return nil, errors.New("browser owner fenced")
	}
	if r.RequestID != "" {
		var b string
		e = source.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=(SELECT snapshot FROM presentation_requests WHERE request=?)`, r.RequestID).Scan(&b)
		if e == nil {
			var v map[string]any
			_ = json.Unmarshal([]byte(b), &v)
			if prior, ok := v["request_sha256"].(string); ok && prior != saveRequestHash(r) {
				return nil, ErrConflict
			}
			return v, nil
		}
		if !errors.Is(e, sql.ErrNoRows) {
			return nil, e
		}
	}
	st, e := loadReceiptState(source)
	if e != nil {
		return nil, e
	}
	stage := &stagedSnapshot{store: s, source: source}
	published := false
	defer func() {
		if !published && stage.snapshot != "" {
			for _, table := range []string{"saved_visibility", "saved_visibility_ready", "saved_presentation", "saved_originals", "saved_assets"} {
				_, _ = s.db.Exec("DELETE FROM "+table+" WHERE snapshot=?", stage.snapshot)
			}
		}
	}()
	result, e := sealRecording(stage, st, r, now)
	if e != nil {
		return nil, e
	}
	source.Rollback() // Release WAL snapshot before the short publication transaction.
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, e := s.db.Begin()
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	for _, statement := range stage.publish {
		if _, e = tx.Exec(statement.query, statement.args...); e != nil {
			return nil, e
		}
	}
	if e = tx.Commit(); e != nil {
		return nil, e
	}
	published = true
	return result, nil
}

type assetResponse struct {
	headers  http.Header
	Code     int
	Body     bytes.Buffer
	overflow bool
}

func (a *assetResponse) Header() http.Header  { return a.headers }
func (a *assetResponse) WriteHeader(code int) { a.Code = code }
func (a *assetResponse) Write(b []byte) (int, error) {
	if a.Body.Len()+len(b) > mapLimit+65536 {
		a.overflow = true
		return 0, errors.New("recording asset response exceeds pin bound")
	}
	return a.Body.Write(b)
}

// A stable browser rendering opportunity preceding the press can establish the
// state visible at Start/Resume without pretending it was first rendered then.
// Its effective recording time is the operator boundary; original render times
// remain separate. If no such proof exists, preserve an explicit uncertainty.
func presentationBoundary(tx *sql.Tx, rec *Recording, proof *presentationRequest, token, epoch string, pressed, received time.Time) error {
	unverified := func(reason string) error {
		_, e := journal(tx, rec.ID, "unverified", pressed, "", 0, map[string]any{"from": pressed, "reason": reason})
		return e
	}
	if proof == nil {
		return unverified("no stable pre-action browser rendering opportunity; awaiting first confirmed state")
	}
	l, e := getLease(tx)
	if e != nil {
		return e
	}
	if !owner(l, presentationRequest{Token: token, Epoch: epoch}, received) {
		return errors.New("browser owner fenced")
	}
	rendered, e1 := time.Parse(time.RFC3339Nano, proof.Presented)
	opportunity, e2 := time.Parse(time.RFC3339Nano, proof.Opportunity)
	if e1 != nil || e2 != nil || rendered.After(opportunity) || opportunity.After(pressed) || pressed.Sub(opportunity) > 30*time.Second || proof.State.Version != 1 || proof.Revision <= l.Revision {
		return unverified("pre-action browser proof outside ordering or timing bounds")
	}
	b := []byte(marshal(proof.State))
	if len(b) > presentationLimit {
		return unverified("pre-action browser state exceeds capture limit")
	}
	hash, e := putPresentationBlob(tx, b, l.Hash, "")
	if e != nil {
		return unverified(e.Error())
	}
	l.Hash = hash
	var common map[string]any
	_ = json.Unmarshal(proof.State.Common, &common)
	ordinal, e := journal(tx, rec.ID, "display", pressed, epoch, proof.Revision, map[string]any{"hash": hash, "epoch": epoch, "revision": proof.Revision, "effective_at": pressed, "presented_at": proof.Presented, "render_opportunity_at": proof.Opportunity, "committed_at": received, "boundary_state": true, "displayed_receipt_version": common["receipt_version"], "timing_uncertainty": "stable applied browser state preceding operator boundary; no OS scanout or attention claim"})
	if e != nil {
		return e
	}
	if e = pinPresentationAssets(tx, rec.ID, ordinal, proof.State.MapID, proof.State.Assets, proof.State.AssetWarnings, received); e != nil {
		return e
	}
	l.Revision = proof.Revision
	l.LastDisplay = rendered
	l.Recording = rec.ID
	return putLease(tx, l)
}

func finishPresentationGap(tx *sql.Tx, rec *Recording, l *presentationLease, presented, committed time.Time) error {
	if l.GapKind == "" {
		return nil
	}
	if rec != nil && rec.Status != "stopped" {
		from := l.GapFrom
		if from.Before(rec.Started) {
			from = rec.Started
		}
		kind := l.GapKind
		uncertain := false
		if presented.Before(from) {
			kind = "unverified"
			uncertain = true
		}
		if _, e := journal(tx, rec.ID, kind, committed, "", 0, map[string]any{"from": from, "until": presented, "boundary_uncertain": uncertain, "reason": "ownership recovery ends at browser rendering opportunity, not HTTP claim"}); e != nil {
			return e
		}
	}
	l.GapKind = ""
	l.GapFrom = time.Time{}
	return nil
}

func saveRequestHash(r presentationRequest) string {
	ids := append([]string(nil), r.Included...)
	sort.Strings(ids)
	return digest([]byte(marshal([]any{strings.TrimSpace(r.Name), r.Description, r.DisplayDate, ids})))
}

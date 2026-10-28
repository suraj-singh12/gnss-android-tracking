package core

// Playback reads frozen browser evidence; it never projects GNSS observations.
import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math"
	"net/http"
	"sort"
	"strconv"
	"time"
)

const displayClock = `julianday(COALESCE(json_extract(data,'$.Data.effective_at'),json_extract(data,'$.Data.presented_at'),json_extract(data,'$.Data.render_opportunity_at'),json_extract(data,'$.At')))`

// Availability is confirmed after rendering; the resumed display itself can
// precede that confirmation by one frame opportunity.
const confirmationClock = `julianday(COALESCE(json_extract(data,'$.Data.effective_at'),json_extract(data,'$.Data.render_opportunity_at'),json_extract(data,'$.Data.presented_at'),json_extract(data,'$.At')))`

type playbackEvent struct {
	Ordinal  int64 `json:"ordinal"`
	Kind, At string
	Data     map[string]json.RawMessage
}
type playbackInterval struct {
	From, Until time.Time
	Kind        string
	Facts       []playbackEvent
}
type playbackGroup struct {
	From, Until  time.Time
	Kind         string
	ElapsedUnion float64
	Duration     float64
	Facts        []playbackEvent
	Offset       float64
	SkipUntil    time.Time
}

func eventStamp(v playbackEvent) time.Time {
	for _, k := range []string{"effective_at", "presented_at", "render_opportunity_at"} {
		var x string
		if json.Unmarshal(v.Data[k], &x) == nil {
			if at, e := time.Parse(time.RFC3339Nano, x); e == nil {
				return at
			}
		}
	}
	at, _ := time.Parse(time.RFC3339Nano, v.At)
	return at
}
func interruptionGroups(intervals []playbackInterval) []playbackGroup {
	sort.SliceStable(intervals, func(i, j int) bool { return intervals[i].From.Before(intervals[j].From) })
	out := []playbackGroup{}
	priority := map[string]int{"PAUSED": 1, "BROWSER NOT ACTIVE": 2, "COMMAND NOT RUNNING": 3}
	for _, v := range intervals {
		if !v.Until.After(v.From) {
			continue
		}
		d := v.Until.Sub(v.From).Seconds()
		n := len(out) - 1
		if n < 0 || !v.From.Before(out[n].Until) {
			out = append(out, playbackGroup{From: v.From, Until: v.Until, Kind: v.Kind, Duration: d, ElapsedUnion: d, Facts: v.Facts})
			continue
		}
		g := &out[n]
		if v.Until.After(g.Until) {
			g.Until = v.Until
		}
		g.Facts = append(g.Facts, v.Facts...)
		g.ElapsedUnion = g.Until.Sub(g.From).Seconds()
		if d > g.Duration || d == g.Duration && priority[v.Kind] > priority[g.Kind] {
			g.Duration = d
			g.Kind = v.Kind
		}
	}
	return out
}
func (s *Store) playbackHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		failure(w, 405, "read_only", errors.New("playback is read-only"))
		return
	}
	db := s.viewDB
	if db == nil {
		db = s.db
	}
	sid := r.URL.Query().Get("id")
	var encoded string
	if e := db.QueryRowContext(r.Context(), `SELECT metadata FROM saved_recordings WHERE id=? AND NOT EXISTS(SELECT 1 FROM saved_library WHERE id=? AND deleted=1)`, sid, sid).Scan(&encoded); e != nil {
		failure(w, 404, "snapshot_not_found", e)
		return
	}
	var meta map[string]json.RawMessage
	if e := json.Unmarshal([]byte(encoded), &meta); e != nil {
		failure(w, 503, "snapshot_integrity_error", e)
		return
	}
	if r.URL.Query().Has("at") {
		at, e := strconv.ParseFloat(r.URL.Query().Get("at"), 64)
		if e != nil || math.IsNaN(at) || math.IsInf(at, 0) {
			failure(w, 400, "invalid_time", errors.New("finite Unix milliseconds required"))
			return
		}
		var start, end time.Time
		_ = json.Unmarshal(meta["started_at"], &start)
		_ = json.Unmarshal(meta["cutoff_at"], &end)
		if at < float64(start.UnixMilli()) || at > float64(end.UnixMilli()) {
			failure(w, 400, "invalid_time", errors.New("time outside saved recording"))
			return
		}
		var data string
		var ordinal int64
		e = db.QueryRowContext(r.Context(), `SELECT ordinal,data FROM saved_presentation WHERE snapshot=? AND json_extract(data,'$.Kind')='display' AND `+displayClock+`<=julianday(?,'unixepoch') ORDER BY `+displayClock+` DESC,ordinal DESC LIMIT 1`, sid, at/1000).Scan(&ordinal, &data)
		if errors.Is(e, sql.ErrNoRows) {
			respond(w, 200, map[string]any{"checkpoint": nil, "unverified": true})
			return
		}
		if e != nil {
			failure(w, 503, "storage_unavailable", e)
			return
		}
		var event playbackEvent
		if e = json.Unmarshal([]byte(data), &event); e != nil {
			failure(w, 503, "snapshot_integrity_error", e)
			return
		}
		var state []byte
		if inline := event.Data["state"]; len(inline) > 0 {
			state = inline
		} else {
			var hash string
			_ = json.Unmarshal(event.Data["hash"], &hash)
			state, _, _, e = readPresentationBlob(db, hash)
		}
		if e != nil {
			failure(w, 503, "snapshot_integrity_error", e)
			return
		}
		var checkpoint displayState
		if e = json.Unmarshal(state, &checkpoint); e != nil {
			failure(w, 503, "snapshot_integrity_error", e)
			return
		}
		gatePresentation(&checkpoint)
		delete(event.Data, "state") // legacy inline payload must not bypass the gate

		gates := map[string]int64{}
		for id := range checkpoint.Parties {
			var gate int64
			if err := db.QueryRowContext(r.Context(), `SELECT ordinal FROM saved_visibility WHERE snapshot=? AND device=? AND ordinal<=? ORDER BY ordinal DESC LIMIT 1`, sid, id, ordinal).Scan(&gate); err != nil && !errors.Is(err, sql.ErrNoRows) {
				failure(w, 503, "storage_unavailable", err)
				return
			}
			gates[id] = gate
		}
		var next sql.NullFloat64
		if err := db.QueryRowContext(r.Context(), `SELECT (`+displayClock+`-2440587.5)*86400000 FROM saved_presentation WHERE snapshot=? AND json_extract(data,'$.Kind')='display' AND `+displayClock+`>julianday(?,'unixepoch') ORDER BY `+displayClock+`,ordinal LIMIT 1`, sid, at/1000).Scan(&next); err != nil && !errors.Is(err, sql.ErrNoRows) {
			failure(w, 503, "storage_unavailable", err)
			return
		}
		respond(w, 200, map[string]any{"visibility_epochs": gates, "next_at": next, "ordinal": ordinal, "event": event, "checkpoint": checkpoint, "presentation_at": eventStamp(event)})
		return
	}
	// Verify the sealed event chronology once when opening, streaming in bounded
	// memory. Frame seeks still resolve only the indexed graph they need.
	stream, err := db.QueryContext(r.Context(), `SELECT data FROM saved_presentation WHERE snapshot=? ORDER BY ordinal`, sid)
	if err != nil {
		failure(w, 503, "storage_unavailable", err)
		return
	}
	hash := sha256.New()
	for stream.Next() {
		var data string
		if err = stream.Scan(&data); err != nil {
			break
		}
		_, _ = hash.Write([]byte(data + "\n"))
	}
	if err == nil {
		err = stream.Err()
	}
	stream.Close()
	var expected string
	_ = json.Unmarshal(meta["stream_sha256"], &expected)
	if err != nil || hex.EncodeToString(hash.Sum(nil)) != expected {
		failure(w, 503, "snapshot_integrity_error", errors.New("sealed event stream verification failed"))
		return
	}
	rows, e := db.QueryContext(r.Context(), `SELECT data FROM saved_presentation WHERE snapshot=? AND json_extract(data,'$.Kind')!='display' ORDER BY ordinal`, sid)
	if e != nil {
		failure(w, 503, "storage_unavailable", e)
		return
	}
	intervals := []playbackInterval{}
	facts := []playbackEvent{}
	var pause *playbackEvent
	var browserClosed *playbackEvent
	browserFacts := []playbackEvent{}
	var cutoff, start time.Time
	_ = json.Unmarshal(meta["cutoff_at"], &cutoff)
	_ = json.Unmarshal(meta["started_at"], &start)
	for rows.Next() {
		var b string
		if e = rows.Scan(&b); e != nil {
			break
		}
		var v playbackEvent
		if e = json.Unmarshal([]byte(b), &v); e != nil {
			break
		}
		facts = append(facts, v)
		switch v.Kind {
		case "browser_closed":
			if browserClosed == nil {
				copy := v
				browserClosed = &copy
			}
			browserFacts = append(browserFacts, v)
		case "pause":
			copy := v
			pause = &copy
		case "resume", "stop":
			if pause != nil {
				intervals = append(intervals, playbackInterval{eventStamp(*pause), eventStamp(v), "PAUSED", []playbackEvent{*pause, v}})
				pause = nil
			}
		case "browser_unavailable", "command_unavailable":
			var from, until time.Time
			_ = json.Unmarshal(v.Data["from"], &from)
			_ = json.Unmarshal(v.Data["until"], &until)
			kind := "BROWSER NOT ACTIVE"
			if v.Kind == "command_unavailable" {
				kind = "COMMAND NOT RUNNING"
			}
			if v.Kind == "browser_unavailable" && browserClosed != nil {
				browserFacts = append(browserFacts, v)
				var reason string
				_ = json.Unmarshal(v.Data["reason"], &reason)
				// #27 splits a known closed browser around process downtime.
				// Retain those facts, but compare the full underlying browser
				// absence against the overlapping Command/Pause intervals.
				if reason == "known pagehide before Command last durable heartbeat" {
					continue
				}
				intervals = append(intervals, playbackInterval{eventStamp(*browserClosed), until, kind, browserFacts})
				browserClosed = nil
				browserFacts = nil
			} else {
				intervals = append(intervals, playbackInterval{from, until, kind, []playbackEvent{v}})
			}
		}
	}
	if e == nil {
		e = rows.Err()
	}
	rows.Close()
	if e != nil {
		failure(w, 503, "snapshot_integrity_error", e)
		return
	}
	if pause != nil {
		intervals = append(intervals, playbackInterval{eventStamp(*pause), cutoff, "PAUSED", []playbackEvent{*pause}})
	}
	if browserClosed != nil {
		intervals = append(intervals, playbackInterval{eventStamp(*browserClosed), cutoff, "BROWSER NOT ACTIVE", browserFacts})
	}
	groups := interruptionGroups(intervals)
	wall := start
	offset := 0.0
	for i := range groups {
		g := &groups[i]
		if g.From.Before(start) {
			g.From = start
		}
		if g.Until.After(cutoff) {
			g.Until = cutoff
		}
		g.SkipUntil = g.Until
		var next string
		err := db.QueryRowContext(r.Context(), `SELECT data FROM saved_presentation WHERE snapshot=? AND json_extract(data,'$.Kind')='display' AND `+confirmationClock+`>=julianday(?) ORDER BY `+confirmationClock+`,ordinal LIMIT 1`, sid, g.Until.Format(time.RFC3339Nano)).Scan(&next)
		if err == nil {
			var v playbackEvent
			_ = json.Unmarshal([]byte(next), &v)
			g.SkipUntil = eventStamp(v)
		} else if !errors.Is(err, sql.ErrNoRows) {
			failure(w, 503, "storage_unavailable", err)
			return
		} else {
			g.SkipUntil = cutoff
		}
		// Disjoint notices remain separate even when there was no rendered frame
		// between them. Do not skip over a later notice into future evidence.
		if i+1 < len(groups) && g.SkipUntil.After(groups[i+1].From) {
			g.SkipUntil = groups[i+1].From
		}
		if g.From.After(wall) {
			offset += g.From.Sub(wall).Seconds()
		}
		g.Offset = offset
		offset += 4
		if g.SkipUntil.After(wall) {
			wall = g.SkipUntil
		}
	}
	if cutoff.After(wall) {
		offset += cutoff.Sub(wall).Seconds()
	}
	respond(w, 200, map[string]any{"metadata": json.RawMessage(encoded), "groups": groups, "facts": facts, "duration": offset, "read_only": true, "stream_integrity_valid": true})
}

// Derived visibility index is built at Save (or once on upgrading old snapshots).
// It records boundaries, not GNSS, and is never modified by inspection requests.
func indexVisibility(db snapshotSQL, sid string) error {
	previous := map[string]bool{}
	cursor := int64(0)
	for {
		var ordinal int64
		var encoded string
		err := db.QueryRow(`SELECT ordinal,data FROM saved_presentation WHERE snapshot=? AND ordinal>? AND json_extract(data,'$.Kind')='display' ORDER BY ordinal LIMIT 1`, sid, cursor).Scan(&ordinal, &encoded)
		if errors.Is(err, sql.ErrNoRows) {
			break
		}
		if err != nil {
			return err
		}
		cursor = ordinal
		var event playbackEvent
		if err = json.Unmarshal([]byte(encoded), &event); err != nil {
			return err
		}
		var b []byte
		if inline := event.Data["state"]; len(inline) > 0 {
			b = inline
		} else {
			var hash string
			_ = json.Unmarshal(event.Data["hash"], &hash)
			b, _, _, err = readPresentationBlob(db, hash)
			if err != nil {
				return err
			}
		}
		var checkpoint displayState
		if err = json.Unmarshal(b, &checkpoint); err != nil {
			return err
		}
		for id, raw := range checkpoint.Parties {
			var p struct{ Visible bool }
			_ = json.Unmarshal(raw, &p)
			old, known := previous[id]
			if !known || old != p.Visible {
				if _, err = db.Exec(`INSERT OR REPLACE INTO saved_visibility VALUES(?,?,?,?)`, sid, id, ordinal, p.Visible); err != nil {
					return err
				}
				previous[id] = p.Visible
			}
		}
	}
	_, err := db.Exec(`INSERT OR IGNORE INTO saved_visibility_ready VALUES(?)`, sid)
	return err
}
func (s *Store) initPlaybackIndex() error {
	for _, q := range []string{`CREATE TABLE IF NOT EXISTS saved_visibility(snapshot TEXT,device TEXT,ordinal INTEGER,visible INTEGER,PRIMARY KEY(snapshot,device,ordinal))`, `CREATE TABLE IF NOT EXISTS saved_visibility_ready(snapshot TEXT PRIMARY KEY)`} {
		if _, e := s.db.Exec(q); e != nil {
			return e
		}
	}
	for _, table := range []string{"saved_visibility", "saved_visibility_ready"} {
		if _, err := s.db.Exec("DELETE FROM " + table + " WHERE NOT EXISTS(SELECT 1 FROM saved_recordings WHERE id=" + table + ".snapshot)"); err != nil {
			return err
		}
	}
	var cursorHash string
	for {
		var hash string
		var packed []byte
		err := s.db.QueryRow(`SELECT hash,data FROM presentation_blobs WHERE hash>? AND NOT EXISTS(SELECT 1 FROM presentation_blob_links WHERE hash=presentation_blobs.hash) ORDER BY hash LIMIT 1`, cursorHash).Scan(&hash, &packed)
		if errors.Is(err, sql.ErrNoRows) {
			break
		}
		if err != nil {
			return err
		}
		if err = indexBlobLink(s.db, hash, packed); err != nil {
			return err
		}
		cursorHash = hash
	}
	// Resume interrupted resource reclamation before accepting traffic.
	gcCursor := ""
	for {
		var sid string
		err := s.db.QueryRow(`SELECT id FROM saved_library WHERE deleted=1 AND id>? AND NOT EXISTS(SELECT 1 FROM saved_gc_done WHERE snapshot=id) ORDER BY id LIMIT 1`, gcCursor).Scan(&sid)
		if errors.Is(err, sql.ErrNoRows) {
			break
		}
		if err != nil {
			return err
		}
		gcCursor = sid
		if err = s.reclaimSnapshot(sid); err != nil && !errors.Is(err, errSnapshotAssetsInUse) {
			return err
		}
	}
	// Read one receipt at a time so migration has bounded memory and no open writer cursor.
	cursor := ""
	for {
		var sid string
		err := s.db.QueryRow(`SELECT id FROM saved_recordings WHERE id>? AND NOT EXISTS(SELECT 1 FROM saved_visibility_ready WHERE snapshot=id) AND NOT EXISTS(SELECT 1 FROM saved_library WHERE id=saved_recordings.id AND deleted=1) ORDER BY id LIMIT 1`, cursor).Scan(&sid)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if err = indexVisibility(s.db, sid); err != nil {
			return err
		}
		cursor = sid
	}
}

// Apply the same disclosure boundary to every browser checkpoint read, including
// the existing #27 cursor API. The sealed stored bytes remain untouched.
func gatePresentation(checkpoint *displayState) {
	for id, raw := range checkpoint.Parties {
		var p struct {
			Visible bool
			Device  struct {
				Snapshot struct{ Party json.RawMessage }
			}
		}
		_ = json.Unmarshal(raw, &p)
		if !p.Visible {
			checkpoint.Parties[id] = json.RawMessage(marshal(map[string]any{"visible": false, "identity": p.Device.Snapshot.Party}))
		}
	}
	var common map[string]json.RawMessage
	_ = json.Unmarshal(checkpoint.Common, &common)
	var selected string
	_ = json.Unmarshal(common["selected_party"], &selected)
	var p struct{ Visible bool }
	_ = json.Unmarshal(checkpoint.Parties[selected], &p)
	if !p.Visible {
		delete(common, "selected_party")
	}
	if raw, ok := common["hidden"]; ok {
		var hidden []string
		_ = json.Unmarshal(raw, &hidden)
		filtered := []string{}
		for _, id := range hidden {
			if _, known := checkpoint.Parties[id]; known {
				filtered = append(filtered, id)
			}
		}
		common["hidden"] = json.RawMessage(marshal(filtered))
	}
	checkpoint.Common = json.RawMessage(marshal(common))
}

package core

// Control v1 extends the existing receiver, SQLite authority and local UI. GNSS v1
// remains unauthenticated. Credentials are deliberately never part of State/export.
import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

type controlEnvelope struct {
	Device     string `json:"device"`
	Enrollment string `json:"enrollment"`
	Data       string `json:"data"`
	MAC        string `json:"mac"`
}
type controlRequest struct {
	Version     int             `json:"version"`
	Authority   string          `json:"authority"`
	Nonce       string          `json:"nonce"`
	At          int64           `json:"at"`
	Results     []controlResult `json:"results"`
	ResultsOnly bool            `json:"results_only,omitempty"`
}
type controlResult struct {
	ID        string `json:"id"`
	Revision  int64  `json:"revision"`
	State     string `json:"state"`
	Reason    string `json:"reason"`
	Effective string `json:"effective"`
	Received  int64  `json:"received"`
	Attempted bool   `json:"attempted"`
}
type controlInstruction struct {
	Priority    string          `json:"priority"`
	ID          string          `json:"id"`
	Authority   string          `json:"authority"`
	Device      string          `json:"device"`
	Enrollment  string          `json:"enrollment"`
	Operation   string          `json:"operation"`
	Version     int             `json:"version"`
	Payload     json.RawMessage `json:"payload"`
	Created     int64           `json:"created"`
	Expires     int64           `json:"expires"`
	Ordering    int64           `json:"ordering"`
	Disposition string          `json:"disposition"`
}
type controlEntry struct {
	controlInstruction
	State  string         `json:"state"`
	Result *controlResult `json:"result"`
	Issued string         `json:"issued"`
}

func randomControlKey() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func controlMAC(key, direction, device, enrollment, data string) string {
	k, e := hex.DecodeString(key)
	if e != nil || len(k) != 32 {
		return ""
	}
	m := hmac.New(sha256.New, k)
	m.Write([]byte("gnss-control-v1\n" + direction + "\n" + device + "\n" + enrollment + "\n" + data))
	return hex.EncodeToString(m.Sum(nil))
}
func (s *Store) initControl() error {
	for _, q := range []string{
		`CREATE TABLE IF NOT EXISTS control_enrollment(device TEXT PRIMARY KEY,enrollment TEXT NOT NULL,secret TEXT NOT NULL,enabled INTEGER NOT NULL,last_seen INTEGER NOT NULL DEFAULT 0)`,
		`CREATE TABLE IF NOT EXISTS control_commands(ordering INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,device TEXT NOT NULL,enrollment TEXT NOT NULL,operation TEXT NOT NULL,instruction TEXT NOT NULL,state TEXT NOT NULL,result TEXT,issued TEXT NOT NULL)`,
		`CREATE INDEX IF NOT EXISTS control_device_order ON control_commands(device,ordering)`,
		`CREATE INDEX IF NOT EXISTS control_queued_expiry ON control_commands(json_extract(instruction,'$.expires')) WHERE state='queued'`,
		`CREATE TABLE IF NOT EXISTS control_nonces(device TEXT NOT NULL,nonce TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(device,nonce))`,
	} {
		if _, e := s.db.Exec(q); e != nil {
			return e
		}
	}
	return nil
}
func controlRead(r *http.Request, v any) error {
	b, e := io.ReadAll(io.LimitReader(r.Body, 65537))
	if e != nil {
		return e
	}
	if len(b) > 65536 {
		return errors.New("control body too large")
	}
	if _, e = decode(b); e != nil {
		return e
	}
	return json.Unmarshal(b, v)
}
func (s *Store) controlExchange(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "method", errors.New("POST required"))
		return
	}
	var env controlEnvelope
	if controlRead(r, &env) != nil {
		failure(w, 400, "invalid_control", errors.New("invalid envelope"))
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, e := s.db.Begin()
	if e != nil {
		failure(w, 503, "storage", e)
		return
	}
	defer tx.Rollback()
	var key, enrollment string
	var enabled int
	e = tx.QueryRow(`SELECT enrollment,secret,enabled FROM control_enrollment WHERE device=?`, env.Device).Scan(&enrollment, &key, &enabled)
	expected := controlMAC(key, "phone", env.Device, env.Enrollment, env.Data)
	if e != nil || enabled != 1 || env.Enrollment != enrollment || expected == "" || !hmac.Equal([]byte(expected), []byte(env.MAC)) {
		failure(w, 403, "untrusted", errors.New("remote control unavailable: invalid enrollment or authentication"))
		return
	}
	b, e := base64.StdEncoding.DecodeString(env.Data)
	var req controlRequest
	if e != nil || len(b) > 49152 {
		failure(w, 400, "invalid_control", errors.New("invalid data"))
		return
	}
	if _, e = decode(b); e != nil || json.Unmarshal(b, &req) != nil {
		failure(w, 400, "invalid_control", errors.New("invalid request"))
		return
	}
	st, e := loadReceiptState(tx)
	now := s.Now().UnixMilli()
	if e != nil || req.Version != 1 || req.Authority != st.Authority || len(req.Nonce) != 64 || req.At < now-60000 || req.At > now+60000 || len(req.Results) > 32 {
		failure(w, 403, "freshness", errors.New("authority, version or freshness mismatch"))
		return
	}
	if _, e = tx.Exec(`DELETE FROM control_nonces WHERE at<?`, now-120000); e != nil {
		failure(w, 503, "storage", e)
		return
	}
	var nonceCount int
	if e = tx.QueryRow(`SELECT COUNT(*) FROM control_nonces WHERE device=?`, env.Device).Scan(&nonceCount); e != nil {
		failure(w, 503, "storage", e)
		return
	}
	if nonceCount >= 256 {
		w.Header().Set("Retry-After", "30")
		failure(w, 429, "control_rate_limit", errors.New("control request rate limit"))
		return
	}
	if _, e = tx.Exec(`INSERT INTO control_nonces VALUES(?,?,?)`, env.Device, req.Nonce, req.At); e != nil {
		failure(w, 409, "replay", errors.New("request already used"))
		return
	}
	if _, e = tx.Exec(`DELETE FROM control_nonces WHERE at<?`, now-120000); e != nil {
		failure(w, 503, "storage", e)
		return
	}
	confirmed := map[string]string{}
	for _, res := range req.Results {
		if res.Revision < 1 || len(res.Reason) > 256 || len(res.Effective) > 1024 || res.Received < 1 {
			failure(w, 400, "invalid_result", errors.New("invalid execution evidence"))
			return
		}
		if (res.State == "applied" || strings.HasPrefix(res.State, "sync_") || res.State == "failed") && !res.Attempted {
			failure(w, 400, "invalid_result", errors.New("execution evidence requires attempt"))
			return
		}
		switch res.State {
		case "received", "applied", "blocked", "failed", "rejected", "expired", "cancelled", "superseded", "unsupported", "unknown", "sync_started", "sync_partial", "sync_complete", "output_attempted", "alert_blocked", "recipient_acknowledged":
		default:
			failure(w, 400, "invalid_result", errors.New("unknown result state"))
			return
		}
		var prior sql.NullString
		var ins, deliveryState string
		if e = tx.QueryRow(`SELECT instruction,result,state FROM control_commands WHERE id=? AND device=? AND enrollment=?`, res.ID, env.Device, enrollment).Scan(&ins, &prior, &deliveryState); e != nil {
			failure(w, 400, "invalid_result", errors.New("unknown target command"))
			return
		}
		var instruction controlInstruction
		_ = json.Unmarshal([]byte(ins), &instruction)
		if (res.State == "output_attempted" || res.State == "alert_blocked" || res.State == "recipient_acknowledged") && (instruction.Operation != "alert" || !res.Attempted) {
			failure(w, 400, "invalid_result", errors.New("alert evidence requires alert execution"))
			return
		}
		if res.Received < instruction.Created-60000 {
			failure(w, 400, "invalid_result", errors.New("invalid receipt time"))
			return
		}
		raw, _ := json.Marshal(res)
		var old controlResult
		if prior.Valid {
			if json.Unmarshal([]byte(prior.String), &old) != nil {
				failure(w, 503, "storage", errors.New("invalid persisted result"))
				return
			}
			if old.State != "sync_started" && old.State != "sync_partial" && old.State != "received" && old.State != "output_attempted" && old.State != "alert_blocked" && !(instruction.Operation == "alert" && old.State == "unknown" && res.State == "recipient_acknowledged") && res.Revision > old.Revision {
				failure(w, 409, "result_conflict", errors.New("terminal execution result is immutable"))
				return
			}
			if res.Revision < old.Revision {
				continue
			}
			if res.Revision == old.Revision && prior.String != string(raw) {
				failure(w, 409, "result_conflict", errors.New("conflicting result revision"))
				return
			}
		}
		nextState := res.State
		if (res.State == "received" || res.State == "output_attempted" || res.State == "alert_blocked") && deliveryState == "cancel_requested_unknown" {
			nextState = deliveryState
		}
		if _, e = tx.Exec(`UPDATE control_commands SET result=?,state=? WHERE id=?`, string(raw), nextState, res.ID); e != nil {
			failure(w, 503, "storage", e)
			return
		}
		// Hash the exact phone JSON result, not Go's reserialization: confirmation is
		// custody evidence for those exact bytes, independent of object field order.
		confirmed[res.ID] = controlResultDigest(b, res.ID)
	}
	if _, e = tx.Exec(`UPDATE control_enrollment SET last_seen=? WHERE device=?`, now, env.Device); e != nil {
		failure(w, 503, "storage", e)
		return
	}
	instructions := []controlInstruction{}
	if !req.ResultsOnly {
		rows, e := tx.Query(`SELECT instruction,state,result FROM control_commands WHERE device=? AND enrollment=? AND (result IS NULL OR state IN ('received','output_attempted','alert_blocked','cancel_requested_unknown')) AND state IN ('queued','sent_unknown','received','cancel_requested_unknown') ORDER BY CASE WHEN state='cancel_requested_unknown' THEN 0 ELSE 1 END,ordering LIMIT 1`, env.Device, enrollment)
		if e != nil {
			failure(w, 503, "storage", e)
			return
		}
		states := []string{}
		for rows.Next() {
			var raw, state string
			var result sql.NullString
			if e = rows.Scan(&raw, &state, &result); e != nil {
				break
			}
			var ins controlInstruction
			if e = json.Unmarshal([]byte(raw), &ins); e != nil {
				break
			}
			if ins.Expires <= now && state == "queued" {
				states = append(states, ins.ID)
				continue
			}
			ins.Disposition = state
			instructions = append(instructions, ins)
		}
		if er := rows.Err(); er != nil {
			e = er
		}
		rows.Close()
		if e != nil {
			failure(w, 503, "storage", e)
			return
		}
		for _, cid := range states {
			if _, e = tx.Exec(`UPDATE control_commands SET state='expired' WHERE id=?`, cid); e != nil {
				failure(w, 503, "storage", e)
				return
			}
		}
		for _, ins := range instructions {
			if ins.Disposition == "queued" {
				if _, e = tx.Exec(`UPDATE control_commands SET state='sent_unknown' WHERE id=?`, ins.ID); e != nil {
					failure(w, 503, "storage", e)
					return
				}
			}
		}
	}
	reply, _ := json.Marshal(map[string]any{"version": 1, "authority": st.Authority, "nonce": req.Nonce, "at": now, "commands": instructions, "confirmed": confirmed})
	data := base64.StdEncoding.EncodeToString(reply)
	if e = tx.Commit(); e != nil {
		failure(w, 503, "storage", e)
		return
	}
	respond(w, 200, controlEnvelope{env.Device, enrollment, data, controlMAC(key, "command", env.Device, enrollment, data)})
}
func controlResultDigest(b []byte, id string) string {
	var req struct {
		Results []json.RawMessage `json:"results"`
	}
	_ = json.Unmarshal(b, &req)
	for _, raw := range req.Results {
		var v struct {
			ID string `json:"id"`
		}
		_ = json.Unmarshal(raw, &v)
		if v.ID == id {
			sum := sha256.Sum256(raw)
			return hex.EncodeToString(sum[:])
		}
	}
	return ""
}

// controlLocal runs under the same loopback listener and same-origin mutation
// rules as all existing operator controls. Only this explicit enroll response
// contains a provisioning secret; it must never be included in diagnostics.
func (s *Store) controlLocal(w http.ResponseWriter, r *http.Request) bool {
	if !strings.HasPrefix(r.URL.Path, "/local/control") {
		return false
	}
	peer, _, peerErr := net.SplitHostPort(r.RemoteAddr)
	host, _, hostErr := net.SplitHostPort(r.Host)
	if hostErr != nil {
		host = r.Host
	}
	host = strings.Trim(host, "[]")
	hostIP := net.ParseIP(host)
	if peerErr != nil || !net.ParseIP(peer).IsLoopback() || (strings.ToLower(host) != "localhost" && (hostIP == nil || !hostIP.IsLoopback())) {
		failure(w, 403, "loopback_required", errors.New("open Phone controls through localhost or a loopback address on the Command computer"))
		return true
	}
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == "GET" && r.URL.Path == "/local/control" {
		s.mu.Lock()
		defer s.mu.Unlock()
		tx, e := s.db.Begin()
		if e != nil {
			failure(w, 503, "storage", e)
			return true
		}
		defer tx.Rollback()
		if _, e = tx.Exec(`UPDATE control_commands SET state='expired' WHERE state='queued' AND json_extract(instruction,'$.expires')<=?`, s.Now().UnixMilli()); e != nil {
			failure(w, 503, "storage", e)
			return true
		}
		// Expiry is durable even when the phone has never reconnected.
		if e = tx.Commit(); e != nil {
			failure(w, 503, "storage", e)
			return true
		}
		tx, e = s.db.Begin()
		if e != nil {
			failure(w, 503, "storage", e)
			return true
		}
		defer tx.Rollback()
		phones := map[string]any{}
		rows, e := tx.Query(`SELECT device,enrollment,enabled,last_seen FROM control_enrollment`)
		if e != nil {
			failure(w, 503, "storage", e)
			return true
		}
		for rows.Next() {
			var d, en string
			var enabled int
			var seen int64
			if e = rows.Scan(&d, &en, &enabled, &seen); e != nil {
				break
			}
			phones[d] = map[string]any{"enrollment": en, "enabled": enabled == 1, "last_seen": seen}
		}
		rows.Close()
		if e != nil {
			failure(w, 503, "storage", e)
			return true
		}
		history := []controlEntry{}
		rows, e = tx.Query(`SELECT instruction,state,result,issued FROM control_commands ORDER BY ordering DESC LIMIT 1000`)
		if e != nil {
			failure(w, 503, "storage", e)
			return true
		}
		for rows.Next() {
			var raw, state, issued string
			var result sql.NullString
			if e = rows.Scan(&raw, &state, &result, &issued); e != nil {
				break
			}
			var entry controlEntry
			_ = json.Unmarshal([]byte(raw), &entry.controlInstruction)
			entry.State = state
			entry.Issued = issued
			if result.Valid {
				_ = json.Unmarshal([]byte(result.String), &entry.Result)
			}
			history = append(history, entry)
		}
		rows.Close()
		if e != nil {
			failure(w, 503, "storage", e)
			return true
		}
		identity, e := loadReceiptState(tx)
		if e != nil {
			failure(w, 503, "storage", e)
			return true
		}
		respond(w, 200, map[string]any{"authority_id": identity.Authority, "phones": phones, "commands": history})
		return true
	}
	if r.Method != "POST" || (r.Header.Get("Origin") != "" && r.Header.Get("Origin") != "http://"+r.Host && r.Header.Get("Origin") != "https://"+r.Host) || !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		failure(w, 403, "forbidden", errors.New("same-origin JSON POST required"))
		return true
	}
	var v struct {
		Device    string          `json:"device"`
		Targets   []string        `json:"targets"`
		Operation string          `json:"operation"`
		Payload   json.RawMessage `json:"payload"`
		TTL       int64           `json:"ttl"`
		ID        string          `json:"id"`
		Confirmed bool            `json:"confirmed"`
	}
	if e := controlRead(r, &v); e != nil {
		failure(w, 400, "invalid_control", e)
		return true
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, e := s.db.Begin()
	if e != nil {
		failure(w, 503, "storage", e)
		return true
	}
	defer tx.Rollback()
	if _, e = tx.Exec(`UPDATE control_commands SET state='expired' WHERE state='queued' AND json_extract(instruction,'$.expires')<=?`, s.Now().UnixMilli()); e != nil {
		failure(w, 503, "storage", e)
		return true
	}
	st, e := loadReceiptState(tx)
	if e != nil {
		failure(w, 503, "storage", e)
		return true
	}
	var response any = map[string]bool{"ok": true}
	switch r.URL.Path {
	case "/local/control/enroll":
		if !v.Confirmed || st.Devices[v.Device] == nil {
			e = errors.New("confirm phone identity on Android before enrolling")
			break
		}
		enrollment, key := id(), randomControlKey()
		_, e = tx.Exec(`INSERT INTO control_enrollment(device,enrollment,secret,enabled) VALUES(?,?,?,1) ON CONFLICT(device) DO UPDATE SET enrollment=excluded.enrollment,secret=excluded.secret,enabled=1,last_seen=0`, v.Device, enrollment, key)
		if e == nil {
			_, e = tx.Exec(`UPDATE control_commands SET state='revoked_unknown' WHERE device=? AND (result IS NULL OR state IN ('received','output_attempted','alert_blocked','cancel_requested_unknown'))`, v.Device)
		}
		response = map[string]string{"credential": "gnss1:" + st.Authority + ":" + v.Device + ":" + enrollment + ":" + key}
	case "/local/control/revoke":
		if !v.Confirmed {
			e = errors.New("confirmation required")
			break
		}
		_, e = tx.Exec(`UPDATE control_enrollment SET enabled=0,secret='' WHERE device=?`, v.Device)
		if e == nil {
			_, e = tx.Exec(`UPDATE control_commands SET state='revoked_unknown' WHERE device=? AND (result IS NULL OR state IN ('received','output_attempted','alert_blocked','cancel_requested_unknown'))`, v.Device)
		}
	case "/local/control/cancel":
		// A sent command might already have executed. Preserve uncertainty and return
		// cancellation as a disposition; only Android can prove no effect occurred.
		_, e = tx.Exec(`UPDATE control_commands SET state=CASE WHEN state='queued' THEN 'cancelled' ELSE 'cancel_requested_unknown' END WHERE id=? AND (result IS NULL OR state IN ('received','output_attempted','alert_blocked')) AND state IN ('queued','sent_unknown','received','output_attempted','alert_blocked')`, v.ID)
	case "/local/control/issue":
		if !v.Confirmed || len(v.Targets) == 0 || v.TTL < 5 || v.TTL > 86400 {
			e = errors.New("confirm frozen recipients and TTL 5..86400 seconds")
			break
		}
		payload, e2 := decode(v.Payload)
		if e2 != nil {
			e = e2
			break
		}
		switch v.Operation {
		case "alert":
			mode, mok := payload["mode"].(string)
			message, sok := payload["message"].(string)
			durationNumber, dok := payload["duration_s"].(json.Number)
			repeatNumber, rok := payload["repeat_s"].(json.Number)
			duration, de := durationNumber.Int64()
			repeat, re := repeatNumber.Int64()
			if len(payload) != 4 || !mok || !sok || !dok || !rok || de != nil || re != nil || (mode != "normal" && mode != "urgent") || len([]rune(message)) > 160 || strings.ContainsAny(message, "\r\n") || v.TTL > 3600 || duration < 5 || duration > 120 || repeat < 5 || repeat > 30 || (mode == "normal" && (duration != 5 || repeat != 5)) {
				e = errors.New("alert requires mode normal/urgent, single-line message <=160 characters, TTL 5..3600s, duration 5..120s and repeat 5..30s; normal uses duration/repeat 5s")
			}
		case "reporting":
			if x, ok := payload["seconds"]; !ok || (x != nil && !interval(x)) {
				e = errors.New("invalid interval")
			}
		case "adaptive":
			if _, ok := payload["enabled"].(bool); !ok {
				e = errors.New("enabled boolean required")
			}
			if len(payload) != 1 {
				e = errors.New("policy revision 1 supports ON/OFF only")
			}
		case "sync", "tracking_start", "tracking_stop":
			if len(payload) != 0 {
				e = errors.New("payload must be empty")
			}
		default:
			e = errors.New("unsupported operation")
		}
		if e != nil {
			break
		}
		unique := map[string]bool{}
		ids := []string{}
		for _, device := range v.Targets {
			if unique[device] {
				continue
			}
			unique[device] = true
			var enrollment string
			var seen int64
			if e = tx.QueryRow(`SELECT enrollment,last_seen FROM control_enrollment WHERE device=? AND enabled=1`, device).Scan(&enrollment, &seen); e != nil {
				e = errors.New("recipient remote control unavailable")
				break
			}
			var count int
			if e = tx.QueryRow(`SELECT COUNT(*) FROM control_commands WHERE device=? AND (result IS NULL OR state IN ('received','output_attempted','alert_blocked','cancel_requested_unknown')) AND state IN ('queued','sent_unknown','received','output_attempted','alert_blocked','cancel_requested_unknown')`, device).Scan(&count); e != nil {
				break
			}
			if count >= 256 {
				e = errors.New("phone outstanding control storage limit reached")
				break
			}
			var retained int
			if e = tx.QueryRow(`SELECT COUNT(*) FROM control_commands WHERE device=?`, device).Scan(&retained); e != nil {
				break
			}
			if retained >= 10000 {
				e = errors.New("phone command journal full; preserve and archive before issuing more")
				break
			}
			now := s.Now()
			ins := controlInstruction{Priority: "normal", ID: id(), Authority: st.Authority, Device: device, Enrollment: enrollment, Operation: v.Operation, Version: 1, Payload: v.Payload, Created: now.UnixMilli(), Expires: now.Add(time.Duration(v.TTL) * time.Second).UnixMilli(), Disposition: "queued"}
			if v.Operation == "alert" && payload["mode"] == "urgent" {
				ins.Priority = "urgent"
			}
			if v.Operation == "reporting" {
				d := st.Devices[device]
				if d == nil || d.Desired.Version >= maxInteger {
					e = errors.New("configuration unavailable")
					break
				}
				d.Desired.Version++
				d.Desired.Override = nil
				if payload["seconds"] != nil {
					n := int(number(payload["seconds"]))
					d.Desired.Override = &n
				}
				ins.Payload, _ = json.Marshal(map[string]any{"seconds": d.Desired.Override, "revision": d.Desired.Version})
			}
			if v.Operation == "reporting" || v.Operation == "adaptive" {
				if _, e = tx.Exec(`UPDATE control_commands SET state='superseded' WHERE device=? AND operation=? AND state='queued' AND result IS NULL`, device, v.Operation); e != nil {
					break
				}
			}
			raw, _ := json.Marshal(ins)
			var res sql.Result
			res, e = tx.Exec(`INSERT INTO control_commands(id,device,enrollment,operation,instruction,state,issued) VALUES(?,?,?,?,?,'queued',?)`, ins.ID, device, enrollment, v.Operation, string(raw), now.UTC().Format(time.RFC3339Nano))
			if e != nil {
				break
			}
			ins.Ordering, e = res.LastInsertId()
			if e != nil {
				break
			}
			raw, _ = json.Marshal(ins)
			_, e = tx.Exec(`UPDATE control_commands SET instruction=? WHERE id=?`, string(raw), ins.ID)
			if e != nil {
				break
			}
			ids = append(ids, ins.ID)
		}
		if e == nil {
			st.ReceiptVersion++
			e = saveReceiptState(tx, st)
		}
		response = map[string]any{"ids": ids}
	default:
		e = errors.New("unknown control endpoint")
	}
	if e == nil {
		e = tx.Commit()
	}
	if e != nil {
		failure(w, 400, "control_unavailable", e)
	} else {
		respond(w, 200, response)
	}
	return true
}

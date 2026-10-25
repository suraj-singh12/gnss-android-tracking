package core

import (
	"bytes"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func controlLoopback(r *http.Request) *http.Request {
	r.RemoteAddr = "127.0.0.1:12345"
	r.Host = "localhost"
	return r
}
func controlAPI(t *testing.T, s *Store, path string, value any, want int) map[string]json.RawMessage {
	t.Helper()
	r := httptest.NewRequest("POST", "http://localhost/local/control/"+path, bytes.NewReader(encode(t, value)))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.LocalHandler(nil).ServeHTTP(w, controlLoopback(r))
	if w.Code != want {
		t.Fatalf("%s: %d %s", path, w.Code, w.Body)
	}
	v := map[string]json.RawMessage{}
	_ = json.Unmarshal(w.Body.Bytes(), &v)
	return v
}
func controlEnroll(t *testing.T, s *Store, d string) (string, string, string) {
	t.Helper()
	v := controlAPI(t, s, "enroll", map[string]any{"device": d, "confirmed": true}, 200)
	var token string
	_ = json.Unmarshal(v["credential"], &token)
	p := strings.Split(token, ":")
	return p[1], p[3], p[4]
}
func controlPoll(t *testing.T, s *Store, d, en, key, authority, nonce string, results []controlResult, want int) (map[string]json.RawMessage, []byte) {
	t.Helper()
	req := controlRequest{1, authority, nonce, s.Now().UnixMilli(), results, false}
	data := base64.StdEncoding.EncodeToString(encode(t, req))
	env := controlEnvelope{d, en, data, controlMAC(key, "phone", d, en, data)}
	b := encode(t, env)
	r := httptest.NewRequest("POST", "http://phone/api/v1/control", bytes.NewReader(b))
	w := httptest.NewRecorder()
	s.IngestHandler().ServeHTTP(w, r)
	if w.Code != want {
		t.Fatalf("poll %d %s", w.Code, w.Body)
	}
	v := map[string]json.RawMessage{}
	if want == 200 {
		var reply controlEnvelope
		if e := json.Unmarshal(w.Body.Bytes(), &reply); e != nil {
			t.Fatal(e)
		}
		if reply.MAC != controlMAC(key, "command", d, en, reply.Data) {
			t.Fatal("unauthenticated response")
		}
		raw, e := base64.StdEncoding.DecodeString(reply.Data)
		if e != nil {
			t.Fatal(e)
		}
		if e = json.Unmarshal(raw, &v); e != nil {
			t.Fatal(e)
		}
	}
	return v, b
}
func TestControlAuthenticatedLifecycleRestartReplayRevocation(t *testing.T) {
	s, now, path := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	a, en, k := controlEnroll(t, s, device)
	issued := controlAPI(t, s, "issue", map[string]any{"targets": []string{device}, "operation": "reporting", "payload": map[string]any{"seconds": 5}, "ttl": 300, "confirmed": true}, 200)
	var ids []string
	_ = json.Unmarshal(issued["ids"], &ids)
	v, replay := controlPoll(t, s, device, en, k, a, randomControlKey(), nil, 200)
	var commands []controlInstruction
	_ = json.Unmarshal(v["commands"], &commands)
	if len(commands) != 1 || commands[0].ID != ids[0] || commands[0].Ordering < 1 {
		t.Fatal("not durably queued", commands)
	}
	if e := s.Close(); e != nil {
		t.Fatal(e)
	}
	restored, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer restored.Close()
	restored.Now = func() time.Time { return *now }
	w := httptest.NewRecorder()
	restored.IngestHandler().ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/control", bytes.NewReader(replay)))
	if w.Code != 409 {
		t.Fatal("restart replay accepted", w.Code)
	}
	controlPoll(t, restored, device, en, randomControlKey(), a, randomControlKey(), nil, 403)
	controlPoll(t, restored, device, en, k, id(), randomControlKey(), nil, 403)
	result := controlResult{ids[0], 2, "applied", "Reporting interval applied", "5 seconds", base.UnixMilli(), true}
	v, _ = controlPoll(t, restored, device, en, k, a, randomControlKey(), []controlResult{result}, 200)
	if string(v["confirmed"]) == "{}" {
		t.Fatal("missing durable result custody")
	}
	controlPoll(t, restored, device, en, k, a, randomControlKey(), []controlResult{result}, 200)
	result.State = "blocked"
	controlPoll(t, restored, device, en, k, a, randomControlKey(), []controlResult{result}, 409)
	controlAPI(t, restored, "cancel", map[string]any{"id": ids[0]}, 200)
	var state string
	if e = restored.db.QueryRow(`SELECT state FROM control_commands WHERE id=?`, ids[0]).Scan(&state); e != nil || state != "applied" {
		t.Fatal("late cancellation hid effect", state, e)
	}
	controlAPI(t, restored, "revoke", map[string]any{"device": device, "confirmed": true}, 200)
	controlPoll(t, restored, device, en, k, a, randomControlKey(), nil, 403)
	a2, en2, k2 := controlEnroll(t, restored, device)
	if a2 != a || en2 == en || k2 == k {
		t.Fatal("authority replaced or credential reused")
	}
	controlPoll(t, restored, device, en, k, a, randomControlKey(), nil, 403)
	controlPoll(t, restored, device, en2, k2, a, randomControlKey(), nil, 200)
}
func TestControlFleetFrozenRecipients(t *testing.T) {
	for _, n := range []int{1, 5, 20} {
		t.Run(fmt.Sprint(n), func(t *testing.T) {
			s, now, _ := open(t)
			*now = base
			type phone struct{ d, a, en, k string }
			phones := []phone{}
			targets := []string{}
			for i := 0; i < n; i++ {
				m := message(t, 1, 0, 0, 0, 1)
				m.Device = fmt.Sprintf("11111111-1111-4111-8111-%012d", i)
				ingest(t, s, m)
				a, en, k := controlEnroll(t, s, m.Device)
				phones = append(phones, phone{m.Device, a, en, k})
				targets = append(targets, m.Device)
			}
			controlAPI(t, s, "issue", map[string]any{"targets": targets, "operation": "adaptive", "payload": map[string]any{"enabled": true}, "ttl": 5, "confirmed": true}, 200)
			late := message(t, 1, 0, 0, 0, 1)
			late.Device = "22222222-2222-4222-8222-222222222222"
			ingest(t, s, late)
			a, en, k := controlEnroll(t, s, late.Device)
			v, _ := controlPoll(t, s, late.Device, en, k, a, randomControlKey(), nil, 200)
			if string(v["commands"]) != "[]" {
				t.Fatal("late join targeted")
			}
			for _, p := range phones {
				v, _ = controlPoll(t, s, p.d, p.en, p.k, p.a, randomControlKey(), nil, 200)
				var commands []controlInstruction
				_ = json.Unmarshal(v["commands"], &commands)
				if len(commands) != 1 || commands[0].Device != p.d {
					t.Fatal("cross phone delivery")
				}
			}
			*now = base.Add(10 * time.Second)
			p := phones[0]
			v, _ = controlPoll(t, s, p.d, p.en, p.k, p.a, randomControlKey(), nil, 200)
			var expired []controlInstruction
			_ = json.Unmarshal(v["commands"], &expired)
			if len(expired) != 1 || expired[0].Expires > now.UnixMilli() {
				t.Fatal("expiry evidence missing")
			}
		})
	}
}
func TestControlLocalOriginAndLegacy(t *testing.T) {
	s, _, _ := open(t)
	r := httptest.NewRequest("POST", "http://localhost/local/control/enroll", strings.NewReader(`{"device":"x","confirmed":true}`))
	r.Header.Set("Origin", "http://attacker")
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.LocalHandler(nil).ServeHTTP(w, controlLoopback(r))
	if w.Code != 403 {
		t.Fatal("cross origin mutation")
	}
	m := message(t, 1, 0, 0, 0, 1)
	w = httptest.NewRecorder()
	r = httptest.NewRequest("POST", "/api/v1/messages", bytes.NewReader(encode(t, m)))
	r.Header.Set("Content-Type", "application/json")
	s.IngestHandler().ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatal("legacy GNSS broken", w.Body)
	}
	w = httptest.NewRecorder()
	s.LocalHandler(nil).ServeHTTP(w, controlLoopback(httptest.NewRequest("GET", "/local/control", nil)))
	if strings.Contains(w.Body.String(), "secret") || strings.Contains(w.Body.String(), "credential") {
		t.Fatal("credential in ordinary view")
	}
}

func TestControlSharedAuthenticationVector(t *testing.T) {
	var v map[string]string
	if e := json.Unmarshal(fixture(t, "control-v1/authentication-vector.json"), &v); e != nil {
		t.Fatal(e)
	}
	if got := controlMAC(v["test_only_key"], v["direction"], v["device"], v["enrollment"], v["data"]); got != v["mac"] {
		t.Fatal("cross-language authentication vector", got)
	}
}

func TestControlSupersessionCrossPhoneResultsAndOfflineExpiry(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	m := message(t, 1, 0, 0, 0, 1)
	ingest(t, s, m)
	a, en, k := controlEnroll(t, s, m.Device)
	other := m
	other.Device = id()
	ingest(t, s, other)
	a2, en2, k2 := controlEnroll(t, s, other.Device)
	issue := func(seconds int, ttl int) string {
		v := controlAPI(t, s, "issue", map[string]any{"targets": []string{m.Device}, "operation": "reporting", "payload": map[string]any{"seconds": seconds}, "ttl": ttl, "confirmed": true}, 200)
		var ids []string
		_ = json.Unmarshal(v["ids"], &ids)
		return ids[0]
	}
	old := issue(5, 300)
	latest := issue(15, 300)
	var state string
	if e := s.db.QueryRow(`SELECT state FROM control_commands WHERE id=?`, old).Scan(&state); e != nil || state != "superseded" {
		t.Fatal("queued supersession", state, e)
	}
	v, _ := controlPoll(t, s, m.Device, en, k, a, randomControlKey(), nil, 200)
	var cmds []controlInstruction
	_ = json.Unmarshal(v["commands"], &cmds)
	if len(cmds) != 1 || cmds[0].ID != latest {
		t.Fatal("old setting delivered", cmds)
	}
	// A valid credential for another phone cannot acknowledge this command.
	controlPoll(t, s, other.Device, en2, k2, a2, randomControlKey(), []controlResult{{latest, 2, "applied", "forged cross-phone", "15", base.UnixMilli(), true}}, 400)
	var result sql.NullString
	if e := s.db.QueryRow(`SELECT result FROM control_commands WHERE id=?`, latest).Scan(&result); e != nil || result.Valid {
		t.Fatal("cross-phone result stored", result, e)
	}
	if e := s.SetOverride(m.Device, nil); e == nil {
		t.Fatal("legacy local form bypassed secure control lifecycle")
	}
	expiring := issue(20, 5)
	*now = base.Add(6 * time.Second)
	w := httptest.NewRecorder()
	s.LocalHandler(nil).ServeHTTP(w, controlLoopback(httptest.NewRequest("GET", "/local/control", nil)))
	if w.Code != 200 {
		t.Fatal(w.Body)
	}
	if e := s.db.QueryRow(`SELECT state FROM control_commands WHERE id=?`, expiring).Scan(&state); e != nil || state != "expired" {
		t.Fatal("offline expiry not durable", state, e)
	}
	if strings.Contains(w.Body.String(), k) || strings.Contains(w.Body.String(), k2) {
		t.Fatal("secret leaked in control view")
	}
}

func TestControlCredentialDatabaseAndWALArePrivate(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows requires native ACL acceptance")
	}
	path := filepath.Join(t.TempDir(), "existing-public.sqlite")
	if e := os.WriteFile(path, nil, 0644); e != nil {
		t.Fatal(e)
	}
	s, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	controlEnroll(t, s, device)
	for _, file := range []string{path, path + "-wal", path + "-shm"} {
		info, e := os.Stat(file)
		if os.IsNotExist(e) && file != path {
			continue
		}
		if e != nil {
			t.Fatal(e)
		}
		if info.Mode().Perm() != 0600 {
			t.Fatalf("credential storage mode %o", info.Mode().Perm())
		}
	}
}

func TestControlStopResultOnlyFlushDoesNotDispatchNextInstruction(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	a, en, k := controlEnroll(t, s, device)
	issue := func(op string) string {
		v := controlAPI(t, s, "issue", map[string]any{"targets": []string{device}, "operation": op, "payload": map[string]any{}, "ttl": 300, "confirmed": true}, 200)
		var ids []string
		_ = json.Unmarshal(v["ids"], &ids)
		return ids[0]
	}
	stop, next := issue("tracking_stop"), issue("sync")
	controlPoll(t, s, device, en, k, a, randomControlKey(), nil, 200)
	result := controlResult{stop, 2, "applied", "Stopped and verified", "", base.UnixMilli(), true}
	data := base64.StdEncoding.EncodeToString(encode(t, controlRequest{1, a, randomControlKey(), base.UnixMilli(), []controlResult{result}, true}))
	body := encode(t, controlEnvelope{device, en, data, controlMAC(k, "phone", device, en, data)})
	w := httptest.NewRecorder()
	s.IngestHandler().ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/control", bytes.NewReader(body)))
	if w.Code != 200 {
		t.Fatal(w.Body)
	}
	var response controlEnvelope
	_ = json.Unmarshal(w.Body.Bytes(), &response)
	raw, _ := base64.StdEncoding.DecodeString(response.Data)
	var reply map[string]json.RawMessage
	_ = json.Unmarshal(raw, &reply)
	if string(reply["commands"]) != "[]" || string(reply["confirmed"]) == "{}" {
		t.Fatal("result-only flush dispatched work or lacked custody")
	}
	var state string
	if e := s.db.QueryRow(`SELECT state FROM control_commands WHERE id=?`, next).Scan(&state); e != nil || state != "queued" {
		t.Fatal("next instruction incorrectly dispatched", state, e)
	}
}

func TestControlRejectsRemoteConsoleAndDNSRebindingHost(t *testing.T) {
	s, _, _ := open(t)
	for _, remote := range []bool{true, false} {
		r := controlLoopback(httptest.NewRequest("GET", "/local/control", nil))
		if remote {
			r.RemoteAddr = "192.168.1.22:1234"
		} else {
			r.Host = "attacker.example:8081"
		}
		w := httptest.NewRecorder()
		s.LocalHandler(nil).ServeHTTP(w, r)
		if w.Code != 403 {
			t.Fatal("remote or rebinding console access allowed", w.Code)
		}
	}
}

func TestControlReceivedIsNonterminalAndCancellationSurvivesReceiptRetry(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	a, en, k := controlEnroll(t, s, device)
	v := controlAPI(t, s, "issue", map[string]any{"targets": []string{device}, "operation": "sync", "payload": map[string]any{}, "ttl": 300, "confirmed": true}, 200)
	var ids []string
	_ = json.Unmarshal(v["ids"], &ids)
	controlPoll(t, s, device, en, k, a, randomControlKey(), nil, 200)
	receipt := controlResult{ids[0], 1, "received", "Durable receipt", "", base.UnixMilli(), false}
	check := func(want string) {
		t.Helper()
		v, _ := controlPoll(t, s, device, en, k, a, randomControlKey(), []controlResult{receipt}, 200)
		var commands []controlInstruction
		_ = json.Unmarshal(v["commands"], &commands)
		if len(commands) != 1 || commands[0].ID != ids[0] || commands[0].Disposition != want {
			t.Fatalf("receipt lost pending instruction: %s", v["commands"])
		}
	}
	check("received")
	controlAPI(t, s, "cancel", map[string]any{"id": ids[0]}, 200)
	check("cancel_requested_unknown")
	result := receipt
	result.Revision = 2
	result.State = "cancelled"
	v, _ = controlPoll(t, s, device, en, k, a, randomControlKey(), []controlResult{result}, 200)
	if string(v["commands"]) != "[]" {
		t.Fatal("terminal cancellation redelivered", v)
	}
}

func TestControlPrivateSQLiteURIPreservesPlatformFilenames(t *testing.T) {
	for _, path := range []string{"C:/Users/Field Team/command?#%.sqlite", "/tmp/Field Team/command?#%.sqlite"} {
		uri := privateURI(path)
		encoded := strings.TrimSuffix(strings.TrimPrefix(uri, "file:"), "?vfs=gnss-private")
		decoded, err := url.PathUnescape(encoded)
		if err != nil || decoded != path || strings.HasPrefix(encoded, "//") {
			t.Fatalf("filename changed: %q %v", uri, err)
		}
	}
	s, err := Open(filepath.Join(t.TempDir(), "field team?#%.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	controlEnroll(t, s, device)
}

func TestPhoneAlertDurableLifecycleAndCancellationEvidence(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	a, en, k := controlEnroll(t, s, device)
	issue := func(mode string) string {
		v := controlAPI(t, s, "issue", map[string]any{"targets": []string{device}, "operation": "alert", "payload": map[string]any{"mode": mode, "message": "Check in", "duration_s": 5, "repeat_s": 5}, "ttl": 300, "confirmed": true}, 200)
		var ids []string
		json.Unmarshal(v["ids"], &ids)
		return ids[0]
	}
	cid := issue("urgent")
	v, _ := controlPoll(t, s, device, en, k, a, randomControlKey(), nil, 200)
	var cmds []controlInstruction
	json.Unmarshal(v["commands"], &cmds)
	if len(cmds) != 1 || cmds[0].Priority != "urgent" {
		t.Fatal(cmds)
	}
	output := controlResult{cid, 2, "output_attempted", "Notification attempted; heard unknown", "", base.UnixMilli(), true}
	controlPoll(t, s, device, en, k, a, randomControlKey(), []controlResult{output}, 200)
	controlAPI(t, s, "cancel", map[string]any{"id": cid}, 200)
	v, _ = controlPoll(t, s, device, en, k, a, randomControlKey(), []controlResult{output}, 200)
	json.Unmarshal(v["commands"], &cmds)
	if len(cmds) != 1 || cmds[0].Disposition != "cancel_requested_unknown" {
		t.Fatal("cancel custody race", cmds)
	}
	ack := controlResult{cid, 3, "recipient_acknowledged", "Local user button", "", base.UnixMilli(), true}
	controlPoll(t, s, device, en, k, a, randomControlKey(), []controlResult{ack}, 200)
	controlPoll(t, s, device, en, k, a, randomControlKey(), []controlResult{ack}, 200)
	controlAPI(t, s, "cancel", map[string]any{"id": cid}, 200)
	var state string
	s.db.QueryRow(`SELECT state FROM control_commands WHERE id=?`, cid).Scan(&state)
	if state != "recipient_acknowledged" {
		t.Fatal(state)
	}
	ack.State = "cancelled"
	ack.Revision++
	controlPoll(t, s, device, en, k, a, randomControlKey(), []controlResult{ack}, 409)
	cid = issue("normal")
	controlPoll(t, s, device, en, k, a, randomControlKey(), nil, 200)
	output.ID = cid
	output.State = "alert_blocked"
	controlPoll(t, s, device, en, k, a, randomControlKey(), []controlResult{output}, 200)
	controlAPI(t, s, "cancel", map[string]any{"id": cid}, 200)
	output.State = "cancelled"
	output.Revision++
	controlPoll(t, s, device, en, k, a, randomControlKey(), []controlResult{output}, 200)
	s.db.QueryRow(`SELECT state FROM control_commands WHERE id=?`, cid).Scan(&state)
	if state != "cancelled" {
		t.Fatal(state)
	}
}

func TestPhoneAlertPayloadValidation(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	controlEnroll(t, s, device)
	for _, payload := range []map[string]any{
		{"mode": "urgent", "message": "x", "duration_s": 121, "repeat_s": 10},
		{"mode": "urgent", "message": "x", "duration_s": 60, "repeat_s": 0},
		{"mode": "urgent", "message": "x", "duration_s": 60.5, "repeat_s": 10},
		{"mode": "normal", "message": strings.Repeat("x", 161), "duration_s": 5, "repeat_s": 5},
		{"mode": "normal", "message": "x\ny", "duration_s": 5, "repeat_s": 5},
		{"mode": "unknown", "message": "x", "duration_s": 5, "repeat_s": 5},
	} {
		controlAPI(t, s, "issue", map[string]any{"targets": []string{device}, "operation": "alert", "payload": payload, "ttl": 300, "confirmed": true}, 400)
	}
}

func TestPhoneAlertSharedFixtures(t *testing.T) {
	b, e := os.ReadFile("../../../protocol/fixtures/control-v1/alerts.json")
	if e != nil {
		t.Fatal(e)
	}
	var fixtures map[string]json.RawMessage
	if e = json.Unmarshal(b, &fixtures); e != nil {
		t.Fatal(e)
	}
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	controlEnroll(t, s, device)
	for _, name := range []string{"normal", "urgent", "invalid_duration"} {
		want := 200
		if name == "invalid_duration" {
			want = 400
		}
		controlAPI(t, s, "issue", map[string]any{"targets": []string{device}, "operation": "alert", "payload": fixtures[name], "ttl": 300, "confirmed": true}, want)
	}
}

func TestControlPublicIdentityProjection(t *testing.T) {
	s, _, _ := open(t)
	// Identity is read from existing persistent authority, not an enrollment or instruction ID.
	w := httptest.NewRecorder()
	s.LocalHandler(nil).ServeHTTP(w, controlLoopback(httptest.NewRequest("GET", "http://localhost/local/control", nil)))
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var projection struct {
		Authority string `json:"authority_id"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &projection); err != nil {
		t.Fatal(err)
	}
	s.mu.Lock()
	tx, err := s.db.Begin()
	if err != nil {
		s.mu.Unlock()
		t.Fatal(err)
	}
	state, err := loadReceiptState(tx)
	tx.Rollback()
	s.mu.Unlock()
	if err != nil || projection.Authority == "" || projection.Authority != state.Authority {
		t.Fatal("persistent authority projection mismatch", err)
	}
	if strings.Contains(w.Body.String(), "credential") || strings.Contains(w.Body.String(), "secret") {
		t.Fatal("private provisioning leaked")
	}
	w = httptest.NewRecorder()
	r := httptest.NewRequest("GET", "http://localhost/local/control", nil)
	r.RemoteAddr = "192.168.1.2:12345"
	s.LocalHandler(nil).ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal("identity projection must retain loopback boundary", w.Code)
	}
}

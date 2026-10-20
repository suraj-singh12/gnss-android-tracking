package core

import (
	"bytes"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func controlAPI(t *testing.T, s *Store, path string, value any, want int) map[string]json.RawMessage {
	t.Helper()
	r := httptest.NewRequest("POST", "http://localhost/local/control/"+path, bytes.NewReader(encode(t, value)))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.LocalHandler(nil).ServeHTTP(w, r)
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
	req := controlRequest{1, authority, nonce, s.Now().UnixMilli(), results}
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
	s.LocalHandler(nil).ServeHTTP(w, r)
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
	s.LocalHandler(nil).ServeHTTP(w, httptest.NewRequest("GET", "/local/control", nil))
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
	s.LocalHandler(nil).ServeHTTP(w, httptest.NewRequest("GET", "/local/control", nil))
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

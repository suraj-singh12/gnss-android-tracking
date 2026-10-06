package mockreceiver

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const authority = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "..", "protocol", "fixtures", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}
func receiver(t *testing.T) *Receiver {
	r, err := New(authority, func() time.Time { return time.Date(2026, 10, 6, 12, 0, 1, 0, time.UTC) })
	if err != nil {
		t.Fatal(err)
	}
	return r
}
func post(r *Receiver, b []byte) *httptest.ResponseRecorder {
	req := httptest.NewRequest("POST", "/api/v1/messages", bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}
func TestGoldenMessagesAndDuplicate(t *testing.T) {
	files, _ := filepath.Glob(filepath.Join("..", "..", "protocol", "fixtures", "*.json"))
	for _, file := range files {
		name := filepath.Base(file)
		if strings.HasPrefix(name, "ack-") || strings.HasPrefix(name, "scenario-") {
			continue
		}
		t.Run(name, func(t *testing.T) {
			r := receiver(t)
			b := fixture(t, name)
			first := post(r, b)
			if first.Code != 200 {
				t.Fatalf("%d %s", first.Code, first.Body)
			}
			ack, err := decode(first.Body.Bytes())
			if err != nil || checkAck(ack) != nil {
				t.Fatal("invalid ACK", err)
			}
			second := post(r, b)
			other, _ := decode(second.Body.Bytes())
			if other["result"] != "duplicate" || other["received_at"] != ack["received_at"] || r.Count() != 1 {
				t.Fatal("duplicate semantics")
			}
		})
	}
}
func TestDroppedResponseAfterStoreThenDuplicate(t *testing.T) {
	r := receiver(t)
	_ = r.Queue(Step{DropAfterStore: true})
	server := httptest.NewServer(r)
	defer server.Close()
	b := fixture(t, "location-normal.json")
	resp, err := http.Post(server.URL+"/api/v1/messages", "application/json", bytes.NewReader(b))
	if err == nil {
		resp.Body.Close()
		t.Fatal("expected dropped response")
	}
	if r.Count() != 1 {
		t.Fatal("must store before dropping")
	}
	resp, err = http.Post(server.URL+"/api/v1/messages", "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	m, _ := decode(data)
	if m["result"] != "duplicate" {
		t.Fatal("expected duplicate")
	}
}
func TestFaultsConfigReplayAndInspection(t *testing.T) {
	r := receiver(t)
	b := fixture(t, "location-normal.json")
	device := "11111111-1111-4111-8111-111111111111"
	v := 30
	newer := Config{authority, 2, &v}
	clear := Config{authority, 3, nil}
	other := Config{"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", 4, nil}
	if err := r.SetConfig(device, newer); err != nil {
		t.Fatal(err)
	}
	if err := r.Queue(Step{Status: 503}, Step{Status: 429, RetryAfter: "60"}, Step{Status: 409}, Step{}, Step{Config: &clear}, Step{Config: &newer}, Step{Config: &other}); err != nil {
		t.Fatal(err)
	}
	for _, status := range []int{503, 429, 409} {
		w := post(r, b)
		if w.Code != status {
			t.Fatal(w.Code)
		}
		if r.Count() != 0 {
			t.Fatal("fault must not store")
		}
	}
	for _, want := range []Config{newer, clear, newer, other} {
		w := post(r, b)
		var ack struct {
			Config Config `json:"config"`
		}
		_ = json.Unmarshal(w.Body.Bytes(), &ack)
		if ack.Config.Authority != want.Authority || ack.Config.Version != want.Version {
			t.Fatal("config replay")
		}
	}
	captures := r.Captures()
	if len(captures) != 7 || r.Count() != 1 {
		t.Fatal("capture count")
	}
	captures[0].Body[0] = 'X'
	if r.Captures()[0].Body[0] == 'X' {
		t.Fatal("mutable captures")
	}
}
func TestIdentityConflictAndAdditiveSemanticEquality(t *testing.T) {
	r := receiver(t)
	b := fixture(t, "location-normal.json")
	_ = post(r, b)
	m, _ := decode(b)
	m["future"] = true
	object(m["fix"])["future"] = true
	object(m["fix"])["horizontal_accuracy_m"] = json.Number("3")
	replay, _ := json.Marshal(m)
	if w := post(r, replay); w.Code != 200 {
		t.Fatal("semantic duplicate rejected", w.Body)
	}
	object(m["party"])["name"] = "Changed"
	conflict, _ := json.Marshal(m)
	if post(r, conflict).Code != 409 {
		t.Fatal("changed content accepted")
	}
	m, _ = decode(b)
	m["message_id"] = "00000000-0000-4000-8000-000000000099"
	conflict, _ = json.Marshal(m)
	if post(r, conflict).Code != 409 {
		t.Fatal("sequence reuse accepted")
	}
}
func TestMalformedRequests(t *testing.T) {
	r := receiver(t)
	for _, body := range []string{`{}`, `{"protocol_version":1,"protocol_version":1}`, `{"protocol_version":1.0}`, `{"protocol_version":1} trailing`} {
		if post(r, []byte(body)).Code != 400 {
			t.Fatal("accepted malformed", body)
		}
	}
	if post(r, []byte(`{"protocol_version":2}`)).Code != 426 {
		t.Fatal("unsupported protocol")
	}
	m, _ := decode(fixture(t, "status-no-fix.json"))
	delete(object(m["health"]), "wifi_rssi_dbm")
	b, _ := json.Marshal(m)
	if post(r, b).Code != 400 {
		t.Fatal("missing nullable field")
	}
	if post(r, bytes.Repeat([]byte(" "), 65537)).Code != 413 {
		t.Fatal("body limit")
	}
}

func TestCaptureWriterEmitsInspectableJSON(t *testing.T) {
	r := receiver(t)
	var output bytes.Buffer
	r.SetCaptureWriter(&output)
	b := fixture(t, "location-normal.json")
	post(r, b)
	post(r, b)
	decoder := json.NewDecoder(&output)
	for _, result := range []string{"stored", "duplicate"} {
		var capture Capture
		if err := decoder.Decode(&capture); err != nil {
			t.Fatal(err)
		}
		if capture.Result != result || capture.Status != 200 {
			t.Fatal("capture outcome")
		}
		if _, err := decode(capture.Body); err != nil {
			t.Fatal("unreadable capture", err)
		}
	}
}

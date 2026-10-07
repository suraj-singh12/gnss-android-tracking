package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRetryLossAndMismatch(t *testing.T) {
	for _, mode := range []string{"response_loss", "transient", "identity_mismatch", "invalid_config"} {
		t.Run(mode, func(t *testing.T) {
			root := t.TempDir()
			request, err := os.ReadFile("../../protocol/fixtures/location-normal.json")
			if err != nil {
				t.Fatal(err)
			}
			ack, err := os.ReadFile("../../protocol/fixtures/ack-stored.json")
			if err != nil {
				t.Fatal(err)
			}
			for name, b := range map[string][]byte{"request.json": request, "ack.json": ack} {
				if err := os.WriteFile(filepath.Join(root, name), b, 0600); err != nil {
					t.Fatal(err)
				}
			}
			script, _ := json.Marshal(scenario{Name: mode, Steps: []step{{Request: "request.json", Ack: "ack.json", Drop: mode == "response_loss"}}})
			path := filepath.Join(root, "scenario.json")
			if err := os.WriteFile(path, script, 0600); err != nil {
				t.Fatal(err)
			}
			count := 0
			var previous map[string]any
			stored := false
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				count++
				var m map[string]any
				if err := json.NewDecoder(r.Body).Decode(&m); err != nil {
					t.Error(err)
				}
				if previous != nil {
					a, _ := json.Marshal(previous)
					b, _ := json.Marshal(m)
					if string(a) != string(b) {
						t.Error("retry content changed")
					}
				}
				previous = m
				if mode == "transient" && count == 1 {
					w.WriteHeader(503)
					_, _ = w.Write([]byte(`{"error":"storage_unavailable"}`))
					return
				}
				var a map[string]any
				_ = json.Unmarshal(ack, &a)
				for _, k := range []string{"device_id", "message_id", "sequence"} {
					a[k] = m[k]
				}
				if stored {
					a["result"] = "duplicate"
				}
				stored = true
				if mode == "identity_mismatch" {
					a["sequence"] = 999
				}
				if mode == "invalid_config" {
					a["config"] = nil
				}
				_ = json.NewEncoder(w).Encode(a)
			}))
			defer server.Close()
			err = run(options{URL: server.URL, Path: path, Device: "33333333-3333-4333-8333-333333333333", Shift: time.Hour, Retries: 2, RetryDelay: time.Millisecond})
			if strings.Contains(mode, "mismatch") || mode == "invalid_config" {
				if err == nil {
					t.Fatal("accepted invalid ACK")
				}
			} else {
				if err != nil {
					t.Fatal(err)
				}
				if count != 2 {
					t.Fatal("missing retry", count)
				}
				if previous["captured_at"] != "2026-10-06T13:00:00.000Z" {
					t.Fatal("timestamp shift")
				}
			}
		})
	}
}

func TestStrictACKShape(t *testing.T) {
	b, err := os.ReadFile("../../protocol/fixtures/ack-stored.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = validateAck(b); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{strings.Replace(string(b), `"sequence": 1`, `"sequence": 1.0`, 1), strings.Replace(string(b), `"version": 0`, `"version": -1`, 1), strings.Replace(string(b), `"result": "stored"`, `"result": "stored", "result": "duplicate"`, 1), strings.Replace(string(b), `"reporting_interval_override_s": null`, `"reporting_interval_override_s": 30`, 1)} {
		if err := validateAck([]byte(bad)); err == nil {
			t.Fatal("accepted malformed ACK", bad)
		}
	}
}

func TestFiveSecondAckContract(t *testing.T) {
	b, err := os.ReadFile("../../protocol/fixtures/ack-five-second.json")
	if err != nil {
		t.Fatal(err)
	}
	for _, n := range []string{"5", "10", "15", "20", "30", "60", "86400", "1", "6", "86405"} {
		changed := strings.Replace(string(b), `"reporting_interval_override_s": 5`, `"reporting_interval_override_s": `+n, 1)
		valid := n != "1" && n != "6" && n != "86405"
		if err := validateAck([]byte(changed)); (err == nil) != valid {
			t.Fatalf("override %s: %v", n, err)
		}
	}
}

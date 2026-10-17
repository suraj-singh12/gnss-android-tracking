package core

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestRuntimeDiagnosticsRemainAvailableDuringWriterStall(t *testing.T) {
	s, _, _ := open(t)
	body := encode(t, message(t, 1, 0, 0, 0, 1))
	r := httptest.NewRecorder()
	s.IngestHandler().ServeHTTP(r, httptest.NewRequest("POST", "/api/v1/messages", bytes.NewReader(body)))
	// Use the protocol's content type before requiring actual durable receipt.
	request := httptest.NewRequest("POST", "/api/v1/messages", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	r = httptest.NewRecorder()
	s.IngestHandler().ServeHTTP(r, request)
	if r.Code != 200 {
		t.Fatalf("receipt: %d %s", r.Code, r.Body)
	}
	s.mu.Lock()
	done := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		w := httptest.NewRecorder()
		s.LocalHandler(nil).ServeHTTP(w, httptest.NewRequest("GET", "/local/runtime-diagnostics", nil))
		done <- w
	}()
	var report *httptest.ResponseRecorder
	select {
	case report = <-done:
		s.mu.Unlock()
	case <-time.After(2 * time.Second):
		s.mu.Unlock()
		t.Fatal("runtime export waited on ingestion mutex")
	}
	if report.Code != 200 {
		t.Fatalf("diagnostics: %d", report.Code)
	}
	var v struct {
		Timings map[string]struct {
			Count, Errors uint64
			Bytes         uint64 `json:"response_bytes"`
		}
	}
	if err := json.Unmarshal(report.Body.Bytes(), &v); err != nil {
		t.Fatal(err)
	}
	if v.Timings["http_ingest_messages"].Count != 2 || v.Timings["http_ingest_messages"].Errors != 1 || v.Timings["http_ingest_messages"].Bytes == 0 || v.Timings["commit_live"].Count != 1 {
		t.Fatalf("missing stage/error/commit counters: %s", report.Body)
	}
	if strings.Contains(report.Body.String(), "11111111-") || strings.Contains(report.Body.String(), "latitude") {
		t.Fatal("runtime counters leaked request content")
	}
}

func TestRuntimeTimingsBoundConcurrentSamples(t *testing.T) {
	s, _, _ := open(t)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := 0; n < 100; n++ {
				s.recordRuntime("http_local_live", time.Now(), false, 17)
			}
		}()
	}
	wg.Wait()
	r := s.runtimeReport()["timings"].(map[string]any)["http_local_live"].(map[string]any)
	if r["count"] != uint64(800) || r["recent_sample_count"] != 128 || r["response_bytes"] != uint64(13600) {
		t.Fatalf("unbounded or lost samples: %v", r)
	}
}

func TestRuntimeHTTPShowsUncompletedRequestWithoutInventedTiming(t *testing.T) {
	s, _, _ := open(t)
	w, done := s.timeHTTP(httptest.NewRecorder(), "http_local_live")
	stage := func() map[string]any {
		return s.runtimeReport()["timings"].(map[string]any)["http_local_live"].(map[string]any)
	}
	if v := stage(); v["active_http"] != 1 || v["count"] != uint64(0) || v["recent_p99_ms"] != nil {
		t.Fatalf("uncompleted HTTP is not a zero-duration success: %v", v)
	}
	w.WriteHeader(503)
	done()
	if v := stage(); v["active_http"] != 0 || v["count"] != uint64(1) || v["errors"] != uint64(1) {
		t.Fatalf("completion/error accounting: %v", v)
	}
}

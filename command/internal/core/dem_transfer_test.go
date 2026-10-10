package core

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDEMTransferRetriesDiagnosticsAndLimits(t *testing.T) {
	valid := compressedDEM(t, 27, 88)
	for _, tc := range []struct {
		name       string
		status     int
		body       []byte
		retryAfter string
		recover    bool
		wantCalls  int
		wantError  string
	}{
		{"transient recovery", 503, nil, "", true, 2, ""},
		{"rate limit", 429, nil, "1", true, 2, ""},
		{"long provider cooldown", 429, nil, "31", false, 1, "HTTP 429"},
		{"invalid credentials", 401, nil, "", false, 1, "HTTP 401"},
		{"forbidden", 403, nil, "", false, 1, "HTTP 403"},
		{"not found", 404, nil, "", false, 1, "HTTP 404"},
		{"bad request", 400, nil, "", false, 1, "HTTP 400"},
		{"exhaustion", 503, nil, "", false, 3, "HTTP 503"},
		{"invalid gzip", 200, []byte("not gzip"), "", false, 1, "invalid compressed DEM"},
		{"truncated gzip", 200, valid[:len(valid)-5], "", false, 1, "invalid compressed DEM"},
		{"compressed limit", 200, make([]byte, demCompressedLimit+2), "", false, 1, "exceeds 26 MiB"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				if tc.recover && calls > 1 {
					w.Write(valid)
					return
				}
				if tc.retryAfter != "" {
					w.Header().Set("Retry-After", tc.retryAfter)
				}
				w.WriteHeader(tc.status)
				w.Write(tc.body)
			}))
			defer s.Close()
			p := newMapProvider()
			p.demTestBackoff = time.Millisecond
			report := DEMReport{}
			started := time.Now()
			raw, err := p.downloadDEMTile(context.Background(), "N27E088.hgt", s.URL, &report, nil)
			if tc.wantError == "" {
				if err != nil || len(raw) != demRawSize {
					t.Fatal(err, len(raw))
				}
			} else if err == nil || !strings.Contains(err.Error(), tc.wantError) {
				t.Fatal(err)
			}
			if calls != tc.wantCalls || len(report.Attempts) != calls {
				t.Fatal("retry count", calls, report)
			}
			if tc.name == "rate limit" && time.Since(started) < time.Second {
				t.Fatal("Retry-After ignored")
			}
			for _, a := range report.Attempts {
				if a.HTTPStatus == nil || a.ConnectionMS == nil || a.FirstByteMS == nil || a.ElapsedMS <= 0 {
					t.Fatal("missing observable diagnostics", a)
				}
			}
			last := report.Attempts[len(report.Attempts)-1]
			if tc.wantError == "" && (last.CompressedBytes == nil || *last.CompressedBytes != int64(len(valid)) || last.TransferMS == nil || last.DecompressionMS == nil) {
				t.Fatal("inaccurate byte/stage measurement", last)
			}
			if tc.status != 200 && report.Attempts[0].DecompressionMS != nil {
				t.Fatal("unavailable measure fabricated")
			}
		})
	}
}

func TestDEMSlowStreamingInterruptedRecoveryAndCancellation(t *testing.T) {
	valid := compressedDEM(t, 27, 88)
	for _, kind := range []string{"slow success", "body interruption recovery", "deadline", "cancel backoff"} {
		t.Run(kind, func(t *testing.T) {
			calls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				if kind == "cancel backoff" {
					w.WriteHeader(503)
					return
				}
				if kind == "body interruption recovery" && calls == 1 {
					w.Header().Set("Content-Length", fmt.Sprint(len(valid)))
					w.Write(valid[:20])
					return
				}
				w.Write(valid[:20])
				w.(http.Flusher).Flush()
				if kind == "deadline" {
					<-r.Context().Done()
					return
				}
				time.Sleep(50 * time.Millisecond)
				w.Write(valid[20:])
			}))
			defer server.Close()
			p := newMapProvider()
			p.demTestBackoff = time.Millisecond
			p.demTestAttemptBudget = 200 * time.Millisecond
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			report := DEMReport{}
			stages := []string{}
			_, err := p.downloadDEMTile(ctx, "N27E088.hgt", server.URL, &report, func(e DEMProgress) {
				stages = append(stages, e.Stage)
				if kind == "cancel backoff" && e.Stage == "retry backoff" {
					cancel()
				}
			})
			switch kind {
			case "slow success":
				if err != nil || *report.Attempts[0].TransferMS < 45 {
					t.Fatal(err, report)
				}
			case "body interruption recovery":
				if err != nil || calls != 2 || report.Attempts[0].DecompressionMS != nil {
					t.Fatal(err, report)
				}
			case "deadline":
				if !errors.Is(err, context.DeadlineExceeded) || calls != 3 || strings.Contains(err.Error(), "invalid compressed") {
					t.Fatal(err, calls)
				}
			case "cancel backoff":
				if !errors.Is(err, context.Canceled) || calls != 1 {
					t.Fatal(err, calls)
				}
			}
			if len(stages) == 0 {
				t.Fatal("no progress")
			}
		})
	}
}

func TestDEMStreamingAPIReportCacheSaveAndOfflineRestart(t *testing.T) {
	db := filepath.Join(t.TempDir(), "command.sqlite")
	s, err := Open(db)
	if err != nil {
		t.Fatal(err)
	}
	m, err := s.saveMap("fixture", []byte(validOfflineMap))
	if err != nil {
		t.Fatal(err)
	}
	other, err := s.saveMap("library limit fixture", []byte(validOfflineMap))
	if err != nil {
		t.Fatal(err)
	}
	s.db.Exec(`INSERT INTO offline_terrain(map_id,data) VALUES(?,?)`, other.ID, make([]byte, 16*1024*1024))
	valid := compressedDEM(t, 28, 77)
	calls := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++; w.Write(valid) }))
	defer provider.Close()
	p := newMapProvider()
	p.dem = provider.URL
	call := func() *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "/local/maps/"+m.ID+"/terrain-download", strings.NewReader(`{"components":{"elevation":true}}`))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Accept", "application/x-ndjson")
		w := httptest.NewRecorder()
		s.mapRequest(w, r, p)
		return w
	}
	w := call()
	if !strings.Contains(w.Body.String(), "local terrain save failed") {
		t.Fatal(w.Body.String())
	}
	var count int
	s.db.QueryRow(`SELECT count(*) FROM offline_terrain WHERE map_id=?`, m.ID).Scan(&count)
	if count != 0 {
		t.Fatal("partial asset persisted")
	}
	initialCalls := calls
	if initialCalls != 4 {
		t.Fatal("fixture straddles four halo tiles", initialCalls)
	}
	s.db.Exec(`DELETE FROM offline_terrain WHERE map_id=?`, other.ID)
	w = call()
	if calls != initialCalls || !strings.Contains(w.Body.String(), `"stage":"ready"`) || !strings.Contains(w.Body.String(), `"reused_completed_acquisition":true`) {
		t.Fatal("successful acquisition redownloaded", calls, w.Body.String())
	}
	s.db.QueryRow(`SELECT count(*) FROM offline_maps`).Scan(&count)
	if count != 2 {
		t.Fatal("duplicate vector records")
	}
	provider.Close()
	s.Close()
	s, err = Open(db)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	w = httptest.NewRecorder()
	s.mapRequest(w, httptest.NewRequest("GET", "/local/maps/"+m.ID+"/download-report", nil), p)
	var report DEMReport
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &report) != nil || report.SaveMS == nil || report.Outcome != "ready" || !report.Reused {
		t.Fatal(w.Code, w.Body.String())
	}
	w = terrainCall(s, "GET", "/local/maps/"+m.ID+"/terrain", nil, "")
	if w.Code != 200 {
		t.Fatal("offline terrain lost")
	}
}

type demRoundTripper func(*http.Request) (*http.Response, error)

func TestDEMActiveDownloadPreservesCriticalOperationsAndCancellation(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "command.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	m, err := s.saveMap("fixture", []byte(validOfflineMap))
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte{31, 139, 8, 0})
		w.(http.Flusher).Flush()
		close(started)
		<-r.Context().Done()
	}))
	defer provider.Close()
	p := newMapProvider()
	p.dem = provider.URL
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r := httptest.NewRequest("POST", "/local/maps/"+m.ID+"/terrain-download", strings.NewReader(`{"components":{"elevation":true}}`)).WithContext(ctx)
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Accept", "application/x-ndjson")
	done := make(chan struct{})
	go func() { defer close(done); s.mapRequest(httptest.NewRecorder(), r, p) }()
	<-started
	criticalDone := make(chan struct{})
	go func() {
		defer close(criticalDone)
		action(t, s, "start")
		ingest(t, s, message(t, 1, 0, 0, 0, 3))
		ingest(t, s, emergency(t, 2, 1))
		snapshot(t, s)
	}()
	select {
	case <-criticalDone:
	case <-time.After(3 * time.Second):
		cancel()
		<-done
		t.Fatal("download blocked critical Store operations")
	}
	cancel()
	<-done
	var count int
	s.db.QueryRow(`SELECT count(*) FROM offline_terrain WHERE map_id=?`, m.ID).Scan(&count)
	if count != 0 {
		t.Fatal("cancelled partial terrain saved")
	}
	w := httptest.NewRecorder()
	s.downloadReport(w, m.ID)
	var report DEMReport
	if json.Unmarshal(w.Body.Bytes(), &report) != nil || report.Outcome != "cancelled" || report.SaveMS != nil || len(report.Attempts) != 1 {
		t.Fatal("cancel evidence", w.Body.String())
	}
}

func (f demRoundTripper) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestDEMConnectionDeadlineUnavailableMetrics(t *testing.T) {
	p := newMapProvider()
	p.demTestAttemptBudget = 20 * time.Millisecond
	p.demTestBackoff = time.Millisecond
	p.demClient = &http.Client{Transport: demRoundTripper(func(r *http.Request) (*http.Response, error) { <-r.Context().Done(); return nil, r.Context().Err() })}
	report := DEMReport{}
	_, err := p.downloadDEMTile(context.Background(), "N27E088.hgt", "http://provider.invalid", &report, nil)
	if !errors.Is(err, context.DeadlineExceeded) || len(report.Attempts) != 3 {
		t.Fatal(err, report)
	}
	for _, a := range report.Attempts {
		if a.ConnectionMS != nil || a.FirstByteMS != nil || a.HTTPStatus != nil || a.CompressedBytes != nil || a.TransferMS != nil || a.DecompressionMS != nil || a.Stage != "connecting" {
			t.Fatal("unobservable measures fabricated", a)
		}
	}
	// Cancellation reaches bounded in-memory processing, too.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = io.ReadAll(&demContextReader{Reader: bytes.NewReader([]byte("data")), ctx: ctx})
	if !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}

func TestDEMDecodedSizeAndElevationGuards(t *testing.T) {
	for _, kind := range []string{"undersized", "decompression size limit", "invalid elevation"} {
		t.Run(kind, func(t *testing.T) {
			raw := []byte{0, 0}
			if kind == "decompression size limit" {
				raw = make([]byte, demRawSize+1)
			}
			if kind == "invalid elevation" {
				raw = make([]byte, demRawSize)
				for i := 0; i < len(raw); i += 2 {
					binary.BigEndian.PutUint16(raw[i:], 10000)
				}
			}
			var compressed bytes.Buffer
			z := gzip.NewWriter(&compressed)
			z.Write(raw)
			z.Close()
			calls := 0
			provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++; w.Write(compressed.Bytes()) }))
			defer provider.Close()
			p := newMapProvider()
			p.dem = provider.URL
			_, err := p.acquireDEM(context.Background(), [4]float64{88.756, 27.369, 88.768, 27.379}, map[string]bool{"elevation": true})
			if err == nil || !strings.Contains(err.Error(), "decoded elevation data invalid") || calls != 1 || p.prepared != nil {
				t.Fatal("invalid decoded data accepted/cached/retried", err, calls)
			}
		})
	}
}

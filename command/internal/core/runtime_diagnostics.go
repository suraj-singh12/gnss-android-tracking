package core

// Bounded process-local timings supplement the existing durable field journal.
// No request content, identifiers, paths, addresses or errors are retained.
import (
	"database/sql"
	"net/http"
	"runtime"
	"sort"
	"sync"
	"time"
)

type runtimeTiming struct {
	Count, Errors, Bytes uint64
	Active               int
	TotalMS, MaxMS       float64
	Recent               [128]float64
}

type runtimeDiagnostics struct {
	mu             sync.Mutex
	timings        map[string]*runtimeTiming
	backlogDevices int
	peakDevices    int
	backlogAt      time.Time
}

func (s *Store) recordRuntime(stage string, began time.Time, failed bool, bytes int) {
	d := &s.runtime
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.timings == nil {
		d.timings = map[string]*runtimeTiming{}
	}
	t := d.timings[stage]
	if t == nil {
		t = &runtimeTiming{}
		d.timings[stage] = t
	}
	ms := time.Since(began).Seconds() * 1000
	t.Recent[t.Count%128] = ms
	t.Count++
	if failed {
		t.Errors++
	}
	t.Bytes += uint64(max(0, bytes))
	t.TotalMS += ms
	t.MaxMS = max(t.MaxMS, ms)
}

func (s *Store) runtimeBacklog(devices int) {
	s.runtime.mu.Lock()
	defer s.runtime.mu.Unlock()
	s.runtime.backlogDevices, s.runtime.backlogAt = devices, time.Now().UTC()
	s.runtime.peakDevices = max(s.runtime.peakDevices, devices)
}

// This endpoint/export deliberately needs no SQLite reader or Store mutex. It
// stays available while those stages are stalled. Backlog is last observed only.
func (s *Store) runtimeReport() map[string]any {
	s.runtime.mu.Lock()
	timings := map[string]any{}
	for name, t := range s.runtime.timings {
		recent := append([]float64(nil), t.Recent[:min(t.Count, 128)]...)
		sort.Float64s(recent)
		q := func(f float64) float64 { return recent[min(len(recent)-1, int(float64(len(recent))*f))] }
		var p50, p95, p99 any
		if len(recent) > 0 {
			p50, p95, p99 = q(.5), q(.95), q(.99)
		}
		timings[name] = map[string]any{"count": t.Count, "active_http": t.Active, "errors": t.Errors, "response_bytes": t.Bytes, "total_ms": t.TotalMS, "max_ms": t.MaxMS, "recent_sample_count": len(recent), "recent_p50_ms": p50, "recent_p95_ms": p95, "recent_p99_ms": p99}
	}
	backlog, peak, at := s.runtime.backlogDevices, s.runtime.peakDevices, s.runtime.backlogAt
	s.runtime.mu.Unlock()
	var memory runtime.MemStats
	runtime.ReadMemStats(&memory)
	return map[string]any{"schema_version": 1, "build": BuildRevision(), "generated_at": time.Now().UTC(), "os": runtime.GOOS, "arch": runtime.GOARCH, "go": runtime.Version(), "goroutines": runtime.NumGoroutine(), "go_heap_bytes": memory.HeapAlloc, "go_sys_bytes": memory.Sys, "sqlite_writer_pool": s.db.Stats(), "timings": timings, "last_observed_projection_devices": backlog, "peak_observed_projection_devices": peak, "backlog_observed_at": at, "coverage": "Process-local counters reset on restart; percentiles cover only the latest 128 completions per stage. HTTP includes response writes, not phone-applied ACK. Writer wait is the application mutex; transaction includes SQLite work/lock wait; commit includes driver/OS work, not isolated fsync. Go memory is not process RSS. Backlog is last observed by a dashboard read, not current queue depth. No phone or browser health is inferred."}
}

type runtimeResponse struct {
	http.ResponseWriter
	status, bytes int
	writeFailed   bool
}

func (w *runtimeResponse) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w *runtimeResponse) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}
func (w *runtimeResponse) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytes += n
	w.writeFailed = w.writeFailed || err != nil
	return n, err
}

func (s *Store) timeHTTP(w http.ResponseWriter, stage string) (http.ResponseWriter, func()) {
	start := time.Now()
	s.runtime.mu.Lock()
	if s.runtime.timings == nil {
		s.runtime.timings = map[string]*runtimeTiming{}
	}
	if s.runtime.timings[stage] == nil {
		s.runtime.timings[stage] = &runtimeTiming{}
	}
	s.runtime.timings[stage].Active++
	s.runtime.mu.Unlock()
	response := &runtimeResponse{ResponseWriter: w}
	return response, func() {
		s.recordRuntime(stage, start, response.status == 0 || response.status >= 400 || response.writeFailed, response.bytes)
		s.runtime.mu.Lock()
		s.runtime.timings[stage].Active--
		s.runtime.mu.Unlock()
	}
}

func (s *Store) commitReceipt(tx *sql.Tx, stage string) error {
	start := time.Now()
	err := tx.Commit()
	s.recordRuntime(stage, start, err != nil, 0)
	return err
}

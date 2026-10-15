package core

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"runtime"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Opt-in streaming workload: memory for source generation is one bounded batch per
// device. GNSS_SYNC_SAMPLES=604800 is dense seven-day 1 Hz retention; smaller
// samples are distributed over GNSS_SYNC_DAYS days, not claimed dense history.
func TestSyncFleetPerformance(t *testing.T) {
	if os.Getenv("GNSS_SYNC_BENCH") == "" {
		t.Skip("opt-in measured fleet workload")
	}
	integer := func(key string, fallback int) int {
		n, e := strconv.Atoi(os.Getenv(key))
		if e != nil || n < 1 {
			return fallback
		}
		return n
	}
	devices, samples, days, limit := integer("GNSS_SYNC_DEVICES", 5), integer("GNSS_SYNC_SAMPLES", 600), integer("GNSS_SYNC_DAYS", 7), integer("GNSS_SYNC_BATCH", 20000)
	s, now, _ := open(t)
	*now = base.Add(time.Duration(days)*24*time.Hour + time.Hour)
	if err := s.ActionMode("start", "from_beginning"); err != nil {
		t.Fatal(err)
	}
	var receiverNanos atomic.Int64
	handler := s.IngestHandler()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		began := time.Now()
		handler.ServeHTTP(w, r)
		receiverNanos.Add(int64(time.Since(began)))
	}))
	defer server.Close()
	template := observationMessage(t, 1, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
	type result struct {
		bytes, ack, requests, source int
		latency                      []time.Duration
		liveLatency, sosLatency      time.Duration
		elapsed                      time.Duration
		err                          error
	}
	priority := os.Getenv("GNSS_SYNC_PRIORITY") == "1"
	results := make(chan result, devices)
	var memoryBefore, memoryAfter runtime.MemStats
	runtime.ReadMemStats(&memoryBefore)
	start := time.Now()
	var wg sync.WaitGroup
	for d := 1; d <= devices; d++ {
		wg.Add(1)
		go func(d int) {
			defer wg.Done()
			r := result{}
			began := time.Now()
			defer func() { r.elapsed = time.Since(began); results <- r }()
			for n := 1; n <= samples; {
				messages := []Message{}
				size := 32
				for n <= samples && len(messages) < 128 {
					m := template
					m.Device = fmt.Sprintf("11111111-1111-4111-8111-%012d", d)
					m.ID = fmt.Sprintf("00000000-0000-4000-8000-%012d", n)
					m.Sequence = int64(n)
					offset := int64(n) * int64(days) * 86400000 / int64(samples)
					within, block := (n-1)%100, (n-1)/100
					period := int64(days) * 86400000 / int64((samples+99)/100)
					bursts := os.Getenv("GNSS_SYNC_PATTERN") == "bursts" && period > 600000
					if bursts {
						local := int64(within+1) * 1000
						if within >= 50 {
							local = 50000 + int64(within-49)*10000
						}
						offset = int64(block)*period + local
					}
					at := base.Add(time.Duration(offset) * time.Millisecond).Format(wireTime)
					m.Captured = at
					fix := *template.Fix
					fix.Observed = at
					fix.Lon = float64(n) * 5 / Radius / radians
					if bursts {
						x, y := float64(block)*150, float64(block%2)*30
						switch {
						case within < 50:
							x += float64(within) * 5
						case within < 80:
							x += 245 + float64(within%3-1)*0.3
						default:
							x += 245 + float64(within-79)*50
							y += float64(within-79) * 2
						}
						fix.Lon = x / Radius / radians
						fix.Lat = y / Radius / radians
						if within == 97 {
							accuracy := 150.0
							fix.Accuracy = &accuracy
						}
					}
					m.Fix = &fix
					o := *template.Observation
					o.ID = m.ID
					o.Sequence = int64(n)
					o.ElapsedMillis = offset
					m.Observation = &o
					wire, _ := json.Marshal(m)
					if len(messages) > 0 && size+len(wire)+1 > limit {
						break
					}
					size += len(wire) + 1
					messages = append(messages, m)
					n++
				}
				body, _ := json.Marshal(map[string]any{"batch_version": 1, "messages": messages})
				requestBegan := time.Now()
				response, e := http.Post(server.URL+"/api/v1/history", "application/json", bytes.NewReader(body))
				if e != nil {
					r.err = e
					return
				}
				ack, e := io.ReadAll(response.Body)
				response.Body.Close()
				if e != nil || response.StatusCode != 200 {
					r.err = fmt.Errorf("HTTP %d: %s %v", response.StatusCode, ack, e)
					return
				}
				var a BatchAck
				if e = json.Unmarshal(ack, &a); e != nil || len(a.Acks) != len(messages) {
					r.err = fmt.Errorf("invalid receipt %v", e)
					return
				}
				for i, m := range messages {
					if a.Acks[i].ID != m.ID || a.Acks[i].Device != m.Device || a.Acks[i].Sequence != m.Sequence {
						r.err = fmt.Errorf("identity mismatch")
						return
					}
				}
				r.latency = append(r.latency, time.Since(requestBegan))
				r.source += size - 32 - len(messages)
				r.bytes += len(body)
				r.ack += len(ack)
				r.requests++

				if priority && (r.requests == 1 || n > samples || (n-1)/600 != (n-1-len(messages))/600) {
					live := template
					live.Device = messages[0].Device
					live.Sequence = int64(samples + 1)
					live.ID = fmt.Sprintf("00000000-0000-4000-8000-%012d", samples+1)
					live.Captured = now.Format(wireTime)
					f := *template.Fix
					f.Observed = live.Captured
					f.Lon = float64(samples+1) * 5 / Radius / radians
					live.Fix = &f
					o := *template.Observation
					o.ID = live.ID
					o.Sequence = int64(samples + 1)
					o.ElapsedMillis = int64(days)*86400000 + 3600000
					live.Observation = &o
					sos := live
					sos.Type = "sos"
					sos.Sequence++
					sos.ID = fmt.Sprintf("00000000-0000-4000-8000-%012d", samples+2)
					sos.Observation = nil
					sos.Fix = nil
					sos.Health.GNSS = "no_fix"
					sos.SOS = &SOS{EventID: sos.ID, Triggered: sos.Captured}
					for i, m := range []Message{live, sos} {
						wire, _ := json.Marshal(m)
						began := time.Now()
						response, e := http.Post(server.URL+"/api/v1/messages", "application/json", bytes.NewReader(wire))
						if e != nil {
							r.err = e
							return
						}
						reply, e := io.ReadAll(response.Body)
						response.Body.Close()
						var ack Ack
						if e != nil || response.StatusCode != 200 || json.Unmarshal(reply, &ack) != nil || ack.ID != m.ID || ack.Device != m.Device || ack.Sequence != m.Sequence {
							r.err = fmt.Errorf("priority receipt: %s %v", reply, e)
							return
						}
						r.bytes += len(wire)
						r.ack += len(reply)
						if i == 0 {
							r.liveLatency = max(r.liveLatency, time.Since(began))
						} else {
							r.sosLatency = max(r.sosLatency, time.Since(began))
						}
					}
				}
			}
		}(d)
	}
	wg.Wait()
	close(results)
	transfer := time.Since(start)
	bodyBytes, ackBytes, requests, sourceBytes := 0, 0, 0, 0
	latencies := []time.Duration{}
	for r := range results {
		if r.err != nil {
			t.Fatal(r.err)
		}
		latencies = append(latencies, r.latency...)
		sourceBytes += r.source
		bodyBytes += r.bytes
		ackBytes += r.ack
		requests += r.requests
		t.Logf("phone completion=%s max_live_ACK=%s max_SOS_ACK=%s", r.elapsed, r.liveLatency, r.sosLatency)
	}
	var count int
	expected := devices * samples
	if priority {
		expected += devices * 2
	}
	if err := s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&count); err != nil || count != expected {
		t.Fatalf("accounting %d: %v", count, err)
	}
	projectionStart := time.Now()
	s.projectionMu.Lock()
	err := s.processProjectionLocked()
	s.projectionMu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	projection := time.Since(projectionStart)
	viewStart := time.Now()
	st, err := s.snapshot(30, false, false)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("dashboard_read_model=%s", time.Since(viewStart))
	tx, err := s.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	raw, err := candidates(tx, "")
	tx.Rollback()
	if err != nil {
		t.Fatal(err)
	}
	referenceStart := time.Now()
	pp, dd := historyReference(raw, st)
	t.Logf("full_chronological_reference=%s", time.Since(referenceStart))
	for i := range st.Points {
		st.Points[i].X = 0
		st.Points[i].Y = 0
		st.Points[i].Dot = false
	}
	if !reflect.DeepEqual(pp, st.Points) || !reflect.DeepEqual(dd, st.Decisions) {
		t.Fatalf("chronological geometry differs: %d/%d", len(pp), len(st.Points))
	}
	runtime.ReadMemStats(&memoryAfter)
	sort.Slice(latencies, func(i, j int) bool { return latencies[i] < latencies[j] })
	t.Logf("source_bytes=%d receiver_handler_sum_including_queue_parse_commit=%s ACK_RTT_p50=%s p95=%s p99=%s total_allocated=%d heap=%d", sourceBytes, time.Duration(receiverNanos.Load()), latencies[len(latencies)/2], latencies[len(latencies)*95/100], latencies[len(latencies)*99/100], memoryAfter.TotalAlloc-memoryBefore.TotalAlloc, memoryAfter.HeapAlloc)
	t.Logf("devices=%d samples/phone=%d source_days=%d batch_target=%d concurrency=1/phone history_requests=%d request_bytes=%d ack_bytes=%d transfer_including_durable_receipt=%s reconstruction_flush=%s observations/s=%.1f raw=%d qualified=%d", devices, samples, days, limit, requests, bodyBytes, ackBytes, transfer, projection, float64(count)/transfer.Seconds(), count, len(st.Points))
}

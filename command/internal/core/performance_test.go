package core

import (
	"bytes"
	"fmt"
	"net/http/httptest"
	"net/http/httputil"
	"os"
	"runtime"
	"testing"
	"time"
)

func TestFiveDeviceShortBatchPerformance(t *testing.T) {
	s, now, path := open(t)
	action(t, s, "start")
	s.db.Exec("PRAGMA wal_checkpoint(TRUNCATE)")
	before, _ := os.Stat(path)
	const perDevice = 600
	var memoryBefore, memoryAfter runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&memoryBefore)
	started := time.Now()
	requests, bodyBytes, headerBytes, ackBytes, responseHeaders, interleavedLives := 0, 0, 0, 0, 0, 0
	sourceBytes := 0
	liveLatency := time.Duration(0)
	handler := s.IngestHandler()
	for d := 0; d < 5; d++ {
		session := id()
		deviceID := fmt.Sprintf("11111111-1111-4111-8111-%012d", d+1)
		messages := []Message{}
		for n := 1; n <= perDevice; n++ {
			m := observationMessage(t, n, session)
			m.Device = deviceID
			messages = append(messages, m)
			sourceBytes += len(encode(t, m))
		}
		// Current observation first, then oldest blocks; retry one durably stored batch.
		*now = base.Add(perDevice * time.Second)
		liveStart := time.Now()
		ingest(t, s, messages[perDevice-1])
		if elapsed := time.Since(liveStart); elapsed > liveLatency {
			liveLatency = elapsed
		}
		for start := 0; start < perDevice-1; {
			end := start + 1
			for end < perDevice-1 && len(batchBytes(t, messages[start:end+1])) <= 20000 {
				end++
			}
			body := batchBytes(t, messages[start:end])
			req := httptest.NewRequest("POST", "http://192.168.1.10:8080/api/v1/history", bytes.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			wire, e := httputil.DumpRequest(req, true)
			if e != nil {
				t.Fatal(e)
			}
			headerBytes += len(wire) - len(body)
			bodyBytes += len(body)
			requests++
			deliver := func() {
				response := httptest.NewRecorder()
				replay := httptest.NewRequest("POST", "/api/v1/history", bytes.NewReader(body))
				replay.Header.Set("Content-Type", "application/json")
				handler.ServeHTTP(response, replay)
				if response.Code != 200 {
					t.Fatal(response.Code, response.Body.String())
				}
				responseWire, err := httputil.DumpResponse(response.Result(), true)
				if err != nil {
					t.Fatal(err)
				}
				ackBytes += response.Body.Len()
				responseHeaders += len(responseWire) - response.Body.Len()
			}
			deliver()
			if start == 0 {
				deliver()
				requests++
				bodyBytes += len(body)
				headerBytes += len(wire) - len(body)
			}
			if requests%5 == 0 {
				// A live retry interleaved with real historical transfer exercises ACK
				// latency while reconstruction is busy, without another raw identity.
				liveStart := time.Now()
				ingest(t, s, messages[perDevice-1])
				interleavedLives++
				if elapsed := time.Since(liveStart); elapsed > liveLatency {
					liveLatency = elapsed
				}
			}
			start = end
		}
	}
	ingestion := time.Since(started)
	projectionStart := time.Now()
	st := snapshot(t, s)
	projection := time.Since(projectionStart)
	if len(st.Points) != 5*perDevice {
		t.Fatalf("lost points: %d", len(st.Points))
	}
	s.db.Exec("PRAGMA wal_checkpoint(TRUNCATE)")
	after, _ := os.Stat(path)
	runtime.ReadMemStats(&memoryAfter)
	t.Logf("5 devices x %d observations: mean serialized observation=%d B, history requests=%d (+5 individual live requests), batch body bytes=%d, measured HTTP history request headers=%d B, durable ACK bodies=%d B, HTTP response headers=%d B, interleaved live retries=%d, catch-up ingestion=%s, max individual live durable ACK latency=%s, projection flush=%s, SQLite allocated delta=%d B (raw/indexes/projection/evidence included), total heap allocations=%d B, retained heap=%d B", perDevice, sourceBytes/(5*perDevice), requests, bodyBytes, headerBytes, ackBytes, responseHeaders, interleavedLives, ingestion, liveLatency, projection, after.Size()-before.Size(), memoryAfter.TotalAlloc-memoryBefore.TotalAlloc, memoryAfter.HeapAlloc)
}

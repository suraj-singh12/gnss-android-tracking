package core

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"github.com/ncruces/go-sqlite3"
	"github.com/ncruces/go-sqlite3/driver"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestStreamingProjectionPreservesChronologyAndBoundedPages(t *testing.T) {
	s, now, _ := open(t)
	s.streamThreshold = 8
	*now = base.Add(time.Hour)
	if err := s.ActionMode("start", "from_beginning"); err != nil {
		t.Fatal(err)
	}
	s.projectionMu.Lock()
	sessions := []string{id(), id(), id()}
	// Reordered arrivals, three sessions, poor quality and real sequence gaps.
	for n := 180; n >= 1; n-- {
		if n == 47 {
			continue
		}
		m := observationMessage(t, n, sessions[(n-1)/60])
		if n%17 == 0 {
			a := 100.0
			m.Fix.Accuracy = &a
		}
		ingest(t, s, m)
	}
	if err := s.processProjectionLocked(); err != nil {
		s.projectionMu.Unlock()
		t.Fatal(err)
	}
	s.projectionMu.Unlock()
	st := snapshot(t, s)
	tx, err := s.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	raw, err := candidates(tx, "")
	tx.Rollback()
	if err != nil {
		t.Fatal(err)
	}
	pp, dd := historyReference(raw, st)
	for i := range st.Points {
		st.Points[i].X = 0
		st.Points[i].Y = 0
		st.Points[i].Dot = false
	}
	if !reflect.DeepEqual(pp, st.Points) || !reflect.DeepEqual(dd, st.Decisions) {
		t.Fatal("streaming changed exact geometry/decisions/distance")
	}
	var points []Point
	var decisions []Decision
	cursor := ""
	for {
		page, err := s.historyPage(context.Background(), device, cursor)
		if err != nil {
			t.Fatal(err)
		}
		if len(page.Points) > 512 || len(page.Decisions) > 512 {
			t.Fatal("unbounded page")
		}
		points = append(points, page.Points...)
		decisions = append(decisions, page.Decisions...)
		cursor = page.Next
		if cursor == "" {
			break
		}
	}
	if !reflect.DeepEqual(pp, points) || !reflect.DeepEqual(dd, decisions) {
		t.Fatal("paged original geometry changed")
	}
	if len(s.projectionRaw) != 0 {
		t.Fatal("streaming retained full raw arrays")
	}
}

func TestCacheStagingCrashAndMissingChunkRebuild(t *testing.T) {
	s, _, path := open(t)
	ingest(t, s, observationMessage(t, 1, id()))
	if err := s.ActionMode("start", "from_beginning"); err != nil {
		t.Fatal(err)
	}
	before := snapshot(t, s)
	prepared, err := prepareDeviceProjection(State{Points: []Point{{Message: "unpublished"}}})
	if err != nil {
		t.Fatal(err)
	}
	if err = s.stageDeviceProjection(device, prepared); err != nil {
		t.Fatal(err)
	}
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	got := snapshot(t, s)
	if !reflect.DeepEqual(before.Points, got.Points) {
		t.Fatal("unpublished chunks replaced good generation")
	}
	s.projectionMu.Lock()
	if _, err = s.db.Exec("DELETE FROM projection_device_chunks WHERE generation IN (SELECT value FROM json_each((SELECT data FROM projection_state WHERE id=1),'$.generations'))"); err != nil {
		t.Fatal(err)
	}
	if err = s.validateProjectionCache(); err != nil {
		t.Fatal(err)
	}
	if err = s.processProjectionLocked(); err != nil {
		t.Fatal(err)
	}
	s.projectionMu.Unlock()
	got = snapshot(t, s)
	if !reflect.DeepEqual(before.Points, got.Points) || got.ProjectionPending {
		t.Fatal("cache did not rebuild from original raw")
	}
}

func TestBoundedHistoryAndLiveAreIndependentOfColdReader(t *testing.T) {
	s, _, _ := open(t)
	if err := s.ActionMode("start", "from_beginning"); err != nil {
		t.Fatal(err)
	}
	s.projectionMu.Lock()
	tx, err := s.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	st, err := loadReceiptState(tx)
	if err != nil {
		t.Fatal(err)
	}
	st.Devices[device] = &Device{ID: device}
	for n := 0; n < 12000; n++ {
		st.Points = append(st.Points, Point{Device: device, Message: id(), Window: st.Recording.Windows[0].ID, Revision: st.Policy.Revision, Fix: Fix{Observed: base.Format(wireTime)}, Distance: float64(n)})
	}
	if err = saveState(tx, st); err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	s.projectionMu.Unlock()
	// Occupy the full inspection connection. LIVE uses its own bounded reader.
	conn, err := s.viewDB.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	live, _, err := s.boundedDashboard(ctx, 30, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(live.Points) != 0 {
		t.Fatal("LIVE response materialized history")
	}
	conn.Close()
	view, sampled, err := s.boundedDashboard(context.Background(), 30, false)
	if err != nil {
		t.Fatal(err)
	}
	if !sampled || len(view.Points) > dashboardPointLimit || len(view.Points) == 0 {
		t.Fatal("unbounded/empty preview")
	}
	b, _ := json.Marshal(dashboardView(view, s.Now()))
	if len(b) > 2*1024*1024 {
		t.Fatal("unbounded response")
	}
	w := httptest.NewRecorder()
	s.LocalHandler(nil).ServeHTTP(w, httptest.NewRequest("GET", "/local/live", nil))
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
}

func TestChronologicalIndexMatchesComparatorAndUsesRange(t *testing.T) {
	s, _, _ := open(t)
	var plan string
	tx, err := s.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	rows, err := tx.Query(`EXPLAIN QUERY PLAN SELECT known,received FROM raw WHERE device=? AND json_extract(known,'$.type')='location' AND (`+chronologicalKey+`)>? ORDER BY `+chronologicalKey+` LIMIT 512`, device, "")
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var a, b, c int
		var detail string
		if err = rows.Scan(&a, &b, &c, &detail); err != nil {
			t.Fatal(err)
		}
		plan += detail
	}
	rows.Close()
	tx.Rollback()
	if !strings.Contains(plan, "raw_chronological_key") || !strings.Contains(plan, "<expr>>?") {
		t.Fatal("streaming must seek, not rescan prefixes", plan)
	}
	values := []Candidate{}
	sessions := []string{id(), id()}
	for n := 1; n <= 30; n++ {
		m := observationMessage(t, n, sessions[n%2])
		if n%3 == 0 {
			m.Observation = nil
		}
		values = append(values, Candidate{Message: m})
	}
	a, b := append([]Candidate{}, values...), append([]Candidate{}, values...)
	sort.Slice(a, func(i, j int) bool { return less(a[i].Message, a[j].Message, true) })
	sort.Slice(b, func(i, j int) bool { return candidateOrderKey(b[i].Message) < candidateOrderKey(b[j].Message) })
	if !reflect.DeepEqual(a, b) {
		t.Fatal("SQL key changes canonical order")
	}
}

func TestFreshPriorityAndLateJoinDuringActualSlowWriterSaturation(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Hour)
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	conn, err := s.db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	err = conn.Raw(func(c any) error {
		return c.(driver.Conn).Raw().SetAuthorizer(func(action sqlite3.AuthorizerActionCode, table, column, schema, inner string) sqlite3.AuthorizerReturnCode {
			if action == sqlite3.AUTH_INSERT && table == "raw" {
				once.Do(func() { close(entered); <-release })
			}
			return sqlite3.AUTH_OK
		})
	})
	conn.Close()
	if err != nil {
		t.Fatal(err)
	}
	var arrivals atomic.Int64
	receiver := s.IngestHandler()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/history" {
			arrivals.Add(1)
		}
		receiver.ServeHTTP(w, r)
	}))
	defer server.Close()
	var unblock sync.Once
	defer unblock.Do(func() { close(release) })
	result := make(chan error, 32)
	sendBatch := func(d int) {
		session := id()
		messages := []Message{}
		for n := 1; n <= 60; n++ {
			m := observationMessage(t, n, session)
			m.Device = fmt.Sprintf("11111111-1111-4111-8111-%012d", d)
			messages = append(messages, m)
		}
		body := batchBytes(t, messages)
		go func() {
			r, e := http.Post(server.URL+"/api/v1/history", "application/json", bytes.NewReader(body))
			if e == nil {
				defer r.Body.Close()
				if r.StatusCode != 200 {
					e = fmt.Errorf("history HTTP %d", r.StatusCode)
				}
			}
			result <- e
		}()
	}
	for d := 1; d <= 20; d++ {
		sendBatch(d)
	}
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("writer was not occupied")
	}
	deadline := time.Now().Add(5 * time.Second)
	for arrivals.Load() < 20 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if arrivals.Load() != 20 {
		t.Fatal("fleet not in receiver")
	}
	sendBatch(21) // Writer still blocked: this is not an ACK-delivery barrier.
	deadline = time.Now().Add(5 * time.Second)
	for arrivals.Load() < 21 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if arrivals.Load() != 21 {
		t.Fatal("late phone did not reach occupied receiver")
	}
	priority := make(chan time.Duration, 8)
	for i := 0; i < 8; i++ {
		m := emergency(t, 10001+i, float64(i))
		m.Device = fmt.Sprintf("11111111-1111-4111-8111-%012d", i+1)
		m.Captured = now.Format(wireTime)
		m.SOS.Triggered = m.Captured
		if i%2 == 0 {
			m.Type = "location"
			m.SOS = nil
			f := observationMessage(t, 10001+i, id())
			m.Fix = f.Fix
			m.Fix.Observed = m.Captured
			m.Health.GNSS = "fix"
		}
		body := encode(t, m)
		go func() {
			start := time.Now()
			r, e := http.Post(server.URL+"/api/v1/messages", "application/json", bytes.NewReader(body))
			if e == nil {
				defer r.Body.Close()
				if r.StatusCode != 200 {
					e = fmt.Errorf("priority HTTP %d", r.StatusCode)
				}
			}
			priority <- time.Since(start)
			result <- e
		}()
	}
	// Storage fault deliberately overlaps requests, not pauses after successful ACKs.
	time.Sleep(50 * time.Millisecond)
	unblock.Do(func() { close(release) })
	for i := 0; i < 29; i++ {
		if err := <-result; err != nil {
			t.Fatal(err)
		}
	}
	values := []time.Duration{}
	for i := 0; i < 8; i++ {
		values = append(values, <-priority)
	}
	sort.Slice(values, func(i, j int) bool { return values[i] < values[j] })
	t.Logf("fresh priority under 21 uploads + slow writer p50=%s p95=%s p99=%s worst=%s", values[4], values[7], values[7], values[7])
	if values[7] > 10*time.Second {
		t.Fatal("priority starved")
	}
	var count int
	if err = s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&count); err != nil || count != 21*60+8 {
		t.Fatal("lost raw identity", count, err)
	}
}

func TestPinnedWALReaderBackpressuresOnlyHistoryThenResumes(t *testing.T) {
	s, _, _ := open(t)
	s.walBudget = 4096
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	reader, err := s.liveDB.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Rollback()
	var count int
	if err = reader.QueryRow("SELECT COUNT(*) FROM raw").Scan(&count); err != nil {
		t.Fatal(err)
	}
	ingest(t, s, emergency(t, 2, 1))
	if !s.historyStoragePressure(context.Background()) {
		t.Fatal("pinned reader pressure ignored")
	}
	body := batchBytes(t, []Message{observationMessage(t, 3, id())})
	r := httptest.NewRequest("POST", "/api/v1/history", bytes.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.IngestHandler().ServeHTTP(w, r)
	if w.Code != 429 || w.Header().Get("X-GNSS-Throttle-Scope") != "history" {
		t.Fatal("history pressure not scoped", w.Code, w.Body.String())
	}
	if _, err = s.Ingest(encode(t, emergency(t, 4, 2))); err != nil {
		t.Fatal("SOS blocked by history pressure", err)
	}
	reader.Rollback()
	if s.historyStoragePressure(context.Background()) {
		t.Fatal("checkpoint did not recover after reader closed")
	}
	r = httptest.NewRequest("POST", "/api/v1/history", bytes.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	s.IngestHandler().ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatal("history did not resume", w.Body.String())
	}
}

func TestStreamingEvidenceKeepsAllRecordsAndSOSPrivacy(t *testing.T) {
	s, _, _ := open(t)
	if err := s.ActionMode("start", "from_beginning"); err != nil {
		t.Fatal(err)
	}
	ingest(t, s, observationMessage(t, 1, id()))
	sos := emergency(t, 10001, 1)
	sos.Device = "99999999-9999-4999-8999-999999999999"
	ingest(t, s, sos)
	snapshot(t, s)
	var buffer bytes.Buffer
	if err := s.exportStreamingFieldReport(context.Background(), &buffer); err != nil {
		t.Fatal(err)
	}
	archive, err := zip.NewReader(bytes.NewReader(buffer.Bytes()), int64(buffer.Len()))
	if err != nil {
		t.Fatal(err)
	}
	files := map[string]string{}
	for _, file := range archive.File {
		r, e := file.Open()
		if e != nil {
			t.Fatal(e)
		}
		b, e := io.ReadAll(r)
		r.Close()
		if e != nil {
			t.Fatal(e)
		}
		files[file.Name] = string(b)
		if strings.Contains(string(b), sos.Device) || strings.Contains(string(b), sos.ID) {
			t.Fatalf("streaming ZIP exposed SOS identity in %s: %s", file.Name, string(b))
		}
	}
	if !strings.Contains(files["manifest.json"], `"schema_version":3`) || len(strings.Split(strings.TrimSpace(files["receipts.ndjson"]), "\n")) != 2 || files["projection-decisions.ndjson"] == "" {
		t.Fatal("streaming archive omitted evidence")
	}
	if !strings.Contains(files["field-report.json"], "INCONCLUSIVE") {
		t.Fatal("truncated analysis claimed PASS")
	}
}

func TestLegacyProjectionFormatsMigrateWithoutChangingRawOrGeometry(t *testing.T) {
	for _, chunked := range []bool{true, false} {
		t.Run(fmt.Sprint(chunked), func(t *testing.T) {
			s, now, path := open(t)
			*now = base.Add(24 * time.Hour)
			if err := s.ActionMode("start", "from_beginning"); err != nil {
				t.Fatal(err)
			}
			session := id()
			count := 700
			if !chunked {
				count = 6000
			}
			s.projectionMu.Lock()
			for first := 1; first <= count; first += 40 {
				messages := []Message{}
				for n := first; n < min(first+40, count+1); n++ {
					messages = append(messages, observationMessage(t, n, session))
				}
				if _, err := s.IngestBatch(batchBytes(t, messages)); err != nil {
					t.Fatal(err)
				}
			}
			if err := s.processProjectionLocked(); err != nil {
				t.Fatal(err)
			}
			tx, err := s.db.Begin()
			if err != nil {
				t.Fatal(err)
			}
			original, err := loadState(tx)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = tx.Exec("DELETE FROM projection_device_chunks"); err != nil {
				t.Fatal(err)
			}
			header := savedProjection{Chunked: chunked, Points: original.Points, Decisions: original.Decisions}
			if chunked {
				header.Points, header.Decisions = nil, nil
				for ordinal, first := 0, 0; first < len(original.Points) || first < len(original.Decisions); ordinal, first = ordinal+1, first+512 {
					pn, dn := min(first+512, len(original.Points)), min(first+512, len(original.Decisions))
					pFirst, dFirst := min(first, len(original.Points)), min(first, len(original.Decisions))
					b, _ := json.Marshal(savedProjection{Points: original.Points[pFirst:pn], Decisions: original.Decisions[dFirst:dn]})
					if _, err = tx.Exec("INSERT INTO projection_chunks VALUES(?,?)", ordinal, string(b)); err != nil {
						t.Fatal(err)
					}
				}
			}
			b, _ := json.Marshal(header)
			if _, err = tx.Exec("UPDATE projection_state SET data=? WHERE id=1", string(b)); err != nil {
				t.Fatal(err)
			}
			if err = tx.Commit(); err != nil {
				t.Fatal(err)
			}
			s.projectionMu.Unlock()
			s.Close()
			s, err = Open(path)
			if err != nil {
				t.Fatal(err)
			}
			defer s.Close()
			s.projectionMu.Lock()
			if err = s.processProjectionLocked(); err != nil {
				t.Fatal(err)
			}
			s.projectionMu.Unlock()
			tx, err = s.db.Begin()
			if err != nil {
				t.Fatal(err)
			}
			got, err := loadState(tx)
			tx.Rollback()
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(original.Points, got.Points) || !reflect.DeepEqual(original.Decisions, got.Decisions) {
				t.Fatal("cache migration changed original qualified geometry/decisions/distance")
			}
			var raw int
			if err = s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&raw); err != nil || raw != count {
				t.Fatal("migration changed raw", raw, err)
			}
		})
	}
}

func TestProjectionTurnsDoNotLetARequeuedPhoneStarveAnother(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Hour)
	if err := s.ActionMode("start", "from_beginning"); err != nil {
		t.Fatal(err)
	}
	s.projectionMu.Lock()
	defer s.projectionMu.Unlock()
	session := id()
	first := observationMessage(t, 1, session)
	second := first
	second.Device = "22222222-2222-4222-8222-222222222222"
	ingest(t, s, first)
	ingest(t, s, second)
	if err := s.processProjectionDevice(); err != nil {
		t.Fatal(err)
	}
	// New work for the just-served phone used to have a smaller generation than
	// the existing queue and could repeatedly jump ahead of other devices.
	ingest(t, s, observationMessage(t, 2, session))
	if err := s.processProjectionDevice(); err != nil {
		t.Fatal(err)
	}
	var pending int
	if err := s.db.QueryRow("SELECT COUNT(*) FROM projection_work WHERE device=?", second.Device).Scan(&pending); err != nil || pending != 0 {
		t.Fatal("other phone starved", pending, err)
	}
}

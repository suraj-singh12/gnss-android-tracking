package core

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
	"time"
)

func observationMessage(t *testing.T, n int, session string) Message {
	m := message(t, n, float64(n), float64(n)*5, 0, 1)
	m.Observation = &ObservationIdentity{m.ID, session, base.Format(wireTime), int64(n), int64(n) * 1000}
	return m
}
func batchBytes(t *testing.T, m []Message) []byte {
	return encode(t, map[string]any{"batch_version": 1, "messages": m})
}
func TestAtomicHistoryIdentityAndACKLoss(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Minute)
	session := id()
	a := observationMessage(t, 1, session)
	b := observationMessage(t, 2, session)
	bad := b
	bad.Observation = &ObservationIdentity{bad.ID, session, base.Format(wireTime), 0, 2000}
	if _, e := s.IngestBatch(batchBytes(t, []Message{a, bad})); e == nil {
		t.Fatal("invalid batch accepted")
	}
	var count int
	s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&count)
	if count != 0 {
		t.Fatal("partial batch commit")
	}
	first, e := s.IngestBatch(batchBytes(t, []Message{a, b}))
	if e != nil {
		t.Fatal(e)
	}
	again, e := s.IngestBatch(batchBytes(t, []Message{a, b}))
	if e != nil {
		t.Fatal(e)
	}
	for i, x := range again.Acks {
		if x.Result != "duplicate" || x.Received != first.Acks[i].Received {
			t.Fatal("retry identity/first receipt lost")
		}
	}
	s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&count)
	if count != 2 {
		t.Fatal(count)
	}
}
func TestMultipleOutagesReconciliationEqualsOracle(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	session := id()
	all := []Candidate{}
	for n := 1; n <= 100; n++ {
		all = append(all, raw(observationMessage(t, n, session)))
	}
	order := []int{}
	for n := 1; n <= 40; n++ {
		order = append(order, n)
	}
	order = append(order, 50, 51, 80, 85, 90, 100)
	for n := 41; n <= 100; n++ {
		if n != 50 && n != 51 && n != 80 && n != 85 && n != 90 && n != 100 {
			order = append(order, n)
		}
	}
	for _, n := range order {
		*now = base.Add(time.Duration(n) * time.Second)
		ingest(t, s, all[n-1].Message)
	}
	st := snapshot(t, s)
	expected, decisions := Derive(all, st.Policy, st.Recording.Windows[0].ID, 0)
	expected[0].Reason = "start:" + expected[0].Reason
	// Presentation annotations are not authoritative geometry.
	for i := range st.Points {
		st.Points[i].X = 0
		st.Points[i].Y = 0
		st.Points[i].Dot = false
	}
	if !reflect.DeepEqual(st.Points, expected) || !reflect.DeepEqual(st.Decisions, decisions) {
		t.Fatalf("interrupted geometry differs: %d vs %d", len(st.Points), len(expected))
	}
}
func TestRawACKIndependentOfProjectionFailureAndRestart(t *testing.T) {
	s, now, path := open(t)
	action(t, s, "start")
	*now = base.Add(time.Second)
	_, e := s.db.Exec(`CREATE TRIGGER fail_projection BEFORE DELETE ON projection_work BEGIN SELECT RAISE(ABORT,'projection unavailable'); END`)
	if e != nil {
		t.Fatal(e)
	}
	m := observationMessage(t, 1, id())
	ack, e := s.Ingest(encode(t, m))
	if e != nil || ack.Result != "stored" {
		t.Fatal("raw ACK blocked by projection", e)
	}
	st := snapshot(t, s)
	if !st.ProjectionPending {
		t.Fatal("failed projection presented current")
	}
	s.Close()
	s, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	s.Now = func() time.Time { return *now }
	ack, e = s.Ingest(encode(t, m))
	if e != nil || ack.Result != "duplicate" {
		t.Fatal(e)
	}
	s.db.Exec("DROP TRIGGER fail_projection")
	st = snapshot(t, s)
	if st.ProjectionPending || len(st.Points) != 1 {
		t.Fatal("projection work not restored")
	}
}
func TestQualitySwitchesAndNoHiddenMovementFloor(t *testing.T) {
	keys := []string{"maximum_accuracy_m", "maximum_fix_age_s", "clock_tolerance_s", "maximum_speed_mps", "minimum_forward_m", "minimum_backward_m", "maximum_gap_s", "uncertainty_multiplier"}
	for _, key := range keys {
		t.Run(key, func(t *testing.T) {
			p := DefaultPolicy()
			p.Enabled = map[string]bool{key: false}
			if e := p.Validate(); e != nil {
				t.Fatal(e)
			}
			if p.on(key) {
				t.Fatal("switch ignored")
			}
		})
	}
	p := DefaultPolicy()
	p.Enabled = map[string]bool{"minimum_forward_m": false, "minimum_backward_m": false, "uncertainty_multiplier": false}
	a := message(t, 1, 0, 0, 0, 1)
	b := message(t, 2, 1, .1, 0, 1)
	points, _ := Derive([]Candidate{raw(a), raw(b)}, p, id(), 0)
	if len(points) != 2 {
		t.Fatal("disabled movement still active")
	}
	p.Enabled["maximum_accuracy_m"] = false
	b.Fix.Accuracy = nil
	if Quality(b, p) != "valid" {
		t.Fatal("disabled accuracy still rejects missing accuracy")
	}
}
func TestSessionRecordingAndFromNowBoundaries(t *testing.T) {
	s, now, _ := open(t)
	session := id()
	m := observationMessage(t, 1, session)
	*now = base.Add(20 * time.Second)
	ingest(t, s, m)
	if e := s.ActionMode("start", "session_beginning"); e != nil {
		t.Fatal(e)
	}
	if len(snapshot(t, s).Points) != 1 {
		t.Fatal("session beginning omitted backlog")
	}
	action(t, s, "clear")
	action(t, s, "start")
	if len(snapshot(t, s).Points) != 0 {
		t.Fatal("from now included old observation")
	}
	other := observationMessage(t, 2, id())
	other.Captured = base.Add(time.Second).Format(wireTime)
	other.Fix.Observed = other.Captured
	ingest(t, s, other)
	if len(snapshot(t, s).Points) != 0 {
		t.Fatal("late receipt changed membership")
	}
}
func TestBatchHTTPNegotiationAndListenerSecurity(t *testing.T) {
	s, _, _ := open(t)
	h := s.IngestHandler()
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/capabilities", nil))
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	var v map[string]any
	json.Unmarshal(w.Body.Bytes(), &v)
	if v["observation_identity_version"] != float64(1) {
		t.Fatal(v)
	}
	req := httptest.NewRequest("POST", "/api/v1/history", bytes.NewReader(batchBytes(t, []Message{observationMessage(t, 1, id())})))
	req.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatal(w.Body.String())
	}
	w = httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest("POST", "/local/quality", nil))
	if w.Code != 404 {
		t.Fatal("LAN exposed controls")
	}
}

func TestEachQualitySwitchChangesOnlyItsRule(t *testing.T) {
	for _, key := range []string{"maximum_accuracy_m", "maximum_fix_age_s", "clock_tolerance_s", "maximum_speed_mps", "maximum_gap_s", "uncertainty_multiplier", "minimum_forward_m", "minimum_backward_m"} {
		t.Run(key, func(t *testing.T) {
			p := DefaultPolicy()
			a := message(t, 1, 0, 0, 0, .01)
			b := message(t, 2, 1, 3, 0, .01)
			set := []Candidate{raw(a), raw(b)}
			switch key {
			case "maximum_accuracy_m":
				b.Fix.Accuracy = nil
				set[1] = raw(b)
			case "maximum_fix_age_s":
				b.Fix.Age = 40000
				b.Captured = base.Add(41 * time.Second).Format(wireTime)
				set[1] = raw(b)
			case "clock_tolerance_s":
				b.Captured = base.Add(11 * time.Second).Format(wireTime)
				set[1] = raw(b)
			case "maximum_speed_mps":
				b.Fix.Lon = 100 / Radius / radians
				set[1] = raw(b)
			case "maximum_gap_s":
				b = message(t, 2, 200, 20, 0, .01)
				set[1] = raw(b)
			case "uncertainty_multiplier":
				p.Enabled = map[string]bool{"minimum_forward_m": false, "minimum_backward_m": false}
				v := 3.0
				a.Fix.Accuracy = &v
				b.Fix.Accuracy = &v
				set = []Candidate{raw(a), raw(b)}
			case "minimum_forward_m":
				c := message(t, 3, 2, 6, 0, .01)
				d := message(t, 4, 3, 6.5, 0, .01)
				set = append(set, raw(c), raw(d))
			case "minimum_backward_m":
				c := message(t, 3, 2, 6, 0, .01)
				d := message(t, 4, 3, 5.5, 0, .01)
				set = append(set, raw(c), raw(d))
			}
			enabled, _ := Derive(append([]Candidate{}, set...), p, "test", 0)
			if p.Enabled == nil {
				p.Enabled = map[string]bool{}
			}
			p.Enabled[key] = false
			disabled, _ := Derive(append([]Candidate{}, set...), p, "test", 0)
			if key == "maximum_gap_s" {
				if len(enabled) != 2 || enabled[1].Distance != 0 || disabled[1].Distance <= 0 {
					t.Fatal("gap switch did not remove break")
				}
			} else if len(disabled) <= len(enabled) {
				t.Fatalf("switch did not bypass rule: enabled=%d disabled=%d", len(enabled), len(disabled))
			}
		})
	}
}

func TestProvisionalSectionsReconcileAfterRejectedJunction(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	session := id()
	a := observationMessage(t, 1, session)
	*now = base.Add(time.Second)
	ingest(t, s, a)
	b := observationMessage(t, 50, session)
	poorAccuracy := 100.0
	b.Fix.Accuracy = &poorAccuracy
	*now = base.Add(50 * time.Second)
	ingest(t, s, b)
	c := observationMessage(t, 51, session)
	*now = base.Add(51 * time.Second)
	ingest(t, s, c)
	st := snapshot(t, s)
	if len(st.Provisional) != 1 || st.Provisional[0].Message != c.ID || st.Provisional[0].Distance != 0 || !st.Devices[device].CurrentPosition {
		t.Fatal("later valid live point lost", st.Provisional)
	}
	all := []Candidate{raw(a)}
	for n := 2; n < 50; n++ {
		m := observationMessage(t, n, session)
		ingest(t, s, m)
		all = append(all, raw(m))
	}
	all = append(all, raw(b), raw(c))
	st = snapshot(t, s)
	expected, _ := Derive(all, st.Policy, st.Recording.Windows[0].ID, 0)
	if len(st.Provisional) != 0 || len(st.Points) != len(expected) || st.Devices[device].Total != expected[len(expected)-1].Distance {
		t.Fatal("provisional distance reconciled incorrectly")
	}
}

func TestHistoryFactsRemainSeparateFromLastPhoneQueue(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	session := id()
	*now = base.Add(5 * time.Second)
	a := observationMessage(t, 1, session)
	c := observationMessage(t, 3, session)
	ingest(t, s, a)
	ingest(t, s, c)
	status := message(t, 10, 5, 0, 0, 1)
	status.Type = "status"
	status.Fix = nil
	status.Health.GNSS = "no_fix"
	status.Progress = &QueueProgress{Session: session, SessionStart: base.Format(wireTime), Latest: 3, Pending: 0, Unresolved: []int64{2}, Measured: now.Format(wireTime)}
	ingest(t, s, status)
	st := snapshot(t, s)
	h := st.Devices[device].History
	if len(st.Points) != 2 || len(st.Provisional) != 0 {
		t.Fatal("known unresolved entry blocks processed geometry", st.Points)
	}
	if h.ReceivedThrough != 1 || h.ProcessedThrough != 3 || h.Condition != "unresolved" {
		t.Fatal(h)
	}
	if verdict(report(t, s), "native_history_synchronization") != "FAIL" {
		t.Fatal("unresolved history certified complete")
	}
	status.Sequence++
	status.ID = id()
	status.Captured = now.Add(time.Second).Format(wireTime)
	status.Progress.Unresolved = []int64{}
	status.Progress.Measured = status.Captured
	*now = now.Add(time.Second)
	ingest(t, s, status)
	h = snapshot(t, s).Devices[device].History
	if h.ReceivedThrough != 1 || h.Condition != "catching_up" {
		t.Fatal("zero pending invented missing receipt", h)
	}
	*now = now.Add(time.Hour)
	if h = snapshot(t, s).Devices[device].History; h.PhoneStateCurrent || h.Condition != "unknown" {
		t.Fatal("stale queue presented current", h)
	}
	if verdict(report(t, s), "native_history_synchronization") != "INCONCLUSIVE" {
		t.Fatal("missing evidence manufactured verdict")
	}
	// Later source session cannot erase earlier incomplete session facts.
	next := observationMessage(t, 1, id())
	next.ID = id()
	next.Sequence = 20
	next.Captured = now.Format(wireTime)
	next.Fix.Observed = next.Captured
	next.Observation.ID = next.ID
	next.Observation.SessionStart = next.Captured
	ingest(t, s, next)
	st = snapshot(t, s)
	if len(st.Devices[device].HistorySessions) != 2 || st.Devices[device].History.Session != next.Observation.Session {
		t.Fatal("session histories conflated")
	}
}

func TestSessionModeLateJoinAndResumeKeepBoundaries(t *testing.T) {
	s, now, _ := open(t)
	session := id()
	*now = base.Add(10 * time.Second)
	ingest(t, s, observationMessage(t, 1, session))
	if e := s.ActionMode("start", "session_beginning"); e != nil {
		t.Fatal(e)
	}
	other := observationMessage(t, 1, id())
	other.Device = "22222222-2222-4222-8222-222222222222"
	other.ID = id()
	other.Observation.ID = other.ID
	ingest(t, s, other)
	if st := snapshot(t, s); len(st.Points) != 2 {
		t.Fatal("late joining identified session excluded", st.Points)
	}
	*now = base.Add(15 * time.Second)
	action(t, s, "stop")
	middle := observationMessage(t, 20, session)
	ingest(t, s, middle)
	*now = base.Add(25 * time.Second)
	action(t, s, "resume")
	after := observationMessage(t, 30, session)
	*now = base.Add(30 * time.Second)
	ingest(t, s, after)
	st := snapshot(t, s)
	if len(st.Recording.Windows) != 2 || st.Recording.Windows[1].Sessions[device] != session || st.Devices[device].Total != 0 {
		t.Fatal("resume joined stopped interval", st.Recording, st.Devices[device].Total)
	}
	for _, p := range st.Points {
		if p.Message == middle.ID {
			t.Fatal("stopped observation recorded")
		}
	}
}

func TestOlderCommandRetainedExtensionsBackfillWithoutChangingWire(t *testing.T) {
	s, now, path := open(t)
	*now = base.Add(time.Second)
	m := observationMessage(t, 1, id())
	wire := encode(t, m)
	ingest(t, s, m)
	s.mu.Lock()
	tx, e := s.db.Begin()
	if e != nil {
		t.Fatal(e)
	}
	st, e := loadState(tx)
	if e != nil {
		t.Fatal(e)
	}
	legacy := m
	legacy.Observation = nil
	known := encode(t, legacy)
	if _, e = tx.Exec("UPDATE raw SET known=? WHERE device=? AND message=?", string(known), m.Device, m.ID); e != nil {
		t.Fatal(e)
	}
	if _, e = tx.Exec("DELETE FROM observation_identity"); e != nil {
		t.Fatal(e)
	}
	st.Devices[device].Snapshot = legacy
	st.Devices[device].Location = &legacy
	if e = saveState(tx, st); e != nil {
		t.Fatal(e)
	}
	if e = tx.Commit(); e != nil {
		t.Fatal(e)
	}
	s.mu.Unlock()
	s.Close()
	upgraded, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer upgraded.Close()
	upgraded.Now = func() time.Time { return *now }
	ack, e := upgraded.Ingest(wire)
	if e != nil || ack.Result != "duplicate" {
		t.Fatal("upgraded retry conflicts", e)
	}
	var retained []byte
	if e = upgraded.db.QueryRow("SELECT wire FROM raw WHERE message=?", m.ID).Scan(&retained); e != nil || !bytes.Equal(retained, wire) {
		t.Fatal("wire rewritten", e)
	}
	if h := snapshot(t, upgraded).Devices[device].History; h.ReceivedThrough != 1 {
		t.Fatal(h)
	}
}

func TestNegotiatedHistoryFixtures(t *testing.T) {
	s, _, _ := open(t)
	b := fixture(t, "history-batch-v1/request.json")
	ack, e := s.IngestBatch(b)
	if e != nil || len(ack.Acks) != 1 {
		t.Fatal(e)
	}
	if _, e = decode(fixture(t, "history-batch-v1/ack.json")); e != nil {
		t.Fatal(e)
	}
	retry, e := s.IngestBatch(b)
	if e != nil || retry.Acks[0].Result != "duplicate" || retry.Acks[0].Received != ack.Acks[0].Received {
		t.Fatal(e)
	}
}

func TestLiveSnapshotDoesNotWaitForHistoryProjection(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	s.projectionMu.Lock()
	defer s.projectionMu.Unlock()
	*now = base.Add(50 * time.Second)
	m := observationMessage(t, 50, id())
	ingest(t, s, m)
	done := make(chan State, 1)
	go func() {
		st, e := s.Snapshot(10)
		if e == nil {
			done <- st
		}
	}()
	select {
	case st := <-done:
		if !st.Devices[device].CurrentPosition || st.Devices[device].Location.ID != m.ID || !st.ProjectionPending {
			t.Fatal("live position depended on projection")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("live snapshot waited for historical engine")
	}
}

func TestSessionStartIdentityIsImmutable(t *testing.T) {
	s, now, _ := open(t)
	session := id()
	*now = base.Add(5 * time.Second)
	ingest(t, s, observationMessage(t, 1, session))
	m := observationMessage(t, 2, session)
	m.Observation.SessionStart = base.Add(time.Second).Format(wireTime)
	if _, e := s.IngestBatch(batchBytes(t, []Message{m})); e == nil {
		t.Fatal("same source session changed start boundary")
	}
	var n int
	s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&n)
	if n != 1 {
		t.Fatal("conflicting session committed")
	}
}

func TestOriginalGNSSOrderingIsTotalAcrossClockAnomaliesAndSessions(t *testing.T) {
	p := DefaultPolicy()
	p.Enabled = map[string]bool{"clock_tolerance_s": false, "maximum_speed_mps": false, "maximum_gap_s": false}
	session := id()
	a := observationMessage(t, 1, session)
	a.Fix.Observed = base.Add(100 * time.Second).Format(wireTime)
	b := observationMessage(t, 2, session)
	b.Fix.Observed = base.Add(50 * time.Second).Format(wireTime)
	c := observationMessage(t, 3, id())
	c.Observation.Sequence = 1
	c.Observation.SessionStart = base.Add(60 * time.Second).Format(wireTime)
	c.Fix.Observed = base.Add(75 * time.Second).Format(wireTime)
	legacy := message(t, 4, 65, 500, 0, 1)
	input := []Candidate{raw(a), raw(b), raw(c), raw(legacy)}
	expected, decisions := Derive(append([]Candidate{}, input...), p, "window", 0)
	for _, order := range [][]int{{3, 2, 1, 0}, {1, 3, 0, 2}, {2, 0, 3, 1}} {
		permuted := []Candidate{}
		for _, n := range order {
			permuted = append(permuted, input[n])
		}
		points, outcomes := Derive(permuted, p, "window", 0)
		if !reflect.DeepEqual(points, expected) || !reflect.DeepEqual(outcomes, decisions) {
			t.Fatal("non-total chronological ordering")
		}
	}
	if len(expected) != 3 || expected[2].Distance != 0 || expected[0].Segment == expected[1].Segment || expected[1].Segment == expected[2].Segment {
		t.Fatal("connector across source sessions", expected)
	}
}
func TestLaterCallbackWithOlderMeasurementCannotRegressLive(t *testing.T) {
	s, now, _ := open(t)
	session := id()
	*now = base.Add(time.Second)
	a := observationMessage(t, 1, session)
	ingest(t, s, a)
	b := observationMessage(t, 2, session)
	b.Fix.Observed = base.Format(wireTime)
	b.Fix.Age = 2000
	*now = base.Add(2 * time.Second)
	ingest(t, s, b)
	st := snapshot(t, s)
	if st.Devices[device].Location.ID != a.ID {
		t.Fatal("later callback regressed measurement time")
	}
	if report(t, s).Devices[device].RawFixes != 2 {
		t.Fatal("older real measurement discarded")
	}
}

func TestDelayedHistoryCannotChooseLateJoinCurrentSession(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Second)
	ingest(t, s, observationMessage(t, 1, id()))
	if e := s.ActionMode("start", "session_beginning"); e != nil {
		t.Fatal(e)
	}
	*now = base.Add(100 * time.Second)
	old := observationMessage(t, 1, id())
	old.Device = "22222222-2222-4222-8222-222222222222"
	old.ID = id()
	old.Observation.ID = old.ID
	if _, e := s.ingestRole(encode(t, old), *now, "history"); e != nil {
		t.Fatal(e)
	}
	st := snapshot(t, s)
	if st.Recording.Windows[0].Sessions[old.Device] != "" || len(st.Points) != 1 {
		t.Fatal("old backlog falsely identified a current session")
	}
	current := id()
	status := message(t, 2, 100, 0, 0, 1)
	status.Device = old.Device
	status.ID = id()
	status.Type = "status"
	status.Fix = nil
	status.Health.GNSS = "no_fix"
	status.Progress = &QueueProgress{Session: current, SessionStart: base.Add(90 * time.Second).Format(wireTime), Latest: 1, Pending: 1, OldestPending: func() *int64 { n := int64(1); return &n }(), Unresolved: []int64{}, Measured: now.Format(wireTime)}
	ingest(t, s, status)
	point := observationMessage(t, 3, current)
	point.Device = old.Device
	point.ID = id()
	point.Observation.ID = point.ID
	point.Observation.Sequence = 1
	point.Observation.SessionStart = status.Progress.SessionStart
	point.Captured = base.Add(95 * time.Second).Format(wireTime)
	point.Fix.Observed = point.Captured
	if _, e := s.ingestRole(encode(t, point), *now, "history"); e != nil {
		t.Fatal(e)
	}
	st = snapshot(t, s)
	if st.Recording.Windows[0].Sessions[old.Device] != current || len(st.Points) != 2 {
		t.Fatal("explicit current source session not selected", st.Recording, st.Points)
	}
}
func TestQualitySwitchControlRejectsNonBooleanAndUnknownNames(t *testing.T) {
	s, _, _ := open(t)
	for _, enabled := range []any{map[string]any{"maximum_accuracy_m": nil}, map[string]any{"maximum_accuracy_m": "false"}, map[string]any{"mistyped_rule": false}} {
		body := map[string]any{}
		encoded := encode(t, DefaultPolicy())
		if e := json.Unmarshal(encoded, &body); e != nil {
			t.Fatal(e)
		}
		body["enabled"] = enabled
		request := httptest.NewRequest("POST", "/local/quality", bytes.NewReader(encode(t, body)))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		s.LocalHandler(http.NotFoundHandler()).ServeHTTP(response, request)
		if response.Code != 400 || snapshot(t, s).Policy.Revision != 1 {
			t.Fatal("invalid switch changed policy", response.Body.String())
		}
	}
}

func TestBatchRejectionEvidenceRetainedWithoutPartialRaw(t *testing.T) {
	s, _, path := open(t)
	session := id()
	a := observationMessage(t, 1, session)
	ingest(t, s, a)
	conflict := observationMessage(t, 2, session)
	conflict.Observation.Sequence = 1
	if _, e := s.IngestBatch(batchBytes(t, []Message{conflict})); e == nil {
		t.Fatal("conflicting identity accepted")
	}
	bad := observationMessage(t, 3, session)
	bad.Observation.Sequence = 0
	if _, e := s.IngestBatch(batchBytes(t, []Message{bad})); e == nil {
		t.Fatal("invalid observation accepted")
	}
	s.Close()
	reopened, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	r := report(t, reopened)
	if r.Devices[device].Reports != 1 || r.Devices[device].Conflicts != 1 {
		t.Fatal(r.Devices[device])
	}
	codes := map[string]bool{}
	for _, event := range r.Events {
		if event.Kind == "batch_rejected" {
			if event.BatchEntryIndex == nil || *event.BatchEntryIndex != 0 || event.Message == "" {
				t.Fatal(event)
			}
			codes[event.FailureCode] = true
		}
	}
	if !codes["identity_conflict"] || !codes["invalid_observation"] {
		t.Fatal(codes)
	}
}

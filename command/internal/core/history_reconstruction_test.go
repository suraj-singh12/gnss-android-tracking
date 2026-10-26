package core

import (
	"bytes"
	"fmt"
	"reflect"
	"sort"
	"testing"
	"time"
)

func TestHistoryBeginningWithoutInventory(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(7 * 24 * time.Hour)
	if err := s.ActionMode("start", "session_beginning"); err != nil {
		t.Fatal("From Beginning must start before phones connect:", err)
	}
	m := observationMessage(t, 1, id())
	if _, err := s.ingestRole(encode(t, m), *now, "history"); err != nil {
		t.Fatal(err)
	}
	st := snapshot(t, s)
	if len(st.Points) != 1 {
		t.Fatalf("durably ACKed historical-only late join disappeared: %d points", len(st.Points))
	}
}

func TestHistoryBeginningIncludesEarlierSessions(t *testing.T) {
	s, now, _ := open(t)
	old := observationMessage(t, 1, id())
	current := observationMessage(t, 2, id())
	current.Observation.Sequence = 1
	current.Observation.SessionStart = base.Add(2 * time.Second).Format(wireTime)
	*now = base.Add(2 * time.Second)
	ingest(t, s, current)
	if err := s.ActionMode("start", "session_beginning"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ingestRole(encode(t, old), *now, "history"); err != nil {
		t.Fatal(err)
	}
	st := snapshot(t, s)
	got := []string{}
	for _, p := range st.Points {
		got = append(got, p.Message)
	}
	if !reflect.DeepEqual(got, []string{old.ID, current.ID}) {
		t.Fatalf("ACKed earlier session excluded: %v", got)
	}
	if st.Points[0].Segment == st.Points[1].Segment || st.Devices[device].Total != 0 {
		t.Fatal("connector across sessions")
	}
}

func TestHistoryMissingSequenceStillDisplaysQualifiedSections(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	session := id()
	*now = base.Add(time.Minute)
	for _, n := range []int{1, 3, 4} {
		if _, err := s.ingestRole(encode(t, observationMessage(t, n, session)), *now, "history"); err != nil {
			t.Fatal(err)
		}
	}
	st := snapshot(t, s)
	if len(st.Points) != 3 {
		t.Fatalf("projection completed but read model hid qualified history: %d points, %d decisions", len(st.Points), len(st.Decisions))
	}
	if st.Points[0].Segment == st.Points[1].Segment || st.Points[1].Distance != 0 {
		t.Fatal("invented connector across missing observation")
	}
	if st.Points[1].Segment != st.Points[2].Segment {
		t.Fatal("contiguous recovered section split")
	}
	ingest(t, s, observationMessage(t, 2, session))
	st = snapshot(t, s)
	if len(st.Points) != 4 || st.Points[0].Segment != st.Points[3].Segment {
		t.Fatal("late history did not reconcile gap")
	}
}

// Independent membership selection plus the existing one-pass quality engine is
// the oracle. It does not consult windowIncludes or any projection cache.
func historyReference(raw []Candidate, st State) ([]Point, []Decision) {
	points, decisions := []Point{}, []Decision{}
	ids := []string{}
	for device := range st.Devices {
		ids = append(ids, device)
	}
	sort.Strings(ids)
	totals := map[string]float64{}
	for wi, w := range st.Recording.Windows {
		for _, device := range ids {
			eligible := []Candidate{}
			for _, c := range raw {
				at := instant(c.Fix.Observed)
				before := st.Recording.Mode != "from_beginning" || wi != 0
				if c.Device == device && (!before || !at.Before(w.Start)) && (w.Stop == nil || at.Before(*w.Stop)) {
					eligible = append(eligible, c)
				}
			}
			pp, dd := Derive(eligible, w.Policy, w.ID, totals[device])
			if len(pp) > 0 {
				pp[0].Reason = w.Reason + ":" + pp[0].Reason
				totals[device] = pp[len(pp)-1].Distance
			}
			points = append(points, pp...)
			decisions = append(decisions, dd...)
		}
	}
	return points, decisions
}
func assertHistoryReference(t *testing.T, s *Store, raw []Candidate) State {
	t.Helper()
	st := snapshot(t, s)
	pp, dd := historyReference(raw, st)
	for i := range st.Points {
		st.Points[i].X = 0
		st.Points[i].Y = 0
		st.Points[i].Dot = false
	}
	if !reflect.DeepEqual(pp, st.Points) || !reflect.DeepEqual(dd, st.Decisions) {
		t.Fatalf("chronological reference differs: points %d/%d, decisions %d/%d", len(st.Points), len(pp), len(st.Decisions), len(dd))
	}
	return st
}
func TestHistoryFiveDevicesScrambledSessionsAndRestart(t *testing.T) {
	for _, days := range []int{1, 2, 7} {
		t.Run(fmt.Sprintf("%d_days", days), func(t *testing.T) {
			s, now, path := open(t)
			*now = base.Add(time.Duration(days) * 24 * time.Hour)
			if err := s.ActionMode("start", "from_beginning"); err != nil {
				t.Fatal(err)
			}
			all := []Candidate{}
			groups := [][]Message{}
			for d := 0; d < 5; d++ {
				dev := fmt.Sprintf("%08d-1111-4111-8111-111111111111", d+1)
				for sessionNumber := 0; sessionNumber < 2; sessionNumber++ {
					session := id()
					group := []Message{}
					start := float64(sessionNumber*5000 + d*7)
					for n := 1; n <= 192+d*16; n++ {
						m := message(t, n+sessionNumber*1000, start+float64(n), float64(n*5), float64(d*100), 1)
						m.Device = dev
						m.ID = id()
						m.Observation = &ObservationIdentity{m.ID, session, base.Add(time.Duration(start) * time.Second).Format(wireTime), int64(n), int64(n) * 1000}
						if n%71 == 0 {
							*m.Fix.Accuracy = 100
						}
						all = append(all, Candidate{m, now.Format(wireTime)})
						group = append(group, m)
					}
					groups = append(groups, group)
				}
			}
			// Newer session and newer blocks arrive first; the last device joins later.
			for gi, group := range groups {
				if gi%2 == 0 {
					continue
				}
				for offset := len(group); offset > 0; {
					first := offset - 64
					if first < 0 {
						first = 0
					}
					if _, err := s.IngestBatch(batchBytes(t, group[first:offset])); err != nil {
						t.Fatal(err)
					}
					offset = first
				}
			}
			if err := s.SetPolicy(DefaultPolicy()); err != nil {
				t.Fatal(err)
			}
			s.Close()
			reopened, err := Open(path)
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			reopened.Now = func() time.Time { return *now }
			s = reopened
			s.projectionMu.Lock()
			for gi, group := range groups {
				if gi%2 != 0 {
					continue
				}
				for offset := len(group); offset > 0; {
					first := offset - 64
					if first < 0 {
						first = 0
					}
					if _, err := s.IngestBatch(batchBytes(t, group[first:offset])); err != nil {
						t.Fatal(err)
					}
					offset = first
				}
			}
			retry, err := s.IngestBatch(batchBytes(t, groups[0][:3]))
			if err != nil || retry.Acks[0].Result != "duplicate" {
				t.Fatal(err)
			}
			began := time.Now()
			err = s.processProjectionLocked()
			elapsed := time.Since(began)
			s.projectionMu.Unlock()
			if err != nil {
				t.Fatal(err)
			}
			t.Logf("reconstructed %d historical observations / five devices / ten sessions: %s", len(all), elapsed)
			st := assertHistoryReference(t, s, all)
			for _, d := range st.Devices {
				r := d.Reconstruction
				if r.Received != r.Eligible || r.Processed != r.Eligible || r.Qualified != r.Accepted || r.Rejected == 0 || r.Segments < 2 || r.Projection != "completed" {
					t.Fatal(r)
				}
			}
			// An edit after complete backfill reconsiders unchanged poor raw observations.
			p := st.Policy
			p.Enabled = map[string]bool{"maximum_accuracy_m": false}
			if err = s.SetPolicy(p); err != nil {
				t.Fatal(err)
			}
			assertHistoryReference(t, s, all)
		})
	}
}

func TestHistoryLifecycleOriginalTimesAndClockUncertainty(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Hour)
	if err := s.ActionMode("start", "from_beginning"); err != nil {
		t.Fatal(err)
	}
	*now = base.Add(2 * time.Hour)
	action(t, s, "pause")
	*now = base.Add(3 * time.Hour)
	action(t, s, "resume")
	all := []Candidate{}
	session := id()
	for i, sec := range []float64{0, 1, 7200, 9000, 10800, 10801} {
		m := message(t, i+1, sec, float64(i*5), 0, 1)
		m.Observation = &ObservationIdentity{m.ID, session, base.Format(wireTime), int64(i + 1), int64(i+1) * 1000}
		all = append(all, Candidate{m, base.Add(4 * time.Hour).Format(wireTime)})
	}
	*now = base.Add(4 * time.Hour)
	for i := len(all) - 1; i >= 0; i-- {
		ingest(t, s, all[i].Message)
	}
	st := assertHistoryReference(t, s, all)
	if len(st.Points) != 4 || st.Points[1].Segment == st.Points[2].Segment || st.Devices[device].Reconstruction.Excluded != 2 {
		t.Fatal("half-open Stop/Resume boundaries", st.Points)
	}
	action(t, s, "clear")
	if len(snapshot(t, s).Points) != 0 {
		t.Fatal("Clear resurrected derived data")
	}
	if err := s.ActionMode("start", "from_now"); err != nil {
		t.Fatal(err)
	}
	boundary := snapshot(t, s).Recording.Windows[0].Start
	older := message(t, 10, boundary.Sub(base).Seconds()-1, 10, 0, 1)
	measured := message(t, 11, boundary.Sub(base).Seconds(), 20, 0, 1)
	future := message(t, 12, boundary.Sub(base).Seconds()+60, 30, 0, 1)
	for _, m := range []Message{future, older, measured} {
		ingest(t, s, m)
	}
	st = assertHistoryReference(t, s, append(all, Candidate{future, now.Format(wireTime)}, Candidate{older, now.Format(wireTime)}, Candidate{measured, now.Format(wireTime)}))
	if len(st.Points) != 1 || st.Points[0].Message != measured.ID || !st.Recording.Windows[0].Start.Equal(boundary) || st.Devices[device].Reconstruction.ClockUncertain != 1 || st.Devices[device].Reconstruction.Reasons["future_capture"] != 1 {
		t.Fatal("From Now original-time/clock boundary", st.Devices[device].Reconstruction)
	}
	var retained int
	if err := s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&retained); err != nil || retained != 9 {
		t.Fatal("raw data lost", retained, err)
	}
}

func TestHistoryUpgradePreservesRawWindowsAndRetiredRecordings(t *testing.T) {
	s, now, path := open(t)
	*now = base.Add(10 * time.Second)
	old := observationMessage(t, 1, id())
	ingest(t, s, old)
	if err := s.ActionMode("start", "session_beginning"); err != nil {
		t.Fatal(err)
	}
	st := snapshot(t, s)
	recordingID := st.Recording.ID
	windowID := st.Recording.Windows[0].ID
	// Model the exact old persisted single-session representation.
	s.mu.Lock()
	tx, err := s.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	legacy, err := loadState(tx)
	if err != nil {
		t.Fatal(err)
	}
	legacy.HistoryReconstructionVersion = 0
	legacy.Recording.Mode = "session_beginning"
	legacy.Recording.Windows[0].AllHistory = false
	legacy.Recording.Windows[0].Start = base
	legacy.Recording.Windows[0].Sessions = map[string]string{device: old.Observation.Session}
	if err = saveState(tx, legacy); err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	s.mu.Unlock()
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.Now = func() time.Time { return *now }
	st = snapshot(t, s)
	if st.Recording.ID != recordingID || st.Recording.Windows[0].ID != windowID || st.Recording.Mode != "from_beginning" || !st.Recording.Windows[0].AllHistory || st.Recording.Windows[0].Sessions != nil || len(st.Points) != 1 {
		t.Fatal("non-destructive upgrade failed", st.Recording)
	}
	var wire []byte
	if err = s.db.QueryRow("SELECT wire FROM raw").Scan(&wire); err != nil || !bytes.Equal(wire, encode(t, old)) {
		t.Fatal("migration changed original bytes", err)
	}
	action(t, s, "clear")
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if snapshot(t, s).Recording != nil {
		t.Fatal("retired recording resurrected")
	}
}

func TestHistoryDashboardCachePreservesSummariesAndOptionalRaw(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	session := id()
	for n := 1; n <= 5; n++ {
		*now = base.Add(time.Duration(n*10) * time.Second)
		m := observationMessage(t, n, session)
		m.Captured = now.Format(wireTime)
		m.Fix.Observed = m.Captured
		ingest(t, s, m)
	}
	snapshot(t, s)
	st, err := s.snapshot(30, false, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(st.RawPoints) != 0 || len(st.Points) != 5 || st.Devices[device].Evidence.Reports != 5 || st.Devices[device].Evidence.Observed == nil || *st.Devices[device].Evidence.Observed != 10 {
		t.Fatal("compact read lost geometry/cadence", st.Devices[device])
	}
	cursor := s.viewRaw.Row
	evidenceRow := s.dashboardEvidenceRow
	repeated, err := s.snapshot(30, false, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(repeated.RawPoints) != 5 || s.viewRaw.Row != cursor || s.dashboardEvidenceRow != evidenceRow {
		t.Fatal("unchanged snapshot repeated durable scan")
	}
	m := observationMessage(t, 5, session)
	m.Captured = now.Format(wireTime)
	m.Fix.Observed = m.Captured
	ingest(t, s, m)
	st, err = s.snapshot(30, false, false)
	if err != nil || st.Devices[device].Evidence.Duplicates != 1 || st.Devices[device].Reconstruction.Received != 5 {
		t.Fatal("incremental duplicate summary", err)
	}
	*now = now.Add(time.Hour)
	st, err = s.snapshot(30, false, false)
	if err != nil || st.Devices[device].Evidence.Observed != nil || st.Devices[device].History.Condition == "synchronized" {
		t.Fatal("cached phone/cadence state failed to expire", err)
	}
}

func TestHistoryPendingPolicyDoesNotRenderObsoleteQualification(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	*now = base.Add(time.Second)
	m := message(t, 1, 1, 0, 0, 10)
	ingest(t, s, m)
	if len(snapshot(t, s).Points) != 1 {
		t.Fatal("initial policy failed")
	}
	if _, err := s.db.Exec(`CREATE TRIGGER history_policy_fail BEFORE DELETE ON projection_work BEGIN SELECT RAISE(ABORT,'pending policy projection'); END`); err != nil {
		t.Fatal(err)
	}
	policy := DefaultPolicy()
	policy.Accuracy = 1
	if err := s.SetPolicy(policy); err != nil {
		t.Fatal(err)
	}
	st := snapshot(t, s)
	if len(st.Points) != 0 || st.Devices[device].Total != 0 || st.Devices[device].Reconstruction.Accepted != 0 {
		t.Fatal("obsolete-policy geometry remained authoritative during failed reconstruction")
	}
	if !st.ProjectionPending || st.Devices[device].Reconstruction.Projection != "failed" {
		t.Fatal("projection failure hidden")
	}
	if _, err := s.db.Exec("DROP TRIGGER history_policy_fail"); err != nil {
		t.Fatal(err)
	}
	st = snapshot(t, s)
	if st.Devices[device].Reconstruction.Reasons["poor_accuracy"] != 1 || st.Devices[device].Reconstruction.Route != "quality_filtered" {
		t.Fatal("policy recovery not explained")
	}
}

func TestHistoryVariableDevicePopulationIncludingTwenty(t *testing.T) {
	for _, count := range []int{3, 20} {
		t.Run(fmt.Sprintf("%d_devices", count), func(t *testing.T) {
			s, now, _ := open(t)
			*now = base.Add(7 * 24 * time.Hour)
			if err := s.ActionMode("start", "from_beginning"); err != nil {
				t.Fatal(err)
			}
			all := []Candidate{}
			s.projectionMu.Lock()
			for d := 0; d < count; d++ {
				dev := fmt.Sprintf("%08d-2222-4222-8222-222222222222", d+1)
				for sessionIndex := 0; sessionIndex < 2; sessionIndex++ {
					session := id()
					group := []Message{}
					for n := 1; n <= 20+d; n++ {
						start := float64(d*11 + sessionIndex*4000)
						m := message(t, sessionIndex*100+n, start+float64(n), float64(n*5), float64(d*100), 1)
						m.Device = dev
						m.ID = id()
						m.Observation = &ObservationIdentity{m.ID, session, base.Add(time.Duration(start) * time.Second).Format(wireTime), int64(n), int64(n) * 1000}
						if n == 7 {
							*m.Fix.Accuracy = 100
						}
						group = append(group, m)
						all = append(all, Candidate{m, now.Format(wireTime)})
					}
					for i, j := 0, len(group)-1; i < j; i, j = i+1, j-1 {
						group[i], group[j] = group[j], group[i]
					}
					if _, err := s.IngestBatch(batchBytes(t, group)); err != nil {
						t.Fatal(err)
					}
				}
			}
			began := time.Now()
			err := s.processProjectionLocked()
			elapsed := time.Since(began)
			s.projectionMu.Unlock()
			if err != nil {
				t.Fatal(err)
			}
			st := assertHistoryReference(t, s, all)
			if len(st.Devices) != count {
				t.Fatal("device population truncated")
			}
			for d := 0; d < count; d++ {
				dev := fmt.Sprintf("%08d-2222-4222-8222-222222222222", d+1)
				r := st.Devices[dev].Reconstruction
				if r.Received != 2*(20+d) || r.Eligible != r.Received || r.Processed != r.Eligible || r.Rejected != 2 || len(st.Devices[dev].HistorySessions) != 2 || r.Segments != 4 {
					t.Fatal("cross-device contamination or missing population", dev, r)
				}
			}
			t.Logf("dynamic population %d devices / %d sessions / %d observations: projection %s", count, count*2, len(all), elapsed)
		})
	}
}

// A fresh Command B has no prior Android receipts. The phone sends only pending
// observations from the middle of a tracking session; missing earlier sequences
// must neither block the available route nor fabricate previous movement.
// Command's original raw is reusable by a later From Beginning recording without
// asking Android to replay already ACKed observations.
func TestHistoryFreshCommandPendingOnlyAndRecordLater(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(7 * 24 * time.Hour)
	if err := s.ActionMode("start", "from_now"); err != nil {
		t.Fatal(err)
	}
	session := id()
	pending := []Message{
		observationMessage(t, 120, session),
		observationMessage(t, 121, session),
		observationMessage(t, 122, session),
	}
	for _, m := range []Message{pending[2], pending[0], pending[1]} {
		if _, err := s.ingestRole(encode(t, m), *now, "history"); err != nil {
			t.Fatal(err)
		}
	}
	st := snapshot(t, s)
	if len(st.Points) != 0 || st.Devices[device].Reconstruction.Received != 3 ||
		st.Devices[device].Reconstruction.Excluded != 3 {
		t.Fatalf("From Now cannot admit old backlog, but must store it: %+v", st.Devices[device].Reconstruction)
	}
	var before int
	if err := s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&before); err != nil || before != 3 {
		t.Fatal("raw backlog not durable", before, err)
	}
	action(t, s, "clear")
	if err := s.ActionMode("start", "from_beginning"); err != nil {
		t.Fatal(err)
	}
	st = snapshot(t, s)
	if len(st.Points) != 3 || st.Devices[device].Reconstruction.Eligible != 3 ||
		st.Devices[device].Reconstruction.Accepted != 3 ||
		st.Devices[device].Reconstruction.Projection != "completed" {
		t.Fatalf("fresh receiver / late selection failed: %+v", st.Devices[device].Reconstruction)
	}
	if st.Devices[device].Total <= 0 {
		t.Fatal("pending-only new Command must calculate the known section distance")
	}
	var after int
	if err := s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&after); err != nil || after != before {
		t.Fatal("re-recording duplicated or lost retained raw data", before, after, err)
	}
}

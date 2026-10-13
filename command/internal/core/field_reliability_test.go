package core

import (
	"crypto/sha256"
	"database/sql"
	"fmt"
	"math"
	"os"
	"reflect"
	"testing"
	"time"
)

func TestOfflineRectanglePermutationsAndFullReprojection(t *testing.T) {
	var truth []Point
	for _, order := range [][]int{{0, 1, 2, 3, 4}, {4, 0, 1, 2, 3}, {2, 4, 0, 3, 1}, {4, 0, 1, 1, 2, 3}} {
		s, now, path := open(t)
		action(t, s, "start")
		strict := DefaultPolicy()
		strict.Accuracy = 2
		if err := s.SetPolicy(strict); err != nil {
			t.Fatal(err)
		}
		observations := []Message{message(t, 1, 0, 0, 0, 1), message(t, 2, 5, 20, 0, 1), message(t, 3, 10, 20, 20, 3), message(t, 4, 15, 0, 20, 1), message(t, 5, 20, 0, 0, 1)}
		*now = base.Add(20 * time.Second)
		for _, i := range order {
			ingest(t, s, observations[i])
		}
		if !snapshot(t, s).Devices[device].CurrentPosition {
			t.Fatal("fresh live position missing")
		}
		p := DefaultPolicy()
		p.Accuracy = 30
		if err := s.SetPolicy(p); err != nil {
			t.Fatal(err)
		}
		st := snapshot(t, s)
		if len(st.Points) != 5 || math.Abs(st.Devices[device].Total-80) > 1e-5 || len(st.Recording.Windows) != 1 {
			t.Fatal("whole route not reprojected", st)
		}
		if truth == nil {
			truth = st.Points
		} else {
			for i, p := range st.Points {
				if p.Message != truth[i].Message || p.Distance != truth[i].Distance || p.X != truth[i].X || p.Y != truth[i].Y || (p.Segment == st.Points[0].Segment) != (truth[i].Segment == truth[0].Segment) {
					t.Fatal("arrival changed truth", order)
				}
			}
		}
		before := st.Points
		s.Close()
		reopened, err := Open(path)
		if err != nil {
			t.Fatal(err)
		}
		reopened.Now = func() time.Time { return *now }
		after := snapshot(t, reopened)
		if !reflect.DeepEqual(before, after.Points) {
			t.Fatal("restart projection changed")
		}
		r := report(t, reopened)
		if r.Devices[device].Reports != 5 || len(r.ProjectionDecisions) != 5 || r.Devices[device].ProjectionReasons["accepted"] != 5 {
			t.Fatal("raw/derived evidence", r.Devices[device])
		}
		action(t, reopened, "clear")
		ingest(t, reopened, message(t, 6, 7, 5, 0, 1))
		if len(snapshot(t, reopened).Points) != 0 {
			t.Fatal("cleared recording resurrected")
		}
		reopened.Close()
	}
}

func TestLivePositionIndependentOfRecordedHistory(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	d := snapshot(t, s).Devices[device]
	if !d.CurrentPosition || d.LiveX == nil || len(snapshot(t, s).Points) != 0 {
		t.Fatal("no-recording live marker unavailable")
	}
	action(t, s, "start")
	*now = base.Add(10 * time.Second)
	ingest(t, s, message(t, 2, 10, 10000, 0, 1))
	d = snapshot(t, s).Devices[device]
	if !d.CurrentPosition {
		t.Fatal("history gates current GNSS")
	}
	// Received a stale source: contact is healthy while position is unavailable.
	*now = base.Add(45 * time.Second)
	st := snapshot(t, s)
	if st.Devices[device].CurrentPosition || st.Devices[device].LiveX != nil || st.Devices[device].GNSSCondition != "stale" {
		t.Fatal("stale marker remained", st.Devices[device])
	}
	status := message(t, 3, 45, 0, 0, 1)
	status.Type = "status"
	status.Fix = nil
	status.Health.GNSS = "no_fix"
	ingest(t, s, status)
	d = snapshot(t, s).Devices[device]
	if d.ContactCondition != "healthy" || d.CurrentPosition || d.LiveX != nil {
		t.Fatal("contact is not live-position health", d)
	}
}

func TestQualityEditMergesLegacyPolicyOnlyWindows(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	*now = base.Add(10 * time.Second)
	if err := s.change("legacy_fixture", "", func(st *State, at time.Time, _tx *sql.Tx) error {
		r := st.Recording
		r.Windows[0].Stop = &at
		r.Windows = append(r.Windows, Window{ID: id(), Start: at, Policy: st.Policy, Reason: "policy_change"})
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	*now = base.Add(20 * time.Second)
	action(t, s, "stop")
	*now = base.Add(30 * time.Second)
	action(t, s, "resume")
	p := DefaultPolicy()
	p.Accuracy = 30
	if err := s.SetPolicy(p); err != nil {
		t.Fatal(err)
	}
	st := snapshot(t, s)
	if len(st.Recording.Windows) != 2 || st.Recording.Windows[0].Stop == nil || !st.Recording.Windows[0].Stop.Equal(base.Add(20*time.Second)) || st.Recording.Windows[1].Reason != "resume" {
		t.Fatal("lifecycle boundaries lost", st.Recording)
	}
}

func TestEvidenceDoesNotCertifyStatusAsCurrentPosition(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	*now = base.Add(90 * time.Second)
	m := message(t, 2, 90, 0, 0, 1)
	m.Type = "status"
	m.Fix = nil
	m.Health.GNSS = "no_fix"
	ingest(t, s, m)
	r := report(t, s)
	if verdict(r, "reconnect_current_first") != "INCONCLUSIVE" {
		t.Fatal("status falsely certifies position", r.Verdicts)
	}
	want := fmt.Sprintf("%x", sha256.Sum256([]byte(m.ID)))
	if r.Receipts[1].ReportReference != want || r.Devices[device].RoutineReports != 1 || r.Devices[device].LocationReports != 1 {
		t.Fatal("correlation/counts", r)
	}
}

func TestCadenceExpiresAfterContactLoss(t *testing.T) {
	s, now, _ := open(t)
	for i := 0; i < 5; i++ {
		*now = base.Add(time.Duration(i*10) * time.Second)
		ingest(t, s, message(t, i+1, float64(i*10), float64(i), 0, 1))
	}
	if report(t, s).Devices[device].Observed == nil {
		t.Fatal("healthy cadence missing")
	}
	*now = base.Add(80 * time.Second)
	if report(t, s).Devices[device].Observed != nil {
		t.Fatal("stale cadence advertised as current")
	}
}

func TestIntermediateCallbacksDoNotInventOneSecondLiveCadence(t *testing.T) {
	s, now, _ := open(t)
	n := 1
	for tick := 0; tick < 5; tick++ {
		sec := float64(tick * 5)
		*now = base.Add(time.Duration(sec) * time.Second)
		ingest(t, s, message(t, n, sec, sec, 0, 1))
		n++
		if tick > 0 {
			for earlier := tick*5 - 4; earlier < tick*5; earlier++ {
				ingest(t, s, message(t, n, float64(earlier), float64(earlier), 0, 1))
				n++
			}
		}
	}
	r := report(t, s)
	if r.Devices[device].Observed == nil || *r.Devices[device].Observed != 5 {
		t.Fatal("history polluted live cadence", r.Devices[device])
	}
	if r.Devices[device].DelayedLocations != 16 {
		t.Fatal("intermediate history missing", r.Devices[device])
	}
	if snapshot(t, s).Devices[device].Location.Fix.Observed != base.Add(20*time.Second).Format(wireTime) {
		t.Fatal("history regressed live position")
	}
}

func TestQualityEditCannotRefreshStaleLiveMeasurement(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	*now = base.Add(45 * time.Second)
	p := DefaultPolicy()
	p.Age = 600
	if err := s.SetPolicy(p); err != nil {
		t.Fatal(err)
	}
	d := snapshot(t, s).Devices[device]
	if d.CurrentPosition || d.GNSSCondition != "stale" || d.LiveX != nil {
		t.Fatal("quality edit invented freshness", d)
	}
}

func TestFiveMinuteNativeBacklogRetainsEveryObservation(t *testing.T) {
	s, now, path := open(t)
	action(t, s, "start")
	*now = base.Add(300 * time.Second)
	started := time.Now()
	for i := 299; i >= 0; i-- {
		ingest(t, s, message(t, i+1, float64(i), float64(i*5), 0, 1))
	}
	st := snapshot(t, s)
	r := report(t, s)
	if r.Devices[device].Reports != 300 || r.Devices[device].RawFixes != 300 || len(st.Points) != 300 || math.Abs(st.Devices[device].Total-1495) > 0.001 {
		t.Fatal("native backlog changed truth", r.Devices[device])
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("300 native observations: %v, SQLite main file %d bytes (WAL may hold more)", time.Since(started), info.Size())
}

func TestReceiptFreshnessCannotBeManufacturedByLaterQualityEdit(t *testing.T) {
	s, now, _ := open(t)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	*now = base.Add(90 * time.Second)
	ingest(t, s, message(t, 2, 90, 1, 0, 30))
	if verdict(report(t, s), "reconnect_current_first") != "INCONCLUSIVE" {
		t.Fatal("poor source certified current")
	}
	p := DefaultPolicy()
	p.Accuracy = 50
	if err := s.SetPolicy(p); err != nil {
		t.Fatal(err)
	}
	r := report(t, s)
	if verdict(r, "reconnect_current_first") != "INCONCLUSIVE" || r.Receipts[1].FreshAtReceipt == nil || *r.Receipts[1].FreshAtReceipt {
		t.Fatal("quality edit invented original freshness", r.Receipts[1])
	}
	*now = base.Add(180 * time.Second)
	m := message(t, 3, 180, 2, 0, 1)
	m.Health.GNSS = "no_fix"
	if _, fresh := receiptQuality(m, (*now).Format(wireTime), p); fresh {
		t.Fatal("no-fix health certified current")
	}
}

func TestHistoricalSwitchesCannotInventLiveFreshness(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	p := DefaultPolicy()
	p.Enabled = map[string]bool{"clock_tolerance_s": false, "maximum_fix_age_s": false}
	p.Clock, p.Age = 86400, 86400
	if e := s.SetPolicy(p); e != nil {
		t.Fatal(e)
	}
	future := message(t, 1, 0, 0, 0, 1)
	future.Fix.Observed = base.Add(20 * time.Second).Format(wireTime)
	future.Captured = future.Fix.Observed
	future.Observation = &ObservationIdentity{future.ID, id(), base.Format(wireTime), 1, 1000}
	ingest(t, s, future)
	if d := snapshot(t, s).Devices[device]; d.CurrentPosition || d.Session != nil {
		t.Fatal("future measurement became current or enrolled a session when historical clock rule disabled")
	}
	if _, fresh := receiptQuality(future, now.Format(wireTime), p); fresh {
		t.Fatal("future receipt certified fresh")
	}
	*now = base.Add(time.Second)
	fresh := message(t, 2, 1, 5, 0, 1)
	fresh.Observation = &ObservationIdentity{fresh.ID, future.Observation.Session, base.Format(wireTime), 2, 2000}
	ingest(t, s, fresh)
	if d := snapshot(t, s).Devices[device]; !d.CurrentPosition || d.Location.ID != fresh.ID || d.Snapshot.ID != fresh.ID {
		t.Fatal("future raw candidate pinned later genuine current data")
	}
	var retained int
	s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&retained)
	if retained != 2 {
		t.Fatal("future raw observation deleted")
	}
	*now = base.Add(30 * time.Second)
	old := message(t, 3, 30, 0, 0, 1)
	old.Fix.Age = 60000
	ingest(t, s, old)
	if snapshot(t, s).Devices[device].CurrentPosition {
		t.Fatal("stale-at-capture became current")
	}
	if _, fresh := receiptQuality(old, now.Format(wireTime), p); fresh {
		t.Fatal("stale-at-capture receipt certified fresh")
	}
}

package core

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http/httptest"
	"reflect"
	"runtime/debug"
	"strings"
	"testing"
	"time"
)

func report(t *testing.T, s *Store) FieldReport {
	t.Helper()
	r, e := s.FieldReport()
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func verdict(r FieldReport, scenario string) string {
	for _, v := range r.Verdicts {
		if v.Scenario == scenario {
			return v.Result
		}
	}
	return ""
}
func TestEvidenceUniqueReportsObservationsAndTracks(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	m := message(t, 1, 0, 0, 0, 1)
	*now = base
	first := ingest(t, s, m)
	*now = base.Add(time.Second)
	duplicate := ingest(t, s, m)
	if duplicate.Received != first.Received {
		t.Fatal("lost first receipt")
	}
	repeated := m
	repeated.ID = id()
	repeated.Sequence = 2
	repeated.Captured = now.Format(wireTime)
	repeated.Fix = &Fix{}
	*repeated.Fix = *m.Fix
	repeated.Fix.Age = 1000
	ingest(t, s, repeated)
	*now = base.Add(5 * time.Second)
	ingest(t, s, message(t, 3, 5, 5, 0, 1))
	r := report(t, s)
	c := r.Devices[device]
	if c.Reports != 3 || c.RawFixes != 2 || c.Useful != 2 || c.Duplicates != 1 {
		t.Fatalf("counters %+v", c)
	}
	if len(r.Receipts) != 3 || len(r.Events) < 5 {
		t.Fatal("missing chronology")
	}
}
func TestEvidenceConflictPersistedWithoutRawInsertion(t *testing.T) {
	s, _, _ := open(t)
	m := message(t, 1, 0, 0, 0, 1)
	ingest(t, s, m)
	m.ID = id()
	_, e := s.Ingest(encode(t, m))
	if !errors.Is(e, ErrConflict) {
		t.Fatal(e)
	}
	r := report(t, s)
	if r.Devices[device].Reports != 1 || r.Devices[device].Conflicts != 1 {
		t.Fatal(r.Devices)
	}
	last := r.Events[len(r.Events)-1]
	if last.ConflictingSequence != 1 || last.ConflictingMessage == "" {
		t.Fatal(last)
	}
}
func TestEvidenceCadenceExcludesBacklogAndRetries(t *testing.T) {
	s, now, _ := open(t)
	for n := 1; n <= 5; n++ {
		sec := float64(n * 5)
		*now = base.Add(time.Duration(sec) * time.Second)
		m := message(t, n, sec, 0, 0, 1)
		m.Config.Local = 5
		m.Config.Effective = 5
		ingest(t, s, m)
		ingest(t, s, m)
	}
	*now = now.Add(time.Millisecond)
	m := message(t, 6, -60, 0, 0, 1)
	m.Config.Local = 5
	m.Config.Effective = 5
	ingest(t, s, m)
	r := report(t, s)
	c := r.Devices[device]
	if c.Observed == nil || *c.Observed != 5 || c.Expected != 5 || r.Receipts[5].Class != "delayed_backlog" {
		t.Fatalf("%+v", r)
	}
}
func TestEvidenceThirtyFiveThirtyConvergence(t *testing.T) {
	s, now, _ := open(t)
	m := message(t, 1, 0, 0, 0, 1)
	m.Config.Local = 30
	m.Config.Effective = 30
	*now = base
	ingest(t, s, m)
	n := 1
	sec := 0
	for _, override := range []*int{func() *int { v := 5; return &v }(), nil} {
		if e := s.SetOverride(device, override); e != nil {
			t.Fatal(e)
		}
		a := ingest(t, s, m)
		effective := 30
		if override != nil {
			effective = *override
		}
		for j := 0; j < 5; j++ {
			n++
			sec += effective
			*now = base.Add(time.Duration(sec) * time.Second)
			cur := message(t, n, float64(sec), 0, 0, 1)
			cur.Config = ConfigState{Config: a.Config, Local: 30, Effective: effective}
			ingest(t, s, cur)
			m = cur
		}
		r := report(t, s)
		if r.Devices[device].Observed == nil || *r.Devices[device].Observed != float64(effective) {
			t.Fatal(r.Devices[device])
		}
	}
	r := report(t, s)
	found := 0
	for _, v := range r.Verdicts {
		if v.Scenario == "reporting_config_convergence" {
			found++
			if v.Result != "PASS" {
				t.Fatal(v)
			}
		}
	}
	if found != 2 {
		t.Fatal(r.Verdicts)
	}
}
func TestEvidenceCurrentFirstAndBacklogFirst(t *testing.T) {
	for _, currentFirst := range []bool{true, false} {
		t.Run(map[bool]string{true: "current_first", false: "backlog_first"}[currentFirst], func(t *testing.T) {
			s, now, _ := open(t)
			*now = base
			ingest(t, s, message(t, 1, 0, 0, 0, 1))
			*now = base.Add(90 * time.Second)
			current := message(t, 4, 90, 10, 0, 1)
			old := message(t, 2, 10, 5, 0, 1)
			if currentFirst {
				ingest(t, s, current)
				ingest(t, s, old)
			} else {
				ingest(t, s, old)
				ingest(t, s, current)
			}
			r := report(t, s)
			want := "FAIL"
			if currentFirst {
				want = "PASS"
			}
			if verdict(r, "reconnect_current_first") != want || len(r.Gaps) != 1 {
				t.Fatal(r)
			}
			if currentFirst && len(r.Gaps[0].OlderFollowing) != 1 {
				t.Fatal("missing drain metadata")
			}
			for _, v := range r.Receipts {
				if v.LiveAfter < v.LiveBefore {
					t.Fatal("live regressed")
				}
			}
			if snapshot(t, s).Devices[device].Location.ID != current.ID {
				t.Fatal("live state wrong")
			}
		})
	}
}
func TestEvidenceRestartDedupePersists(t *testing.T) {
	s, now, path := open(t)
	action(t, s, "start")
	m := message(t, 1, 0, 0, 0, 1)
	*now = base
	first := ingest(t, s, m)
	v := 5
	if e := s.SetOverride(device, &v); e != nil {
		t.Fatal(e)
	}
	before := report(t, s)
	s.Close()
	reopened, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	reopened.Now = func() time.Time { return base.Add(time.Minute) }
	a := ingest(t, reopened, m)
	r := report(t, reopened)
	if a.Result != "duplicate" || a.Received != first.Received || r.Devices[device].Reports != 1 || r.Devices[device].Useful != before.Devices[device].Useful || verdict(r, "command_restart_dedupe") != "PASS" {
		t.Fatal(r)
	}
	if snapshot(t, reopened).Recording.ID != before.Recording.Recording.ID || *a.Config.Override != 5 {
		t.Fatal("state lost")
	}
}
func TestEvidenceRecordingWindowsClearRetainsEvidence(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	*now = base.Add(5 * time.Second)
	ingest(t, s, message(t, 2, 5, 5, 0, 1))
	*now = base.Add(6 * time.Second)
	action(t, s, "stop")
	*now = base.Add(10 * time.Second)
	ingest(t, s, message(t, 3, 10, 100, 0, 1))
	*now = base.Add(15 * time.Second)
	action(t, s, "resume")
	*now = base.Add(20 * time.Second)
	ingest(t, s, message(t, 4, 20, 200, 0, 1))
	*now = base.Add(25 * time.Second)
	ingest(t, s, message(t, 5, 25, 205, 0, 1))
	r := report(t, s)
	if len(r.Recording.Recording.Windows) != 2 || len(r.Recording.Points) != 4 || r.Recording.Distances[device] > 11 {
		t.Fatal(r.Recording)
	}
	id := r.Recording.Recording.ID
	action(t, s, "clear")
	r = report(t, s)
	if r.Recording.Recording != nil || r.Devices[device].Reports != 5 || r.Devices[device].Useful != 0 {
		t.Fatal(r)
	}
	last := r.Events[len(r.Events)-1]
	if last.Kind != "recording_clear" || last.Before.Recording.ID != id || len(last.Before.Points) != 4 || last.After.Recording != nil {
		t.Fatal(last)
	}
	*now = base.Add(30 * time.Second)
	ingest(t, s, message(t, 6, 4, 4, 0, 1))
	if report(t, s).Devices[device].Useful != 0 {
		t.Fatal("clear resurrected")
	}
}
func TestEvidenceConservativeInsufficientData(t *testing.T) {
	s, _, _ := open(t)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	v := 5
	if e := s.SetOverride(device, &v); e != nil {
		t.Fatal(e)
	}
	r := report(t, s)
	if verdict(r, "reporting_config_convergence") != "INCONCLUSIVE" || verdict(r, "command_restart_dedupe") != "INCONCLUSIVE" || r.Devices[device].Observed != nil {
		t.Fatal(r)
	}
}
func TestEvidenceExportPrivacyAndLocalBoundary(t *testing.T) {
	s, _, _ := open(t)
	action(t, s, "start")
	m := message(t, 1, 0, 0, 0, 1)
	m.Party.Name = "private-party-secret"
	ingest(t, s, m)
	before := snapshot(t, s)
	var b bytes.Buffer
	if e := s.ExportFieldReport(&b); e != nil {
		t.Fatal(e)
	}
	z, e := zip.NewReader(bytes.NewReader(b.Bytes()), int64(b.Len()))
	if e != nil {
		t.Fatal(e)
	}
	if len(z.File) != 2 {
		t.Fatal(z.File)
	}
	for _, f := range z.File {
		rd, e := f.Open()
		if e != nil {
			t.Fatal(e)
		}
		data, e := io.ReadAll(rd)
		rd.Close()
		if e != nil {
			t.Fatal(e)
		}
		for _, secret := range []string{"latitude", "longitude", "altitude", "private-party-secret", "\"wire\"", "command_url"} {
			if strings.Contains(string(data), secret) {
				t.Fatal("privacy leak", secret)
			}
		}
		var v map[string]any
		if e = json.Unmarshal(data, &v); e != nil {
			t.Fatal(e)
		}
		if v["build"].(map[string]any)["source_revision"] != BuildRevision().Source {
			t.Fatal("revision mismatch")
		}
	}
	if !reflect.DeepEqual(before, snapshot(t, s)) {
		t.Fatal("export changed state")
	}
	req := httptest.NewRequest("GET", "http://localhost/local/field-report", nil)
	w := httptest.NewRecorder()
	s.LocalHandler(nil).ServeHTTP(w, req)
	if w.Code != 200 || w.Header().Get("Content-Type") != "application/zip" {
		t.Fatal(w)
	}
	w = httptest.NewRecorder()
	s.IngestHandler().ServeHTTP(w, req)
	if w.Code != 404 {
		t.Fatal("LAN exposed report")
	}
}
func TestEvidenceStorageFailureNeverAcknowledges(t *testing.T) {
	s, _, _ := open(t)
	if _, e := s.db.Exec("CREATE TRIGGER fail_evidence BEFORE INSERT ON field_evidence BEGIN SELECT RAISE(ABORT,'disk failure'); END"); e != nil {
		t.Fatal(e)
	}
	_, e := s.Ingest(encode(t, message(t, 1, 0, 0, 0, 1)))
	if e == nil {
		t.Fatal("ACK before evidence commit")
	}
	var n int
	if e = s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&n); e != nil || n != 0 {
		t.Fatal("partial commit", n, e)
	}
}

func TestEvidenceCadenceMismatchFailsWithSufficientSamples(t *testing.T) {
	s, now, _ := open(t)
	m := message(t, 1, 0, 0, 0, 1)
	*now = base
	ingest(t, s, m)
	v := 5
	if e := s.SetOverride(device, &v); e != nil {
		t.Fatal(e)
	}
	a := ingest(t, s, m)
	for n := 2; n <= 6; n++ {
		sec := float64(n * 10)
		*now = base.Add(time.Duration(sec) * time.Second)
		cur := message(t, n, sec, 0, 0, 1)
		cur.Config = ConfigState{Config: a.Config, Local: 30, Effective: 5}
		ingest(t, s, cur)
	}
	r := report(t, s)
	if verdict(r, "reporting_config_convergence") != "FAIL" {
		t.Fatal(r.Verdicts)
	}
}
func TestEvidenceClockAnomalyDoesNotPassReconnect(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	*now = base.Add(time.Minute)
	ingest(t, s, message(t, 2, 120, 0, 0, 1))
	r := report(t, s)
	if verdict(r, "reconnect_current_first") != "INCONCLUSIVE" || r.Devices[device].Observed != nil {
		t.Fatal(r)
	}
}
func TestEvidenceStatusWithoutFixDoesNotInventObservation(t *testing.T) {
	s, _, _ := open(t)
	m := message(t, 1, 0, 0, 0, 1)
	m.Fix = nil
	m.Type = "status"
	m.Health.GNSS = "no_fix"
	ingest(t, s, m)
	r := report(t, s)
	if r.Devices[device].Reports != 1 || r.Devices[device].RawFixes != 0 || r.Receipts[0].FixPresent {
		t.Fatal(r)
	}
}
func TestEvidenceRecordingAssessmentAfterClear(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	*now = base.Add(time.Second)
	action(t, s, "stop")
	*now = base.Add(2 * time.Second)
	action(t, s, "resume")
	*now = base.Add(3 * time.Second)
	ingest(t, s, message(t, 2, 3, 100, 0, 1))
	action(t, s, "clear")
	r := report(t, s)
	if verdict(r, "recording_stop_resume") != "PASS" {
		t.Fatal(r.Verdicts)
	}
	var last Evidence
	last = r.Events[len(r.Events)-1]
	if len(last.Before.Participation) != 2 || last.Before.Participation[1].StartDistance != 0 {
		t.Fatal(last)
	}
}

func TestEvidenceExactRevisionAndUnavailableIdentity(t *testing.T) {
	sha := "c2a948d174e81b37b1c4515fc49a2f532bddc34c"
	r := revisionFromSettings([]debug.BuildSetting{{Key: "vcs.revision", Value: sha}, {Key: "vcs.modified", Value: "false"}})
	if r.Source != sha || r.Modified {
		t.Fatal(r)
	}
	if revisionFromSettings(nil).Source != "unknown" {
		t.Fatal("invented revision")
	}
	r = revisionFromSettings([]debug.BuildSetting{{Key: "vcs.revision", Value: sha}, {Key: "vcs.modified", Value: "true"}})
	if !r.Modified {
		t.Fatal("lost dirty flag")
	}
}

func TestEvidenceOngoingGapAndDeterministicExport(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	*now = base.Add(100 * time.Second)
	r := report(t, s)
	if len(r.Gaps) != 1 || r.Gaps[0].Recovered || r.Gaps[0].GapBeginsAfter == "" || r.Gaps[0].Duration != 100 {
		t.Fatal(r.Gaps)
	}
	var a, b bytes.Buffer
	if e := s.ExportFieldReport(&a); e != nil {
		t.Fatal(e)
	}
	if e := s.ExportFieldReport(&b); e != nil {
		t.Fatal(e)
	}
	if !bytes.Equal(a.Bytes(), b.Bytes()) {
		t.Fatal("nondeterministic export")
	}
}
func TestEvidenceSameReceiptTimestampUsesCommitOrder(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	*now = base.Add(100 * time.Second)
	ingest(t, s, message(t, 4, 100, 10, 0, 1))
	ingest(t, s, message(t, 2, 10, 5, 0, 1))
	ingest(t, s, message(t, 3, 20, 7, 0, 1))
	r := report(t, s)
	if r.Receipts[1].Sequence != 4 || r.Receipts[2].Sequence != 2 || r.Receipts[3].Sequence != 3 || verdict(r, "reconnect_current_first") != "PASS" {
		t.Fatal(r.Receipts)
	}
}
func TestEvidenceLegacyUpgradeDoesNotInventArrivalProof(t *testing.T) {
	s, _, _ := open(t)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	if _, e := s.db.Exec("DELETE FROM field_evidence"); e != nil {
		t.Fatal(e)
	}
	r := report(t, s)
	if r.Devices[device].Reports != 1 || r.Receipts[0].Kind != "legacy_stored" || verdict(r, "reconnect_current_first") == "PASS" {
		t.Fatal(r)
	}
}

func TestEvidenceWallClockRollbackPreservesActualCommitOrder(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Minute)
	ingest(t, s, message(t, 1, 60, 0, 0, 1))
	*now = base
	ingest(t, s, message(t, 2, 0, 0, 0, 1))
	r := report(t, s)
	if r.Receipts[0].Sequence != 1 || r.Receipts[1].Sequence != 2 || r.Devices[device].Observed != nil {
		t.Fatal(r.Receipts)
	}
}

func TestEvidenceTimingClassificationSurvivesPolicyChanges(t *testing.T) {
	s, now, _ := open(t)
	*now = base
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	*now = base.Add(90 * time.Second)
	ingest(t, s, message(t, 2, 10, 0, 0, 1))
	p := DefaultPolicy()
	p.Clock = 1000
	if e := s.SetPolicy(p); e != nil {
		t.Fatal(e)
	}
	r := report(t, s)
	if r.Receipts[1].Class != "delayed_backlog" || r.Receipts[1].ClockTolerance != 5 || verdict(r, "reconnect_current_first") != "FAIL" {
		t.Fatal(r)
	}
}

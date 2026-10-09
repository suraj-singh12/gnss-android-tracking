package core

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"github.com/suraj-singh12/gnss-android-tracking/command/web"
	"io"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

func emergency(t *testing.T, n int, sec float64) Message {
	m := message(t, n, sec, 0, 0, 4)
	m.Type = "sos"
	m.SOS = &SOS{m.ID, m.Captured}
	return m
}
func TestSOSDurableReceiptAcknowledgementAndRecordingIndependence(t *testing.T) {
	s, now, path := open(t)
	m := emergency(t, 1, 0)
	m.Fix = nil
	m.Health.GNSS = "no_fix"
	first := ingest(t, s, m)
	initial := snapshot(t, s).Alerts
	if len(initial) != 1 || initial[0].Acknowledged != nil || initial[0].Fix != nil || initial[0].Freshness != "missing" {
		t.Fatal(initial)
	}
	*now = base.Add(time.Minute)
	if err := s.AcknowledgeSOS(m.Device, m.ID); err != nil {
		t.Fatal(err)
	}
	acknowledged := snapshot(t, s).Alerts[0]
	if acknowledged.Acknowledged == nil || *acknowledged.Acknowledged != now.Format(wireTime) || acknowledged.Received != first.Received {
		t.Fatal(acknowledged)
	}
	*now = base.Add(2 * time.Minute)
	if err := s.AcknowledgeSOS(m.Device, m.ID); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(acknowledged, snapshot(t, s).Alerts[0]) {
		t.Fatal("repeated acknowledgement changed state")
	}
	duplicate := ingest(t, s, m)
	if duplicate.Result != "duplicate" || duplicate.Received != first.Received || !reflect.DeepEqual(acknowledged, snapshot(t, s).Alerts[0]) {
		t.Fatal(duplicate)
	}
	for _, a := range []string{"start", "stop", "resume", "clear"} {
		action(t, s, a)
		if !reflect.DeepEqual(acknowledged, snapshot(t, s).Alerts[0]) {
			t.Fatal(a)
		}
	}
	second := emergency(t, 2, 10)
	ingest(t, s, second)
	if len(snapshot(t, s).Alerts) != 2 || snapshot(t, s).Alerts[1].Acknowledged != nil {
		t.Fatal("lost independent emergency")
	}
	s.Close()
	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	if !reflect.DeepEqual(acknowledged, snapshot(t, reopened).Alerts[0]) {
		t.Fatal("SOS not restored")
	}
	if a := ingest(t, reopened, m); a.Result != "duplicate" || a.Received != first.Received {
		t.Fatal(a)
	}
	report, err := reopened.FieldReport()
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"SOS retries deduplicated", "Operator ACK persisted", "SOS survived restart"} {
		found := false
		for _, v := range report.Verdicts {
			if v.Scenario == name && v.Result == "PASS" {
				found = true
			}
		}
		if !found {
			t.Fatal("missing proof", name, report.Verdicts)
		}
	}
}
func TestSOSConflictStorageFailureAndSpecificAcknowledgement(t *testing.T) {
	s, _, _ := open(t)
	m := emergency(t, 1, 0)
	_, err := s.db.Exec("CREATE TRIGGER fail_sos BEFORE UPDATE ON state BEGIN SELECT RAISE(ABORT,'full'); END")
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest("POST", "/api/v1/messages", bytes.NewReader(encode(t, m)))
	request.Header.Set("Content-Type", "application/json")
	out := httptest.NewRecorder()
	s.IngestHandler().ServeHTTP(out, request)
	if out.Code != 503 || len(snapshot(t, s).Alerts) != 0 {
		t.Fatal(out.Code)
	}
	var count int
	s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&count)
	if count != 0 {
		t.Fatal("partial save")
	}
	s.db.Exec("DROP TRIGGER fail_sos")
	ingest(t, s, m)
	changed := m
	changed.Party.Name = "conflict"
	if _, err = s.Ingest(encode(t, changed)); err != ErrConflict {
		t.Fatal(err)
	}
	if err = s.AcknowledgeSOS("22222222-2222-4222-8222-222222222222", m.ID); err == nil {
		t.Fatal("ack wrong device")
	}
	if err = s.AcknowledgeSOS(m.Device, "00000000-0000-4000-8000-999999999999"); err == nil {
		t.Fatal("ack wrong event")
	}
	other := m
	other.Device = "22222222-2222-4222-8222-222222222222"
	ingest(t, s, other)
	if err = s.AcknowledgeSOS(m.Device, m.ID); err != nil {
		t.Fatal(err)
	}
	alerts := snapshot(t, s).Alerts
	if len(alerts) != 2 || alerts[0].Acknowledged == nil || alerts[1].Acknowledged != nil {
		t.Fatal(alerts)
	}
}
func TestSOSLocalHTTPProjectionAndControlSecurity(t *testing.T) {
	s, _, _ := open(t)
	m := emergency(t, 1, 0)
	m.Fix.Age = 60000
	m.Health.GNSS = "unknown"
	ingest(t, s, m)
	if snapshot(t, s).Alerts[0].Freshness != "last_known" {
		t.Fatal("stale mislabeled")
	}
	local := s.LocalHandler(web.Handler())
	body := encode(t, map[string]string{"device_id": m.Device, "event_id": m.ID})
	for _, origin := range []string{"https://evil.example", ""} {
		req := httptest.NewRequest("POST", "/local/sos/acknowledge", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Origin", origin)
		out := httptest.NewRecorder()
		local.ServeHTTP(out, req)
		if origin != "" && out.Code != 403 {
			t.Fatal(out.Code)
		}
		if origin == "" && out.Code != 200 {
			t.Fatal(out.Code, out.Body)
		}
	}
	out := httptest.NewRecorder()
	local.ServeHTTP(out, httptest.NewRequest("GET", "/local/state", nil))
	var view map[string]any
	if err := json.Unmarshal(out.Body.Bytes(), &view); err != nil {
		t.Fatal(err)
	}
	if len(view["sos_alerts"].([]any)) != 1 {
		t.Fatal(view)
	}
	out = httptest.NewRecorder()
	s.IngestHandler().ServeHTTP(out, httptest.NewRequest("POST", "/local/sos/acknowledge", bytes.NewReader(body)))
	if out.Code != 404 {
		t.Fatal("controls exposed on phone listener")
	}
}
func TestSOSConcurrentAcknowledgementAndDuplicate(t *testing.T) {
	s, _, _ := open(t)
	m := emergency(t, 1, 0)
	ingest(t, s, m)
	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := s.AcknowledgeSOS(m.Device, m.ID); err != nil {
				t.Error(err)
			}
			if _, err := s.Ingest(encode(t, m)); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if len(snapshot(t, s).Alerts) != 1 || snapshot(t, s).Alerts[0].Acknowledged == nil {
		t.Fatal("duplicate emergency")
	}
	var count int
	s.db.QueryRow("SELECT COUNT(*) FROM field_evidence WHERE data LIKE '%sos_operator_acknowledged%'").Scan(&count)
	if count != 1 {
		t.Fatal("non-idempotent evidence", count)
	}
}
func TestSOSExportPrivacyAndInconclusiveBeforeRestart(t *testing.T) {
	s, _, _ := open(t)
	m := emergency(t, 1, 0)
	m.Party = Party{"sensitive-party", "private-team"}
	ingest(t, s, m)
	s.AcknowledgeSOS(m.Device, m.ID)
	if err := s.Action("start"); err != nil {
		t.Fatal(err)
	}
	if err := s.Action("clear"); err != nil {
		t.Fatal(err)
	}
	report, err := s.FieldReport()
	if err != nil {
		t.Fatal(err)
	}
	for _, v := range report.Verdicts {
		if v.Scenario == "Operator ACK persisted" && v.Result != "INCONCLUSIVE" {
			t.Fatal("fabricated restart proof")
		}
	}
	var out bytes.Buffer
	if err = s.ExportFieldReport(&out); err != nil {
		t.Fatal(err)
	}
	z, err := zip.NewReader(bytes.NewReader(out.Bytes()), int64(out.Len()))
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range z.File {
		r, _ := f.Open()
		b, _ := io.ReadAll(r)
		r.Close()
		for _, sensitive := range []string{m.ID, m.Device, "sensitive-party", "private-team", "latitude", "longitude", "horizontal_accuracy_m"} {
			if strings.Contains(string(b), sensitive) {
				t.Fatal("privacy leak", f.Name, sensitive)
			}
		}
	}
}
func TestSOSLegacyProjectionUpgradeAndTrackingContinues(t *testing.T) {
	s, _, path := open(t)
	m := emergency(t, 1, 0)
	ingest(t, s, m)
	var state string
	s.db.QueryRow("SELECT data FROM state WHERE id=1").Scan(&state)
	var old map[string]any
	json.Unmarshal([]byte(state), &old)
	delete(old, "sos_alerts")
	b, _ := json.Marshal(old)
	s.db.Exec("UPDATE state SET data=? WHERE id=1", string(b))
	s.Close()
	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	if len(snapshot(t, reopened).Alerts) != 1 {
		t.Fatal("legacy SOS lost")
	}
	reopened.Now = func() time.Time { return base.Add(-time.Second) }
	action(t, reopened, "start")
	reopened.Now = func() time.Time { return base.Add(time.Minute) }
	ingest(t, reopened, message(t, 2, 1, 0, 0, 1))
	ingest(t, reopened, message(t, 3, 11, 10, 0, 1))
	if len(snapshot(t, reopened).Points) != 2 || len(snapshot(t, reopened).Alerts) != 1 {
		t.Fatalf("tracking regression: %#v decisions %#v", snapshot(t, reopened).Points, snapshot(t, reopened).Decisions)
	}
}

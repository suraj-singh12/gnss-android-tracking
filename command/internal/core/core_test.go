package core

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"runtime"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/suraj-singh12/gnss-android-tracking/command/web"
)

var base = instant("2026-10-06T12:00:00.000Z")

const device = "11111111-1111-4111-8111-111111111111"

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("../../../protocol/fixtures", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}
func message(t *testing.T, n int, sec, x, y, accuracy float64) Message {
	t.Helper()
	m, err := Parse(fixture(t, "location-normal.json"))
	if err != nil {
		t.Fatal(err)
	}
	m.Sequence = int64(n)
	m.ID = fmt.Sprintf("00000000-0000-4000-8000-%012d", n)
	m.Captured = base.Add(time.Duration(sec * float64(time.Second))).Format(wireTime)
	f := *m.Fix
	f.Observed = m.Captured
	f.Lat = y / Radius / radians
	f.Lon = x / Radius / radians
	f.Accuracy = &accuracy
	m.Fix = &f
	return m
}
func raw(m Message) Candidate { return Candidate{m, base.Add(24 * time.Hour).Format(wireTime)} }
func encode(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
func ingest(t *testing.T, s *Store, m Message) Ack {
	t.Helper()
	a, err := s.Ingest(encode(t, m))
	if err != nil {
		t.Fatal(err)
	}
	return a
}
func open(t *testing.T) (*Store, *time.Time, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "command.sqlite")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	now := base.Add(-time.Second)
	s.Now = func() time.Time { return now }
	t.Cleanup(func() { s.Close() })
	return s, &now, path
}
func snapshot(t *testing.T, s *Store) State {
	t.Helper()
	s.projectionMu.Lock()
	if e := s.processProjectionLocked(); e != nil {
		s.recordProjectionFailure(e)
	}
	s.projectionMu.Unlock()
	st, err := s.Snapshot(30)
	if err != nil {
		t.Fatal(err)
	}
	return st
}
func action(t *testing.T, s *Store, a string) {
	t.Helper()
	if err := s.Action(a); err != nil {
		t.Fatal(err)
	}
}
func TestGoldenParsingAndValidation(t *testing.T) {
	paths, _ := filepath.Glob("../../../protocol/fixtures/*.json")
	for _, p := range paths {
		if strings.HasPrefix(filepath.Base(p), "ack-") || strings.HasPrefix(filepath.Base(p), "scenario-") {
			continue
		}
		t.Run(filepath.Base(p), func(t *testing.T) {
			if _, err := Parse(fixture(t, filepath.Base(p))); err != nil {
				t.Fatal(err)
			}
		})
	}
	for name, mutate := range map[string]func(map[string]any){"missing nullable": func(m map[string]any) { delete(object(m["fix"]), "speed_mps") }, "fractional sequence": func(m map[string]any) { m["sequence"] = 1.0 }, "invalid date": func(m map[string]any) { m["captured_at"] = "2026-02-30T12:00:00.000Z" }, "bad latitude": func(m map[string]any) { object(m["fix"])["latitude"] = 91 }, "interval precedence": func(m map[string]any) { object(m["config_state"])["effective_reporting_interval_s"] = 20 }, "bearing": func(m map[string]any) { object(m["fix"])["bearing_deg"] = 360 }, "missing health": func(m map[string]any) { delete(object(m["health"]), "charging") }, "unexpected sos": func(m map[string]any) { m["sos"] = nil }} {
		t.Run(name, func(t *testing.T) {
			m, err := decode(fixture(t, "location-normal.json"))
			if err != nil {
				t.Fatal(err)
			}
			mutate(m)
			b := encode(t, m)
			if name == "fractional sequence" {
				b = bytes.Replace(b, []byte(`"sequence":1`), []byte(`"sequence":1.0`), 1)
			}
			if _, err := Parse(b); err == nil {
				t.Fatal("accepted malformed message")
			}
		})
	}
	for _, b := range []string{`{"type":"location","type":"status"}`, `{"nested":{"x":1,"x":2}}`, `[]`, `{} {}`} {
		if _, err := decode([]byte(b)); err == nil {
			t.Fatal("accepted", b)
		}
	}
}
func TestDurableDedupeConfigAndRestart(t *testing.T) {
	s, now, path := open(t)
	*now = base.Add(time.Minute)
	m := message(t, 1, 0, 0, 0, 1)
	first := ingest(t, s, m)
	if first.Result != "stored" {
		t.Fatal(first)
	}
	v := 30
	if err := s.SetOverride(device, &v); err != nil {
		t.Fatal(err)
	}
	action(t, s, "start")
	authority := snapshot(t, s).Authority
	s.Close()
	restarted, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	restarted.Now = func() time.Time { return base.Add(time.Hour) }
	retry := ingest(t, restarted, m)
	if retry.Result != "duplicate" || retry.Received != first.Received || retry.Config.Version != 1 || *retry.Config.Override != 30 || *retry.Config.Authority != authority {
		t.Fatalf("restart ACK: %+v", retry)
	}
	st := snapshot(t, restarted)
	if len(st.Devices) != 1 || st.Recording == nil || st.Authority != authority {
		t.Fatal("durability")
	}
	additive, err := decode(encode(t, m))
	if err != nil {
		t.Fatal(err)
	}
	additive["future"] = true
	object(additive["fix"])["future"] = true
	object(additive["party"])["future"] = true
	if a, err := restarted.Ingest(encode(t, additive)); err != nil || a.Result != "duplicate" {
		t.Fatal("additive equality", a, err)
	}
	for _, change := range []func(*Message){func(m *Message) { m.Party.Name = "changed" }, func(m *Message) { m.ID = id() }, func(m *Message) { m.Sequence++ }} {
		copy := m
		change(&copy)
		if _, err := restarted.Ingest(encode(t, copy)); !errors.Is(err, ErrConflict) {
			t.Fatal("missing conflict", err)
		}
	}
	if err := restarted.SetOverride(device, nil); err != nil {
		t.Fatal(err)
	}
	a := ingest(t, restarted, m)
	if a.Config.Version != 2 || a.Config.Override != nil {
		t.Fatal("clear config", a)
	}
	echo := message(t, 2, 80, 0, 0, 1)
	echo.Config.Config = a.Config
	echo.Config.Local = 20
	echo.Config.Effective = 20
	ingest(t, restarted, echo)
	if !snapshot(t, restarted).Devices[device].Converged {
		t.Fatal("config convergence")
	}
	ingest(t, restarted, m)
	if !snapshot(t, restarted).Devices[device].Converged {
		t.Fatal("backlog config rollback")
	}
	var count int
	if err := restarted.db.QueryRow("SELECT count(*) FROM raw").Scan(&count); err != nil || count != 2 {
		t.Fatal(count, err)
	}
}
func TestLiveOrderingDynamicAndCadence(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Minute)
	newest := message(t, 3, 50, 30, 0, 1)
	ingest(t, s, newest)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	status := message(t, 4, 55, 0, 0, 1)
	status.Type = "status"
	status.Fix = nil
	status.Health.GNSS = "no_fix"
	status.Config.Local = 60
	status.Config.Effective = 60
	ingest(t, s, status)
	d := snapshot(t, s).Devices[device]
	if d.Location.ID != newest.ID || d.Snapshot.ID != status.ID || d.GNSSCondition != "no_fix" {
		t.Fatal("live ordering", d)
	}
	*now = base.Add(180 * time.Second)
	if snapshot(t, s).Devices[device].ContactCondition != "healthy" {
		t.Fatal("cadence health")
	}
	*now = base.Add(190 * time.Second)
	if snapshot(t, s).Devices[device].ContactCondition != "delayed" {
		t.Fatal("cadence delayed")
	}
	*now = base.Add(320 * time.Second)
	if snapshot(t, s).Devices[device].ContactCondition != "contact_lost" {
		t.Fatal("cadence lost")
	}
	for i := 2; i <= 6; i++ {
		m := message(t, 1, 0, 0, 0, 1)
		m.Device = fmt.Sprintf("%08d-1111-4111-8111-111111111111", i)
		ingest(t, s, m)
	}
	if len(snapshot(t, s).Devices) != 6 {
		t.Fatal("dynamic devices")
	}
}
func TestTrackQualityAndGeometry(t *testing.T) {
	p := DefaultPolicy()
	cases := []struct {
		name     string
		xy       [][2]float64
		accuracy float64
		want     int
	}{
		{"stationary jitter", [][2]float64{{0, 0}, {1, 0}, {-1, 0}, {0, 1}}, 3, 1},
		{"2m floors and accepted anchor", [][2]float64{{0, 0}, {1.5, 0}, {3, 0}}, 0.1, 2},
		{"uncertainty raises floor", [][2]float64{{0, 0}, {3, 0}, {8, 0}}, 4, 2},
		{"curve", [][2]float64{{0, 0}, {10, 0}, {20, 5}, {25, 15}, {20, 25}}, 1, 5},
		{"90 degree turn", [][2]float64{{0, 0}, {10, 0}, {20, 0}, {20, 10}}, 1, 4},
		{"sideways", [][2]float64{{0, 0}, {10, 0}, {20, 0}, {20, -10}}, 1, 4},
		{"genuine reverse", [][2]float64{{0, 0}, {10, 0}, {20, 0}, {10, 0}, {0, 0}}, 1, 5},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			raws := []Candidate{}
			for i, xy := range c.xy {
				raws = append(raws, raw(message(t, i+1, float64(i*10), xy[0], xy[1], c.accuracy)))
			}
			points, _ := Derive(raws, p, "window", 0)
			if len(points) != c.want {
				t.Fatalf("got %d want %d %+v", len(points), c.want, points)
			}
		})
	}
	for name, mutate := range map[string]func(*Message){"poor_accuracy": func(m *Message) { a := 80.0; m.Fix.Accuracy = &a }, "unknown_accuracy": func(m *Message) { m.Fix.Accuracy = nil }, "stale_at_capture": func(m *Message) { m.Fix.Age = 40000 }, "clock_anomaly": func(m *Message) { m.Fix.Observed = base.Add(-time.Hour).Format(wireTime) }} {
		t.Run(name, func(t *testing.T) {
			m := message(t, 1, 0, 0, 0, 1)
			mutate(&m)
			if got := Quality(m, p); got != name {
				t.Fatal(got)
			}
			points, decisions := Derive([]Candidate{raw(m)}, p, "w", 0)
			if len(points) != 0 || len(decisions) != 1 {
				t.Fatal(points, decisions)
			}
		})
	}
	t.Run("jump recovery has no bridge", func(t *testing.T) {
		c := []Candidate{raw(message(t, 1, 0, 0, 0, 1)), raw(message(t, 2, 10, 10, 0, 1)), raw(message(t, 3, 20, 10000, 0, 1)), raw(message(t, 4, 30, 20, 0, 1)), raw(message(t, 5, 40, 30, 0, 1))}
		points, decisions := Derive(c, p, "w", 0)
		if len(points) != 4 || decisions[2].Reason != "implausible_speed" || math.Abs(points[3].Distance-20) > 1e-6 || points[1].Segment == points[2].Segment {
			t.Fatal(points, decisions)
		}
	})
	t.Run("quality and time gaps", func(t *testing.T) {
		bad := message(t, 3, 20, 15, 0, 80)
		c := []Candidate{raw(message(t, 1, 0, 0, 0, 1)), raw(message(t, 2, 10, 10, 0, 1)), raw(bad), raw(message(t, 4, 30, 20, 0, 1)), raw(message(t, 5, 200, 30, 0, 1))}
		points, _ := Derive(c, p, "w", 0)
		if len(points) != 4 || points[2].Reason != "poor_accuracy" || points[3].Reason != "time_gap" || math.Abs(points[3].Distance-10) > 1e-6 {
			t.Fatal(points)
		}
	})
	t.Run("equal instants use lowest sequence", func(t *testing.T) {
		c := []Candidate{raw(message(t, 3, 10, 20, 0, 1)), raw(message(t, 2, 10, 10, 0, 1)), raw(message(t, 1, 0, 0, 0, 1))}
		points, decisions := Derive(c, p, "w", 0)
		if len(points) != 2 || points[1].Sequence != 2 || decisions[2].Reason != "same_observation_time" {
			t.Fatal(points, decisions)
		}
	})
	t.Run("direction uses backward floor only for reverse", func(t *testing.T) {
		p.Backward = 12
		c := []Candidate{}
		for i, x := range []float64{0, 20, 40, 35, 25} {
			c = append(c, raw(message(t, i+1, float64(i*10), x, 0, 0.1)))
		}
		points, _ := Derive(c, p, "w", 0)
		if len(points) != 4 || points[3].Fix.Lon >= points[2].Fix.Lon {
			t.Fatal(points)
		}
	})
}
func TestRecordingPermutationsRestartAndProjection(t *testing.T) {
	orders := [][]int{{0, 1, 2, 3}, {3, 2, 1, 0}, {1, 3, 0, 2}, {2, 0, 3, 1}}
	var expected []Point
	var distance float64
	for i, order := range orders {
		s, now, path := open(t)
		action(t, s, "start")
		*now = base.Add(100 * time.Second)
		messages := []Message{message(t, 1, 0, 0, 0, 1), message(t, 2, 10, 10, 0, 1), message(t, 3, 20, 10, 10, 1), message(t, 4, 30, 0, 10, 1)}
		for _, j := range order {
			ingest(t, s, messages[j])
		}
		st := snapshot(t, s)
		if len(st.Points) != 4 {
			t.Fatal(st.Points)
		}
		for j := range st.Points {
			st.Points[j].Segment = ""
			st.Points[j].Window = ""
		}
		if i == 0 {
			expected = st.Points
			distance = st.Devices[device].Total
		} else if !reflect.DeepEqual(expected, st.Points) || distance != st.Devices[device].Total {
			t.Fatal("arrival-dependent derivation", st.Points)
		}
		before := snapshot(t, s)
		ingest(t, s, messages[0])
		after := snapshot(t, s)
		if !reflect.DeepEqual(before.Points, after.Points) {
			t.Fatal("duplicate geometry")
		}
		s.Close()
		restart, err := Open(path)
		if err != nil {
			t.Fatal(err)
		}
		rs := snapshot(t, restart)
		if !reflect.DeepEqual(after.Points, rs.Points) {
			t.Fatal("restart track")
		}
		restart.Close()
	}
}
func TestRecordingWindowsPolicyClear(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	*now = base.Add(time.Minute)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	ingest(t, s, message(t, 2, 10, 10, 0, 1))
	late := message(t, 1, 5, 0, 10, 1)
	late.Device = "22222222-2222-4222-8222-222222222222"
	ingest(t, s, late)
	action(t, s, "stop")
	*now = base.Add(120 * time.Second)
	ingest(t, s, message(t, 3, 20, 20, 0, 1))
	ingest(t, s, message(t, 4, 60, 999, 0, 1))
	if math.Abs(snapshot(t, s).Devices[device].Total-20) > 1e-6 {
		t.Fatal("closed-window backlog")
	}
	action(t, s, "resume")
	*now = base.Add(200 * time.Second)
	ingest(t, s, message(t, 5, 120, 500, 0, 1))
	ingest(t, s, message(t, 6, 130, 510, 0, 1))
	if math.Abs(snapshot(t, s).Devices[device].Total-30) > 1e-6 {
		t.Fatal("resume connector")
	}
	p := DefaultPolicy()
	p.Forward = 5
	*now = base.Add(140 * time.Second)
	if err := s.SetPolicy(p); err != nil {
		t.Fatal(err)
	}
	*now = base.Add(200 * time.Second)
	ingest(t, s, message(t, 7, 140, 520, 0, 1))
	ingest(t, s, message(t, 8, 150, 523, 0, 1))
	st := snapshot(t, s)
	if len(st.Recording.Windows) != 2 || st.Recording.Windows[0].Policy.Forward != 5 || st.Recording.Windows[1].Policy.Forward != 5 || st.Recording.Windows[1].Reason != "resume" || math.Abs(st.Devices[device].Total-40) > 1e-6 {
		t.Fatal("policy history", st)
	}
	action(t, s, "clear")
	ingest(t, s, message(t, 9, 25, 25, 0, 1))
	if st = snapshot(t, s); st.Recording != nil || len(st.Points) != 0 || len(st.Devices) != 2 {
		t.Fatal("resurrected clear")
	}
	var count int
	if err := s.db.QueryRow("SELECT count(*) FROM raw").Scan(&count); err != nil || count != 10 {
		t.Fatal("raw retention", count, err)
	}
	action(t, s, "start")
	ingest(t, s, message(t, 10, 30, 30, 0, 1))
	if len(snapshot(t, s).Points) != 0 {
		t.Fatal("old raw in new recording")
	}
}
func TestDistanceProjectionAndPresentation(t *testing.T) {
	a := Fix{Lat: 0, Lon: 0}
	b := Fix{Lat: 0, Lon: 1}
	if math.Abs(Distance(a, b)-111195.0802335329) > 1e-7 {
		t.Fatal(Distance(a, b))
	}
	alt := 10000.0
	b.Altitude = &alt
	if math.Abs(Distance(a, b)-111195.0802335329) > 1e-7 {
		t.Fatal("altitude distance")
	}
	if math.Abs(Distance(Fix{Lon: 179.9}, Fix{Lon: -179.9})-22239.0160467) > .001 {
		t.Fatal("antimeridian")
	}
	x, y := Project(Fix{Lat: 1, Lon: 1}, a)
	if x != y || x <= 0 {
		t.Fatal("metric scale/north", x, y)
	}
	points := []Point{}
	for i := 0; i < 7; i++ {
		m := message(t, i+1, float64(i*10), float64(i*10), 0, 1)
		points = append(points, Point{Device: m.Device, Message: m.ID, Sequence: m.Sequence, Fix: *m.Fix, Segment: "w", Distance: float64(i * 10)})
	}
	copy := append([]Point{}, points...)
	dots := Present(points, 30)
	visible := []int64{}
	for _, p := range dots {
		if p.Dot {
			visible = append(visible, p.Sequence)
		}
	}
	if !reflect.DeepEqual(visible, []int64{1, 4, 7}) || !reflect.DeepEqual(points, copy) {
		t.Fatal("marker mutation", visible)
	}
	dense := Present(points, 10)
	for i := range dense {
		if dense[i].Distance != dots[i].Distance || dense[i].Fix != dots[i].Fix {
			t.Fatal("presentation changed geometry")
		}
	}
	late := Point{Device: device, Fix: *message(t, 20, -10, -10, 0, 1).Fix, Segment: "w"}
	shifted := Present(append(points, late), 30)
	if shifted[0].X <= 0 || shifted[len(shifted)-1].X != 0 {
		t.Fatal("origin recalculation")
	}
	// Sparse buckets show each actual point once, without interpolation.
	sparse := Present([]Point{points[0], points[6]}, 20)
	if len(sparse) != 2 || !sparse[1].Dot {
		t.Fatal(sparse)
	}
}
func TestHTTPAPIAndDurableFailure(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Minute)
	handler := s.IngestHandler()
	cases := []struct {
		method, path, body, media string
		status                    int
		code                      string
	}{{"GET", "/api/v1/messages", "", "application/json", 405, "method_not_allowed"}, {"POST", "/api/v2/messages", "{}", "application/json", 404, "unsupported_endpoint"}, {"POST", "/api/v1/messages", "{}", "text/plain", 415, "unsupported_media_type"}, {"POST", "/api/v1/messages", `{"protocol_version":2}`, "application/json", 426, "unsupported_protocol"}, {"POST", "/api/v1/messages", "{}", "application/json", 400, "invalid_message"}, {"POST", "/api/v1/messages", strings.Repeat("x", 65537), "application/json", 413, "message_too_large"}}
	for _, c := range cases {
		r := httptest.NewRequest(c.method, c.path, strings.NewReader(c.body))
		r.Header.Set("Content-Type", c.media)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != c.status || !strings.Contains(w.Body.String(), c.code) {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	local := s.LocalHandler(web.Handler())
	request := func(path string, body any) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", path, bytes.NewReader(encode(t, body)))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		local.ServeHTTP(w, r)
		return w
	}
	if w := request("/local/recording", map[string]any{"action": "start"}); w.Code != 200 {
		t.Fatal(w.Body)
	}
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	if w := request("/local/recording", map[string]any{"action": "clear"}); w.Code != 400 {
		t.Fatal("unconfirmed clear")
	}
	for _, path := range []string{"/", "/app.js", "/style.css", "/local/state?dot_interval_s=20"} {
		w := httptest.NewRecorder()
		local.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		if w.Code != 200 {
			t.Fatal(path, w.Code)
		}
	}
	if w := request("/local/override", map[string]any{"device_id": device, "reporting_interval_override_s": 6}); w.Code != 400 {
		t.Fatal("invalid interval")
	}
	w := httptest.NewRecorder()
	local.ServeHTTP(w, httptest.NewRequest("GET", "/local/state?dot_interval_s=15", nil))
	if w.Code != 400 {
		t.Fatal("invalid dots")
	}
	r := httptest.NewRequest("POST", "/local/recording", strings.NewReader(`{"action":"clear","confirmed":true}`))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Origin", "https://evil.test")
	w = httptest.NewRecorder()
	local.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal("remote controls")
	}
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, httptest.NewRequest("GET", "/local/state", nil))
	if w.Code != 404 {
		t.Fatal("controls exposed on LAN")
	}
	s.Close()
	r = httptest.NewRequest("POST", "/api/v1/messages", bytes.NewReader(fixture(t, "location-normal.json")))
	r.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != 503 || strings.Contains(w.Body.String(), `"result":"stored"`) {
		t.Fatal("ACK before persistence", w.Code, w.Body)
	}
}
func TestConcurrentRetries(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Minute)
	b := fixture(t, "location-normal.json")
	var wg sync.WaitGroup
	results := make(chan Ack, 12)
	errs := make(chan error, 12)
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			a, err := s.Ingest(b)
			if err != nil {
				errs <- err
			} else {
				results <- a
			}
		}()
	}
	wg.Wait()
	close(results)
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	stored := 0
	first := ""
	for a := range results {
		if a.Result == "stored" {
			stored++
		}
		if first == "" {
			first = a.Received
		}
		if a.Received != first {
			t.Fatal("first receipt")
		}
	}
	if stored != 1 {
		t.Fatal(stored)
	}
}
func TestSimulatorIntegration(t *testing.T) {
	for _, name := range []string{"scenario-reconnect.json", "scenario-dynamic-sos.json", "scenario-config.json"} {
		t.Run(name, func(t *testing.T) {
			s, now, _ := open(t)
			action(t, s, "start")
			*now = base.Add(time.Hour)
			receiver := httptest.NewServer(s.IngestHandler())
			defer receiver.Close()
			local := httptest.NewServer(s.LocalHandler(web.Handler()))
			defer local.Close()
			args := []string{"run", "./phone-simulator", "-url", receiver.URL, "-scenario", "../protocol/fixtures/" + name, "-retry-delay", "1ms"}
			if name == "scenario-config.json" {
				args = append(args, "-config-controls", local.URL)
			}
			cmd := exec.Command(filepath.Join(runtime.GOROOT(), "bin", "go"), args...)
			cmd.Dir = "../../../test-tools"
			b, err := cmd.CombinedOutput()
			if err != nil {
				t.Fatalf("simulator: %v\n%s", err, b)
			}
			st := snapshot(t, s)
			if name == "scenario-reconnect.json" {
				if len(st.Points) != 3 {
					t.Fatal(st.Points)
				}
				seq := []int64{}
				for _, p := range st.Points {
					seq = append(seq, p.Sequence)
				}
				if !sort.SliceIsSorted(seq, func(i, j int) bool { return seq[i] < seq[j] }) {
					t.Fatal(seq)
				}
			}
			if name == "scenario-dynamic-sos.json" && len(st.Devices) != 2 {
				t.Fatal("simulator dynamic")
			}
			if name == "scenario-config.json" && !st.Devices[device].Converged {
				t.Fatal("simulator config")
			}
		})
	}
}

func TestTransactionRollbackBeforeACK(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Minute)
	if _, err := s.db.Exec(`CREATE TRIGGER fail_state BEFORE UPDATE ON state BEGIN SELECT RAISE(ABORT,'simulated disk write failure'); END`); err != nil {
		t.Fatal(err)
	}
	a, err := s.Ingest(fixture(t, "location-normal.json"))
	if err == nil || a.Result != "" {
		t.Fatal("successful ACK despite failed transaction", a, err)
	}
	var count int
	if err := s.db.QueryRow("SELECT count(*) FROM raw").Scan(&count); err != nil || count != 0 {
		t.Fatal("partial commit", count, err)
	}
	if _, err := s.db.Exec("DROP TRIGGER fail_state"); err != nil {
		t.Fatal(err)
	}
	if a := ingest(t, s, message(t, 1, 0, 0, 0, 1)); a.Result != "stored" {
		t.Fatal("failed receipt deduped")
	}
}
func TestRestartStoppedClearAndRemoteOverride(t *testing.T) {
	s, now, path := open(t)
	action(t, s, "start")
	*now = base.Add(time.Minute)
	m := message(t, 1, 0, 0, 0, 1)
	ingest(t, s, m)
	ingest(t, s, message(t, 2, 10, 10, 0, 1))
	v := 30
	if err := s.SetOverride(device, &v); err != nil {
		t.Fatal(err)
	}
	action(t, s, "stop")
	before := snapshot(t, s)
	s.Close()
	r, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	r.Now = func() time.Time { return base.Add(120 * time.Second) }
	st := snapshot(t, r)
	if st.Recording.Active || st.Recording.ID != before.Recording.ID || !reflect.DeepEqual(st.Points, before.Points) {
		t.Fatal("stopped restart")
	}
	ingest(t, r, message(t, 3, 20, 20, 0, 1))
	if math.Abs(snapshot(t, r).Devices[device].Total-20) > 1e-6 {
		t.Fatal("late backlog after restart")
	}
	action(t, r, "resume")
	if len(snapshot(t, r).Recording.Windows) != 2 {
		t.Fatal("resume restart")
	}
	action(t, r, "clear")
	r.Close()
	r, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	r.Now = func() time.Time { return base.Add(180 * time.Second) }
	ingest(t, r, message(t, 4, 30, 30, 0, 1))
	st = snapshot(t, r)
	if st.Recording != nil || len(st.Points) != 0 || st.Devices[device].Desired.Version != 1 || *st.Devices[device].Desired.Override != 30 {
		t.Fatal("clear restart")
	}
	if err := r.SetOverride(device, nil); err != nil {
		t.Fatal(err)
	}
	r.Close()
	r, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	a := ingest(t, r, m)
	if a.Config.Version != 2 || a.Config.Override != nil {
		t.Fatal("clear override restart")
	}
}
func TestCanonicalNumericRetriesAndPersistedDecisions(t *testing.T) {
	s, now, _ := open(t)
	action(t, s, "start")
	*now = base.Add(time.Minute)
	m := message(t, 1, 0, 0, 0, 1)
	ingest(t, s, m)
	b := encode(t, m)
	b = bytes.Replace(b, []byte(`"latitude":0`), []byte(`"latitude":-0.0`), 1)
	if a, err := s.Ingest(b); err != nil || a.Result != "duplicate" {
		t.Fatal("negative zero logical retry", a, err)
	}
	bad := message(t, 2, 10, 10, 0, 80)
	ingest(t, s, bad)
	st := snapshot(t, s)
	if len(st.Decisions) != 2 || st.Decisions[1].Reason != "poor_accuracy" || st.Decisions[1].Revision != 1 {
		t.Fatal("persisted decision", st.Decisions)
	}
	if a, b := st.Devices[device].Color, st.Devices[device].Dash; a == "" || b != "" {
		t.Fatal("identity style")
	}
}

func TestQualitySettingsCannotDisableUncertainty(t *testing.T) {
	p := DefaultPolicy()
	p.Uncertainty = .1
	if err := p.Validate(); err == nil {
		t.Fatal("uncertainty disabled")
	}
	for _, v := range []float64{0, -1, math.NaN(), math.Inf(1)} {
		p = DefaultPolicy()
		p.Forward = v
		if err := p.Validate(); err == nil {
			t.Fatal("invalid floor", v)
		}
	}
}

func TestLiveJumpQualityWithoutRecording(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(20 * time.Second)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	ingest(t, s, message(t, 2, 10, 10000, 0, 1))
	d := snapshot(t, s).Devices[device]
	if d.GNSSCondition != "valid" || !d.CurrentPosition || d.Location.Sequence != 2 {
		t.Fatal("live source incorrectly gated by historical speed", d)
	}
	ingest(t, s, message(t, 3, 20, 20, 0, 1))
	if snapshot(t, s).Devices[device].GNSSCondition != "valid" {
		t.Fatal("credible recovery")
	}
}

func TestCaseVariantAdditiveFieldsCannotShadowKnownContent(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Minute)
	m := message(t, 1, 0, 0, 0, 1)
	ingest(t, s, m)
	v, err := decode(encode(t, m))
	if err != nil {
		t.Fatal(err)
	}
	v["DEVICE_ID"] = "not-a-device"
	v["Fix"] = nil
	object(v["party"])["Name"] = "shadow"
	object(v["fix"])["LATITUDE"] = 91
	object(v["health"])["GNSS_STATUS"] = "disabled"
	object(v["config_state"])["VERSION"] = 999
	parsed, err := Parse(encode(t, v))
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(parsed, m) {
		t.Fatal("unknown fields changed known content", parsed)
	}
	if a, err := s.Ingest(encode(t, v)); err != nil || a.Result != "duplicate" {
		t.Fatal("unknown field retry", a, err)
	}
}

func TestFiveSecondReportingContract(t *testing.T) {
	s, _, _ := open(t)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	for n, valid := range map[int]bool{5: true, 10: true, 15: true, 20: true, 30: true, 60: true, 86400: true, 0: false, 1: false, 6: false, 86405: false} {
		if got := interval(json.Number(fmt.Sprint(n))); got != valid {
			t.Fatalf("interval %d: %v", n, got)
		}
		err := s.SetOverride(device, &n)
		if (err == nil) != valid {
			t.Fatalf("override %d: %v", n, err)
		}
	}
}

package core

// Field evidence references the authoritative raw envelopes and derived state.
// No coordinates, Party metadata, network addresses or raw payloads are copied.
import (
	"archive/zip"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"runtime/debug"
	"sort"
	"time"
)

type Revision struct {
	Source   string `json:"source_revision"`
	Modified bool   `json:"source_modified"`
}

func BuildRevision() Revision {
	if b, ok := debug.ReadBuildInfo(); ok {
		return revisionFromSettings(b.Settings)
	}
	return Revision{Source: "unknown"}
}
func revisionFromSettings(settings []debug.BuildSetting) Revision {
	r := Revision{Source: "unknown"}
	{
		for _, v := range settings {
			if v.Key == "vcs.revision" {
				r.Source = v.Value
			}
			if v.Key == "vcs.modified" {
				r.Modified = v.Value == "true"
			}
		}
	}
	return r
}

type Evidence struct {
	FailureCode          string             `json:"failure_code,omitempty"`
	BatchEntryIndex      *int               `json:"batch_entry_index,omitempty"`
	ProjectionPending    bool               `json:"projection_pending"`
	ProjectionRawVersion int64              `json:"projection_raw_version,omitempty"`
	DeliveryRole         string             `json:"delivery_role,omitempty"`
	ReportReference      string             `json:"report_reference,omitempty"`
	FreshAtReceipt       *bool              `json:"fresh_gnss_at_receipt,omitempty"`
	SourceQuality        string             `json:"source_quality,omitempty"`
	TimingClass          string             `json:"receipt_timing_class,omitempty"`
	ClockTolerance       float64            `json:"receipt_clock_tolerance_s,omitempty"`
	UsefulBefore         int                `json:"useful_points_before"`
	UsefulAfter          int                `json:"useful_points_after"`
	DistanceBefore       float64            `json:"distance_before_m"`
	DistanceAfter        float64            `json:"distance_after_m"`
	ConflictingMessage   string             `json:"existing_conflicting_message_id,omitempty"`
	ConflictingSequence  int64              `json:"existing_conflicting_sequence,omitempty"`
	Ordinal              int64              `json:"ordinal"`
	At                   string             `json:"at"`
	Kind                 string             `json:"kind"`
	Device               string             `json:"device_id,omitempty"`
	Message              string             `json:"message_id,omitempty"`
	Sequence             int64              `json:"sequence,omitempty"`
	Captured             string             `json:"captured_at,omitempty"`
	Observed             string             `json:"observed_at,omitempty"`
	FirstReceived        string             `json:"first_received_at,omitempty"`
	Type                 string             `json:"message_type,omitempty"`
	GNSSStatus           string             `json:"gnss_status,omitempty"`
	FixAgeAtCapture      *int64             `json:"fix_age_at_capture_ms,omitempty"`
	FixPresent           bool               `json:"fix_present"`
	FreshAtCapture       bool               `json:"fresh_at_capture"`
	Effective            int                `json:"effective_reporting_interval_s,omitempty"`
	Reported             *ConfigState       `json:"reported_config,omitempty"`
	Offered              *Config            `json:"ack_config_offered,omitempty"`
	LiveBefore           string             `json:"live_observed_before,omitempty"`
	LiveAfter            string             `json:"live_observed_after,omitempty"`
	Revision             *Revision          `json:"revision,omitempty"`
	Before               *RecordingEvidence `json:"recording_before,omitempty"`
	After                *RecordingEvidence `json:"recording_after,omitempty"`
}
type PointEvidence struct {
	Device   string  `json:"device_id"`
	Message  string  `json:"message_id"`
	Observed string  `json:"observed_at"`
	Segment  string  `json:"segment_id"`
	Distance float64 `json:"cumulative_m"`
}
type WindowParticipation struct {
	Device        string  `json:"device_id"`
	Window        string  `json:"window_id"`
	First         string  `json:"first_accepted_observed_at"`
	Last          string  `json:"last_accepted_observed_at"`
	Count         int     `json:"accepted_points"`
	StartDistance float64 `json:"first_cumulative_m"`
	EndDistance   float64 `json:"last_cumulative_m"`
}
type RecordingEvidence struct {
	ProjectionPending bool                  `json:"projection_pending"`
	Participation     []WindowParticipation `json:"device_window_participation"`
	Recording         *Recording            `json:"recording"`
	Points            []PointEvidence       `json:"accepted_points"`
	Distances         map[string]float64    `json:"distance_m"`
}

func recordingEvidence(st State) *RecordingEvidence {
	// Marshal/unmarshal detaches mutable window pointers before the operation.
	b, _ := json.Marshal(st.Recording)
	var r *Recording
	_ = json.Unmarshal(b, &r)
	e := &RecordingEvidence{ProjectionPending: st.ProjectionPending, Recording: r, Points: []PointEvidence{}, Distances: map[string]float64{}}
	for _, p := range st.Points {
		e.Points = append(e.Points, PointEvidence{p.Device, p.Message, p.Fix.Observed, p.Segment, p.Distance})
	}
	for id, d := range st.Devices {
		e.Distances[id] = d.Total
	}
	if r != nil {
		for _, w := range r.Windows {
			ids := []string{}
			for id := range st.Devices {
				ids = append(ids, id)
			}
			sort.Strings(ids)
			for _, id := range ids {
				var part *WindowParticipation
				for _, point := range e.Points {
					t := instant(point.Observed)
					if point.Device == id && !t.Before(w.Start) && (w.Stop == nil || t.Before(*w.Stop)) {
						if part == nil {
							part = &WindowParticipation{Device: id, Window: w.ID, First: point.Observed, StartDistance: point.Distance}
						}
						part.Last = point.Observed
						part.EndDistance = point.Distance
						part.Count++
					}
				}
				if part != nil {
					e.Participation = append(e.Participation, *part)
				}
			}
		}
	}
	return e
}
func writeEvidence(tx *sql.Tx, e Evidence) error {
	b, err := json.Marshal(e)
	if err != nil {
		return err
	}
	_, err = tx.Exec("INSERT INTO field_evidence(data) VALUES(?)", string(b))
	return err
}
func trackEffect(st State, device string) (int, float64) {
	n := 0
	if st.Points == nil && st.Devices[device] != nil {
		n = st.Devices[device].UsefulPoints
	}
	for _, p := range st.Points {
		if p.Device == device {
			n++
		}
	}
	distance := 0.0
	if d := st.Devices[device]; d != nil {
		distance = d.Total
	}
	return n, distance
}
func liveObserved(d *Device) string {
	if d != nil && d.Location != nil && d.Location.Fix != nil {
		return d.Location.Fix.Observed
	}
	return ""
}
func receiptEvidence(m Message, at, first, result string, d *Device) Evidence {
	c := m.Config
	e := Evidence{ReportReference: fmt.Sprintf("%x", sha256.Sum256([]byte(m.ID))), GNSSStatus: m.Health.GNSS, At: at, Kind: result, Device: m.Device, Message: m.ID, Sequence: m.Sequence, Captured: m.Captured, FirstReceived: first, Type: m.Type, Effective: m.Config.Effective, Reported: &c, FixPresent: m.Fix != nil, LiveBefore: liveObserved(d)}
	if m.Fix != nil {
		age := m.Fix.Age
		e.FixAgeAtCapture = &age
		e.Observed = m.Fix.Observed
		e.FreshAtCapture = m.Fix.Age <= 30000
	}
	return e
}

// Freeze source validity at receipt. Later quality edits must not manufacture
// evidence that the original recovery supplied a usable current position.
func receiptQuality(m Message, at string, p Policy) (string, bool) {
	quality := Quality(m, p)
	if m.Fix == nil {
		return quality, false
	}
	age := instant(at).Sub(instant(m.Fix.Observed)).Seconds()
	fresh := m.Health.GNSS == "fix" && quality == "valid" && float64(m.Fix.Age)/1000 <= liveAgeLimit(p) && age >= -liveClockTolerance(p) && age <= liveAgeLimit(p) && instant(m.Captured).Sub(instant(at)).Seconds() <= liveClockTolerance(p)
	return quality, fresh
}

type Receipt struct {
	NonAdvancingHistory bool `json:"nonadvancing_history"`
	Evidence
	Class      string  `json:"receipt_class"`
	CaptureAge float64 `json:"capture_age_at_receipt_s"`
}
type Counters struct {
	DelayedLocations  int            `json:"delayed_location_reports"`
	DelayedRoutine    int            `json:"delayed_routine_reports"`
	LocationReports   int            `json:"location_reports"`
	RoutineReports    int            `json:"routine_reports"`
	SOSReports        int            `json:"sos_reports"`
	DelayedReports    int            `json:"delayed_reports"`
	LastReceipt       string         `json:"last_unique_receipt_at,omitempty"`
	ProjectionReasons map[string]int `json:"projection_reasons"`
	Reports           int            `json:"reports_received"`
	RawFixes          int            `json:"raw_fixes"`
	Useful            int            `json:"useful_points"`
	Duplicates        int            `json:"identical_duplicate_retries"`
	Conflicts         int            `json:"identity_conflicts"`
	Expected          int            `json:"expected_interval_s"`
	Observed          *float64       `json:"median_recent_unique_receipt_gap_s"`
}
type Gap struct {
	GapBeginsAfter string   `json:"gap_begins_after"`
	Recovered      bool     `json:"recovered"`
	Device         string   `json:"device_id"`
	LastReceipt    string   `json:"last_unique_receipt"`
	FirstReceipt   string   `json:"first_unique_receipt_after_gap"`
	Duration       float64  `json:"receipt_gap_s"`
	FirstMessage   string   `json:"first_message_id"`
	CaptureAge     float64  `json:"first_capture_age_s"`
	FirstCaptured  string   `json:"first_captured_at"`
	FirstObserved  string   `json:"first_observed_at,omitempty"`
	OlderFollowing []string `json:"older_backlog_message_ids"`
}
type Verdict struct {
	Scenario string `json:"scenario"`
	Device   string `json:"device_id,omitempty"`
	Result   string `json:"result"`
	Reason   string `json:"reason"`
	Evidence any    `json:"evidence,omitempty"`
}
type FieldReport struct {
	HistorySessions     map[string]map[string]*HistoryState `json:"history_sessions"`
	History             map[string]*HistoryState            `json:"history"`
	ProjectionPending   bool                                `json:"projection_pending"`
	ProjectionError     string                              `json:"projection_error,omitempty"`
	ProjectionDecisions []Decision                          `json:"projection_decisions"`
	Policy              Policy                              `json:"current_policy"`
	Schema              int                                 `json:"schema_version"`
	Generated           string                              `json:"generated_at"`
	Revision            Revision                            `json:"build"`
	Coverage            string                              `json:"coverage"`
	Devices             map[string]*Counters                `json:"devices"`
	Receipts            []Receipt                           `json:"receipt_chronology"`
	Events              []Evidence                          `json:"events"`
	Gaps                []Gap                               `json:"receipt_gaps"`
	Verdicts            []Verdict                           `json:"assessments"`
	Recording           *RecordingEvidence                  `json:"current_recording"`
}

func cadenceConfigEqual(a, b Config) bool {
	authoritySame := (a.Authority == nil && b.Authority == nil) || (a.Authority != nil && b.Authority != nil && *a.Authority == *b.Authority)
	return authoritySame && a.Version == b.Version && ((a.Override == nil && b.Override == nil) || (a.Override != nil && b.Override != nil && *a.Override == *b.Override))
}
func median(g []float64) *float64 {
	if len(g) < 3 {
		return nil
	}
	c := append([]float64{}, g...)
	sort.Float64s(c)
	v := c[len(c)/2]
	if len(c)%2 == 0 {
		v = (c[len(c)/2-1] + v) / 2
	}
	return &v
}
func receiptClass(m Message, at string, p Policy) (string, float64) {
	age := instant(at).Sub(instant(m.Captured)).Seconds()
	if age < -p.Clock {
		return "clock_anomalous", age
	}
	if age > p.Clock {
		return "delayed_backlog", age
	}
	return "current_like", age
}
func (s *Store) FieldReport() (FieldReport, error) {
	_ = s.processProjection()
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return FieldReport{}, err
	}
	defer tx.Rollback()
	return s.fieldReport(tx)
}
func (s *Store) fieldReport(tx *sql.Tx) (FieldReport, error) {
	st, err := loadState(tx)
	if err != nil {
		return FieldReport{}, err
	}
	r := FieldReport{Schema: 2, ProjectionDecisions: st.Decisions, Policy: st.Policy, Generated: s.Now().UTC().Format(wireTime), Revision: BuildRevision(), Coverage: "Unique counts include all retained raw messages. Retry, operation and restart evidence begins when this recorder was installed. ACK offered does not prove phone receipt. Phone queue figures are explicitly timestamped last reports, not inferred current depth; physical radio state is unknown.", Devices: map[string]*Counters{}, Receipts: []Receipt{}, Events: []Evidence{}, Gaps: []Gap{}, Verdicts: []Verdict{}, Recording: recordingEvidence(st)}
	r.ProjectionPending, r.ProjectionError = st.ProjectionPending, st.ProjectionError
	if err = populateHistory(tx, &st, s.Now()); err != nil {
		return r, err
	}
	r.History = map[string]*HistoryState{}
	r.HistorySessions = map[string]map[string]*HistoryState{}
	for id, d := range st.Devices {
		r.History[id] = d.History
		r.HistorySessions[id] = d.HistorySessions
	}
	observations := map[string]map[string]bool{}
	nativeTimes := map[string]map[string]bool{}
	legacyTimes := map[string]map[string]bool{}
	for id, d := range st.Devices {
		r.Devices[id] = &Counters{Expected: d.Snapshot.Config.Effective, ProjectionReasons: map[string]int{}}
		observations[id] = map[string]bool{}
		nativeTimes[id] = map[string]bool{}
		legacyTimes[id] = map[string]bool{}
	}
	rows, err := tx.Query("SELECT known,received FROM raw ORDER BY received,device,sequence")
	if err != nil {
		return r, err
	}
	legacy := map[string]Receipt{}
	for rows.Next() {
		var b, at string
		if err = rows.Scan(&b, &at); err != nil {
			rows.Close()
			return r, err
		}
		var m Message
		if err = json.Unmarshal([]byte(b), &m); err != nil {
			rows.Close()
			return r, err
		}
		c := r.Devices[m.Device]
		c.Reports++
		switch m.Type {
		case "location":
			c.LocationReports++
		case "sos":
			c.SOSReports++
		default:
			c.RoutineReports++
		}
		c.LastReceipt = at
		if m.Fix != nil {
			if m.Observation != nil {
				observations[m.Device][m.Observation.Session+"/"+m.Observation.ID] = true
				nativeTimes[m.Device][m.Fix.Observed] = true
			} else {
				legacyTimes[m.Device][m.Fix.Observed] = true
			}
		}

		cl, age := receiptClass(m, at, st.Policy)
		e := receiptEvidence(m, at, at, "stored", nil)
		e.SourceQuality = "unavailable_at_receipt"
		legacy[m.Device+"/"+m.ID] = Receipt{Evidence: e, Class: cl, CaptureAge: age}
		if cl == "delayed_backlog" {
			c.DelayedReports++
			if m.Type == "location" {
				c.DelayedLocations++
			} else if m.Type == "status" {
				c.DelayedRoutine++
			}
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return r, err
	}
	for id, o := range observations {
		for t := range legacyTimes[id] {
			if !nativeTimes[id][t] {
				o["legacy/"+t] = true
			}
		}
		r.Devices[id].RawFixes = len(o)
	}
	for _, p := range st.Points {
		r.Devices[p.Device].Useful++
	}
	rows, err = tx.Query("SELECT ordinal,data FROM field_evidence ORDER BY ordinal")
	if err != nil {
		return r, err
	}
	for rows.Next() {
		var e Evidence
		var b string
		if err = rows.Scan(&e.Ordinal, &b); err != nil {
			rows.Close()
			return r, err
		}
		ordinal := e.Ordinal
		if err = json.Unmarshal([]byte(b), &e); err != nil {
			rows.Close()
			return r, err
		}
		e.Ordinal = ordinal
		r.Events = append(r.Events, e)
		if e.Kind == "stored" {
			key := e.Device + "/" + e.Message
			v := legacy[key]
			e.ReportReference = v.ReportReference
			if e.SourceQuality == "" {
				e.SourceQuality = "unavailable_at_receipt"
			}
			v.Evidence = e
			if e.TimingClass != "" {
				v.Class = e.TimingClass
			}
			r.Receipts = append(r.Receipts, v)
			delete(legacy, key)
		}
		if c := r.Devices[e.Device]; c != nil {
			if e.Kind == "duplicate" {
				c.Duplicates++
			}
			if e.Kind == "conflict" || (e.Kind == "batch_rejected" && e.FailureCode == "identity_conflict") {
				c.Conflicts++
			}
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return r, err
	}
	// Legacy receipts have no insertion ordinal. Never fabricate same-time arrival order.
	for _, v := range legacy {
		v.Kind = "legacy_stored"
		r.Receipts = append(r.Receipts, v)
	}
	sort.SliceStable(r.Receipts, func(i, j int) bool {
		a, b := r.Receipts[i], r.Receipts[j]
		if a.Ordinal > 0 && b.Ordinal > 0 {
			return a.Ordinal < b.Ordinal
		}
		if a.Ordinal != b.Ordinal {
			return a.Ordinal < b.Ordinal
		}
		if a.At != b.At {
			return a.At < b.At
		}
		if a.Device != b.Device {
			return a.Device < b.Device
		}
		return a.Message < b.Message
	})

	for _, d := range st.Decisions {
		if c := r.Devices[d.Device]; c != nil {
			c.ProjectionReasons[d.Reason]++
		}
	}
	analyzeField(&r, st.Policy)
	for device, h := range r.History {
		verdict, reason := "INCONCLUSIVE", "No recent phone queue report proves final synchronization"
		if h.Condition == "incomplete" {
			verdict, reason = "FAIL", "Phone explicitly reports known collection loss"
		}
		if h.Condition == "unresolved" {
			verdict, reason = "FAIL", "Permanent delivery rejection remains unresolved"
		}
		if h.Condition == "synchronized" && !r.ProjectionPending && r.ProjectionError == "" {
			verdict, reason = "PASS", "Contiguous durable identities and recent zero-pending phone report; projection work complete"
		}
		r.Verdicts = append(r.Verdicts, Verdict{Scenario: "native_history_synchronization", Device: device, Result: verdict, Reason: reason})
	}
	return r, nil
}
func analyzeField(r *FieldReport, p Policy) {
	last := map[string]Receipt{}
	lastLive := map[string]Receipt{}
	gaps := map[string][]float64{}
	gapIndex := map[string]int{}
	// Periodic health and live GNSS are independent deliveries at the same
	// configured cadence. Mixing their gaps halves the apparent interval.
	cadenceType := map[string]string{}
	for _, v := range r.Receipts {
		if v.Kind != "stored" || v.DeliveryRole == "history" || v.Class != "current_like" || (v.Type != "location" && v.Type != "status") {
			continue
		}
		age := instant(r.Generated).Sub(instant(v.At)).Seconds()
		if age < 0 || age > float64(2*r.Devices[v.Device].Expected+5) {
			continue
		}
		if cadenceType[v.Device] == "" || v.Type == "location" {
			cadenceType[v.Device] = v.Type
		}
	}
	for index, v := range r.Receipts {
		if previous, ok := lastLive[v.Device]; ok && !instant(v.Captured).After(instant(previous.Captured)) {
			v.NonAdvancingHistory = true
			r.Receipts[index].NonAdvancingHistory = true
			// Near-current intermediate callbacks still follow a newer live report.
			if v.Class == "current_like" {
				c := r.Devices[v.Device]
				c.DelayedReports++
				if v.Type == "location" {
					c.DelayedLocations++
				} else if v.Type == "status" {
					c.DelayedRoutine++
				}
			}
		}
		old, ok := last[v.Device]
		gap := instant(v.At).Sub(instant(old.At)).Seconds()
		expected := old.Effective
		if live, known := lastLive[v.Device]; known {
			expected = live.Effective
		}
		if ok && gap > float64(4*expected+10) && v.Kind == "stored" && old.Kind == "stored" {
			r.Gaps = append(r.Gaps, Gap{Recovered: true, GapBeginsAfter: instant(old.At).Add(time.Duration(4*expected+10) * time.Second).Format(wireTime), Device: v.Device, LastReceipt: old.At, FirstReceipt: v.At, Duration: gap, FirstMessage: v.Message, CaptureAge: v.CaptureAge, FirstCaptured: v.Captured, FirstObserved: v.Observed, OlderFollowing: []string{}})
			gapIndex[v.Device] = len(r.Gaps) - 1
			verdict := "INCONCLUSIVE"
			reason := "First receipt has clock ambiguity or lacks exact arrival evidence"
			if v.Class == "current_like" && v.DeliveryRole != "history" && v.FreshAtReceipt != nil && *v.FreshAtReceipt {
				verdict = "PASS"
				reason = "First unique post-gap receipt carries a fresh quality-valid GNSS observation"
			} else if v.Class == "current_like" {
				reason = "Fresh report capture does not prove current position; no fresh quality-valid GNSS observation"
			}
			if v.Class == "delayed_backlog" {
				verdict = "FAIL"
				reason = "First unique post-gap report was already delayed at receipt"
			}
			r.Verdicts = append(r.Verdicts, Verdict{"reconnect_current_first", v.Device, verdict, reason, r.Gaps[len(r.Gaps)-1]})
		}
		if i, ok := gapIndex[v.Device]; ok && v.Class == "delayed_backlog" && instant(v.Captured).Before(instant(r.Gaps[i].FirstCaptured)) {
			r.Gaps[i].OlderFollowing = append(r.Gaps[i].OlderFollowing, v.Message)
		}
		if v.LiveBefore != "" && v.LiveAfter != "" && v.LiveAfter < v.LiveBefore {
			r.Verdicts = append(r.Verdicts, Verdict{"live_state_during_backlog", v.Device, "FAIL", "Persisted live observed timestamp regressed", v.Message})
		}
		if v.Class == "current_like" && v.DeliveryRole != "history" && !v.NonAdvancingHistory && v.Kind == "stored" && v.Type == cadenceType[v.Device] {
			prev, ok := lastLive[v.Device]
			if !ok || prev.Effective != v.Effective || !cadenceConfigEqual(prev.Reported.Config, v.Reported.Config) {
				gaps[v.Device] = nil
			} else {
				g := instant(v.At).Sub(instant(prev.At)).Seconds()
				if g > 0 && g <= float64(2*v.Effective+5) {
					gaps[v.Device] = append(gaps[v.Device], g)
					if len(gaps[v.Device]) > 7 {
						gaps[v.Device] = gaps[v.Device][1:]
					}
				} else {
					gaps[v.Device] = nil
				}
			}
			lastLive[v.Device] = v
		}
		last[v.Device] = v
	}
	ids := []string{}
	for id := range r.Devices {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		if old, ok := last[id]; ok && old.Kind == "stored" {
			threshold := 4*r.Devices[id].Expected + 10
			duration := instant(r.Generated).Sub(instant(old.At)).Seconds()
			if duration > float64(threshold) {
				r.Gaps = append(r.Gaps, Gap{Device: id, LastReceipt: old.At, Duration: duration, GapBeginsAfter: instant(old.At).Add(time.Duration(threshold) * time.Second).Format(wireTime), OlderFollowing: []string{}})
			}
		}
	}
	for _, id := range ids {
		c := r.Devices[id]
		c.Observed = median(gaps[id])
		if latest, ok := lastLive[id]; !ok || instant(r.Generated).Sub(instant(latest.At)).Seconds() > float64(2*c.Expected+5) {
			c.Observed = nil
		}
		verdict, reason := "INCONCLUSIVE", "No recent explicit zero-pending phone report; correlate Android pendingOutbox timeline"
		var evidence any
		if h := r.History[id]; h != nil && h.Condition == "synchronized" {
			verdict, reason = "PASS", "Phone explicitly reported zero pending at the exported measured_at; all identities through that report are durable"
			evidence = h.LatestReported
		}
		r.Verdicts = append(r.Verdicts, Verdict{"android_queue_drained", id, verdict, reason, evidence})
	}
	for _, id := range ids {
		saw := false
		regressed := false
		for _, v := range r.Receipts {
			if v.Device == id && v.Class == "delayed_backlog" && v.LiveBefore != "" && v.LiveAfter != "" {
				saw = true
				if v.LiveAfter < v.LiveBefore {
					regressed = true
				}
			}
		}
		result := "INCONCLUSIVE"
		reason := "No delayed receipt with before/after live state evidence"
		if saw {
			result = "PASS"
			reason = "All captured delayed receipts preserved monotonic live observation state"
		}
		if regressed {
			result = "FAIL"
			reason = "Live observed timestamp regressed"
		}
		r.Verdicts = append(r.Verdicts, Verdict{"live_state_during_backlog", id, result, reason, nil})
	}
	for _, e := range r.Events {
		if e.Kind != "recording_resume" || e.Before == nil || e.Before.Recording == nil || e.After == nil || e.After.Recording == nil || len(e.After.Recording.Windows) == len(e.Before.Recording.Windows) {
			continue
		}
		result := "INCONCLUSIVE"
		reason := "No accepted post-resume observation retained yet"
		rec := r.Recording
		if rec.Recording == nil || rec.Recording.ID != e.After.Recording.ID {
			for _, later := range r.Events {
				if later.Ordinal > e.Ordinal && later.Before != nil && later.Before.Recording != nil && later.Before.Recording.ID == e.After.Recording.ID {
					rec = later.Before
				}
			}
		}
		if rec != nil && !rec.ProjectionPending && rec.Recording != nil && rec.Recording.ID == e.After.Recording.ID {
			window := e.After.Recording.Windows[len(e.After.Recording.Windows)-1]
			compared := 0
			missing := false
			failed := false
			for _, part := range rec.Participation {
				if part.Window != window.ID {
					continue
				}
				var prior *PointEvidence
				for _, point := range rec.Points {
					if point.Device == part.Device && instant(point.Observed).Before(window.Start) {
						copy := point
						prior = &copy
					}
				}
				if prior == nil {
					missing = true
					continue
				}
				compared++
				var first *PointEvidence
				for _, point := range rec.Points {
					if point.Device == part.Device && point.Observed == part.First {
						copy := point
						first = &copy
						break
					}
				}
				if math.Abs(part.StartDistance-prior.Distance) > 0.000001 || first == nil || first.Segment == prior.Segment {
					failed = true
				}
			}
			if failed {
				result = "FAIL"
				reason = "Resumed first point added connector distance or reused previous segment"
			} else if compared > 0 && !missing {
				result = "PASS"
				reason = "Reconciled resumed windows preserve prior distance and separate segments"
			}
		}

		r.Verdicts = append(r.Verdicts, Verdict{"recording_stop_resume", "", result, reason, map[string]any{"operation_ordinal": e.Ordinal, "recording_id": e.After.Recording.ID}})
	}

	for _, e := range r.Events {
		if e.Kind != "override_requested" {
			continue
		}
		result := "INCONCLUSIVE"
		reason := "No subsequent unique current report echoes the requested authority/version/value with enough cadence samples"
		offered := false
		var offeredAt, echoedAt string
		var previous *Receipt
		var newestCapture string
		gs := []float64{}
		sampleType := "status"
		for _, v := range r.Events {
			if v.Ordinal > e.Ordinal && v.Device == e.Device && v.Kind == "override_requested" {
				break
			}
			if v.Ordinal > e.Ordinal && v.Device == e.Device && v.Kind == "stored" && v.Type == "location" && v.DeliveryRole != "history" && v.Reported != nil && configEqual(v.Reported.Config, *e.Offered) && v.TimingClass == "current_like" {
				sampleType = "location"
			}
		}
		for _, v := range r.Events {
			if v.Ordinal > e.Ordinal && v.Device == e.Device && v.Kind == "override_requested" {
				break
			}
			if v.Ordinal <= e.Ordinal || v.Device != e.Device {
				continue
			}
			if v.Offered != nil && configEqual(*v.Offered, *e.Offered) {
				offered = true
				if offeredAt == "" {
					offeredAt = v.At
				}
			}
			if v.Kind != "stored" || v.Reported == nil || !configEqual(v.Reported.Config, *e.Offered) {
				continue
			}
			cl := v.TimingClass
			if cl == "" {
				cl, _ = receiptClass(Message{Captured: v.Captured}, v.At, p)
			}
			if cl == "current_like" && echoedAt == "" {
				echoedAt = v.At
			}
			if v.Type != sampleType || v.DeliveryRole == "history" || cl != "current_like" || (newestCapture != "" && !instant(v.Captured).After(instant(newestCapture))) {
				continue
			}
			newestCapture = v.Captured
			if echoedAt == "" {
				echoedAt = v.At
			}
			cur := Receipt{Evidence: v}
			if previous != nil {
				g := instant(v.At).Sub(instant(previous.At)).Seconds()
				if g > 0 && g <= float64(2*v.Effective+5) {
					gs = append(gs, g)
					if len(gs) > 7 {
						gs = gs[1:]
					}
				} else {
					gs = nil
				}
			}
			previous = &cur
		}
		med := median(gs)
		if med != nil && previous != nil {
			expected := float64(previous.Effective)
			result = "FAIL"
			reason = "Config echoed but unique live receipt cadence differs from expected"
			if *med >= expected*.7 && *med <= expected*1.3 {
				result = "PASS"
				reason = "Device echoed requested config and at least three current receipt gaps match expected cadence"
			}
		}
		r.Verdicts = append(r.Verdicts, Verdict{"reporting_config_convergence", e.Device, result, reason, map[string]any{"request_ordinal": e.Ordinal, "requested": e.Offered, "ack_offered": offered, "first_ack_offered_at": offeredAt, "first_current_config_echo_at": echoedAt, "median_gap_s": med}})
	}
	r.Verdicts = append(r.Verdicts, Verdict{"command_restart_dedupe", "", "INCONCLUSIVE", "Retry counts and restart boundaries are retained; a pre-restart identity retried after restart is required for proof", nil})
	for i, e := range r.Events {
		if e.Kind != "command_open" {
			continue
		}
		for _, v := range r.Events[i+1:] {
			if v.Kind != "duplicate" {
				continue
			}
			for _, prior := range r.Events[:i] {
				if prior.Kind == "stored" && prior.Device == v.Device && prior.Message == v.Message && prior.FirstReceived == v.FirstReceived && v.UsefulBefore == v.UsefulAfter && v.DistanceBefore == v.DistanceAfter {
					r.Verdicts[len(r.Verdicts)-1] = Verdict{"command_restart_dedupe", v.Device, "PASS", "Identical retry after a persisted restart boundary retained the original receipt, with unchanged accepted point count and distance", v.Message}
				}
			}
		}
	}
}
func (s *Store) ExportFieldReport(w io.Writer) error {
	r, err := s.FieldReport()
	if err != nil {
		return err
	}
	z := zip.NewWriter(w)
	files := []struct {
		name string
		data any
	}{{"manifest.json", map[string]any{"schema_version": r.Schema, "generated_at": r.Generated, "build": r.Revision, "privacy": "No coordinates, Party names, addresses, raw envelopes, SQLite files or secrets. Device/message/recording identifiers are included for correlation."}}, {"field-report.json", r}}
	for _, f := range files {
		entry, err := z.Create(f.name)
		if err != nil {
			return err
		}
		encoder := json.NewEncoder(entry)
		encoder.SetIndent("", "  ")
		if err = encoder.Encode(f.data); err != nil {
			return err
		}
	}
	return z.Close()
}

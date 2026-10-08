package core

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	_ "github.com/ncruces/go-sqlite3/driver"
	_ "github.com/ncruces/go-sqlite3/embed"
)

type Device struct {
	LocationReason   string    `json:"location_reason"`
	Color            string    `json:"track_color"`
	Dash             string    `json:"track_dash"`
	ID               string    `json:"device_id"`
	Snapshot         Message   `json:"snapshot"`
	Location         *Message  `json:"location"`
	Contact          string    `json:"last_contact"`
	Desired          Config    `json:"desired_config"`
	Converged        bool      `json:"config_converged"`
	ContactCondition string    `json:"contact_condition"`
	GNSSCondition    string    `json:"gnss_condition"`
	LocationAge      *float64  `json:"location_age_s"`
	Total            float64   `json:"total_m"`
	Evidence         *Counters `json:"field_evidence,omitempty"`
}
type Window struct {
	ID     string     `json:"segment_id"`
	Start  time.Time  `json:"start"`
	Stop   *time.Time `json:"stop"`
	Policy Policy     `json:"policy"`
	Reason string     `json:"reason"`
}
type Recording struct {
	ID      string   `json:"recording_id"`
	Active  bool     `json:"active"`
	Windows []Window `json:"windows"`
}
type SOSAlert struct {
	Device       string  `json:"device_id"`
	Event        string  `json:"event_id"`
	Party        Party   `json:"party"`
	Triggered    string  `json:"triggered_at"`
	Captured     string  `json:"captured_at"`
	Received     string  `json:"received_at"`
	Fix          *Fix    `json:"fix"`
	GNSS         string  `json:"gnss_status"`
	Freshness    string  `json:"location_freshness"`
	Acknowledged *string `json:"operator_acknowledged_at"`
}

func alertFrom(m Message, received string) SOSAlert {
	freshness := "missing"
	if m.Fix != nil {
		freshness = "last_known"
		if m.Health.GNSS == "fix" && m.Fix.Age <= 30000 {
			freshness = "fresh_at_capture"
		}
		if absClock(m) > 120 {
			freshness = "clock_anomaly"
		}
	}
	return SOSAlert{Device: m.Device, Event: m.SOS.EventID, Party: m.Party,
		Triggered: m.SOS.Triggered, Captured: m.Captured, Received: received,
		Fix: m.Fix, GNSS: m.Health.GNSS, Freshness: freshness}
}
func absClock(m Message) float64 {
	if m.Fix == nil {
		return 0
	}
	d := instant(m.Captured).Sub(instant(m.Fix.Observed)).Seconds() - float64(m.Fix.Age)/1000
	if d < 0 {
		return -d
	}
	return d
}

type State struct {
	Alerts []SOSAlert `json:"sos_alerts"`

	Authority string             `json:"authority_id"`
	Policy    Policy             `json:"policy"`
	Devices   map[string]*Device `json:"devices"`
	Recording *Recording         `json:"recording"`
	Points    []Point            `json:"points"`
	Decisions []Decision         `json:"decisions"`
}
type Store struct {
	db  *sql.DB
	mu  sync.Mutex
	Now func() time.Time
}

var ErrConflict = errors.New("identity conflict")
var ErrAction = errors.New("invalid recording action")

func Open(path string) (*Store, error) {
	if path != ":memory:" {
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			return nil, err
		}
	}
	// SQLite is compiled into embedded WASM and executed by Go's wazero runtime.
	db, err := sql.Open("sqlite3", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	s := &Store{db: db, Now: time.Now}
	fail := func(e error) (*Store, error) { db.Close(); return nil, e }
	for _, q := range []string{"PRAGMA journal_mode=WAL", "PRAGMA synchronous=FULL", "PRAGMA busy_timeout=5000", `CREATE TABLE IF NOT EXISTS raw (device TEXT NOT NULL, message TEXT NOT NULL, sequence INTEGER NOT NULL, known TEXT NOT NULL, wire BLOB NOT NULL, received TEXT NOT NULL, PRIMARY KEY(device,message), UNIQUE(device,sequence))`, `CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY CHECK(id=1), data TEXT NOT NULL)`, `CREATE TABLE IF NOT EXISTS retired (recording TEXT PRIMARY KEY, data TEXT NOT NULL)`, `CREATE TABLE IF NOT EXISTS field_evidence (ordinal INTEGER PRIMARY KEY AUTOINCREMENT, data TEXT NOT NULL)`} {
		if _, err = db.Exec(q); err != nil {
			return fail(err)
		}
	}
	state := State{Authority: id(), Policy: DefaultPolicy(), Devices: map[string]*Device{}, Points: []Point{}, Decisions: []Decision{}}
	b, _ := json.Marshal(state)
	if _, err = db.Exec("INSERT OR IGNORE INTO state(id,data) VALUES(1,?)", string(b)); err != nil {
		return fail(err)
	}
	tx, err := db.Begin()
	if err != nil {
		return fail(err)
	}
	st, err := loadState(tx)
	if err == nil {
		err = saveState(tx, st)
	}
	if err == nil {
		for _, a := range st.Alerts {
			err = writeEvidence(tx, Evidence{At: s.Now().UTC().Format(wireTime), Kind: "sos_restored", Device: a.Device,
				SOSRef: sosRef(a.Event), SOSAcknowledged: a.Acknowledged, FirstReceived: a.Received})
			if err != nil {
				break
			}
		}
	}
	if err != nil {
		tx.Rollback()
		return fail(err)
	}
	if err = tx.Commit(); err != nil {
		return fail(err)
	}

	revision := BuildRevision()
	event, _ := json.Marshal(Evidence{At: s.Now().UTC().Format(wireTime), Kind: "command_open", Revision: &revision})
	if _, err = db.Exec("INSERT INTO field_evidence(data) VALUES(?)", string(event)); err != nil {
		return fail(err)
	}
	return s, nil
}
func (s *Store) Close() error { return s.db.Close() }
func loadState(tx *sql.Tx) (State, error) {
	var st State
	var b string
	if err := tx.QueryRow("SELECT data FROM state WHERE id=1").Scan(&b); err != nil {
		return st, err
	}
	err := json.Unmarshal([]byte(b), &st)
	// Additive upgrade for reserved SOS envelopes stored before Issue #5.
	if err == nil && st.Alerts == nil {
		st.Alerts = []SOSAlert{}
		rows, e := tx.Query("SELECT known,received FROM raw ORDER BY device,sequence")
		if e != nil {
			return st, e
		}
		for rows.Next() {
			var known, received string
			if e = rows.Scan(&known, &received); e != nil {
				rows.Close()
				return st, e
			}
			var m Message
			if e = json.Unmarshal([]byte(known), &m); e != nil {
				rows.Close()
				return st, e
			}
			if m.Type == "sos" && m.SOS != nil {
				st.Alerts = append(st.Alerts, alertFrom(m, received))
			}
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return st, e
		}
	}
	return st, err
}
func saveState(tx *sql.Tx, st State) error {
	b, err := json.Marshal(st)
	if err != nil {
		return err
	}
	_, err = tx.Exec("UPDATE state SET data=? WHERE id=1", string(b))
	return err
}
func configEqual(a, b Config) bool {
	if a.Authority == nil || b.Authority == nil || *a.Authority != *b.Authority || a.Version != b.Version {
		return false
	}
	return (a.Override == nil && b.Override == nil) || (a.Override != nil && b.Override != nil && *a.Override == *b.Override)
}
func (s *Store) Ingest(b []byte) (Ack, error) { return s.ingestAt(b, s.Now().UTC()) }

func (s *Store) ingestAt(b []byte, ingress time.Time) (Ack, error) {
	m, err := Parse(b)
	if err != nil {
		return Ack{}, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	received := ingress.UTC().Format(wireTime)
	tx, err := s.db.Begin()
	if err != nil {
		return Ack{}, err
	}
	defer tx.Rollback()
	st, err := loadState(tx)
	if err != nil {
		return Ack{}, err
	}
	known, _ := json.Marshal(m)
	var old, first string
	err = tx.QueryRow("SELECT known,received FROM raw WHERE device=? AND (message=? OR sequence=?)", m.Device, m.ID, m.Sequence).Scan(&old, &first)
	result := "stored"
	if err == nil {
		if old != string(known) {
			e := receiptEvidence(m, received, first, "conflict", st.Devices[m.Device])
			e.TimingClass, _ = receiptClass(m, received, st.Policy)
			e.ClockTolerance = st.Policy.Clock
			var existing Message
			_ = json.Unmarshal([]byte(old), &existing)
			e.ConflictingMessage = existing.ID
			e.ConflictingSequence = existing.Sequence
			if err = writeEvidence(tx, e); err != nil {
				return Ack{}, err
			}
			if err = tx.Commit(); err != nil {
				return Ack{}, err
			}
			return Ack{}, ErrConflict
		}
		result = "duplicate"
	} else if !errors.Is(err, sql.ErrNoRows) {
		return Ack{}, err
	} else {
		first = received
		if _, err = tx.Exec("INSERT INTO raw VALUES(?,?,?,?,?,?)", m.Device, m.ID, m.Sequence, string(known), b, first); err != nil {
			return Ack{}, err
		}
	}
	evidence := receiptEvidence(m, received, first, result, st.Devices[m.Device])
	evidence.TimingClass, _ = receiptClass(m, received, st.Policy)
	evidence.ClockTolerance = st.Policy.Clock
	evidence.UsefulBefore, evidence.DistanceBefore = trackEffect(st, m.Device)
	d := st.Devices[m.Device]
	if d == nil {
		authority := st.Authority
		palette := []string{"#245fa5", "#8857a5", "#ad5525", "#267b7b", "#925a80", "#617224", "#5449a4", "#3b718a", "#996329", "#9b4660", "#4e6e50", "#76583f"}
		styles := []string{"", "10 4", "3 4", "12 4 3 4"}
		index := len(st.Devices)
		d = &Device{ID: m.Device, Snapshot: m, Desired: Config{Authority: &authority}, Color: palette[index%len(palette)], Dash: styles[(index/len(palette))%len(styles)]}
		st.Devices[m.Device] = d
	}
	if received > d.Contact {
		d.Contact = received
	}
	if less(d.Snapshot, m, false) {
		d.Snapshot = m
	}
	if m.Fix != nil && (d.Location == nil || less(*d.Location, m, true)) {
		copy := m
		d.Location = &copy
	}
	if result == "stored" && m.Type == "sos" {
		st.Alerts = append(st.Alerts, alertFrom(m, first))
	}
	if result == "stored" {
		if err = rebuild(tx, &st); err != nil {
			return Ack{}, err
		}
	}
	evidence.LiveAfter = liveObserved(d)
	evidence.UsefulAfter, evidence.DistanceAfter = trackEffect(st, m.Device)
	offered := d.Desired
	evidence.Offered = &offered
	if err = writeEvidence(tx, evidence); err != nil {
		return Ack{}, err
	}
	if err = saveState(tx, st); err != nil {
		return Ack{}, err
	}
	if err = tx.Commit(); err != nil {
		return Ack{}, err
	}
	return Ack{1, m.Device, m.ID, m.Sequence, result, first, d.Desired}, nil
}

// Replacing the complete current derivation inside the ingestion transaction is
// intentionally simple. Closed windows remain eligible; retired windows never do.
func rebuild(tx *sql.Tx, st *State) error {
	st.Points = []Point{}
	st.Decisions = []Decision{}
	for _, d := range st.Devices {
		d.Total = 0
	}
	rows, err := tx.Query("SELECT known,received FROM raw")
	if err != nil {
		return err
	}
	raw := []Candidate{}
	for rows.Next() {
		var b, r string
		if err = rows.Scan(&b, &r); err != nil {
			rows.Close()
			return err
		}
		var m Message
		if err = json.Unmarshal([]byte(b), &m); err != nil {
			rows.Close()
			return err
		}
		if m.Type == "location" {
			raw = append(raw, Candidate{m, r})
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	// Live validity is separate from recording membership. A latest measured jump
	// remains visible as telemetry but must not be labelled usable GNSS.
	for device, d := range st.Devices {
		if d.Location == nil {
			continue
		}
		d.LocationReason = Quality(*d.Location, st.Policy)
		c := []Candidate{}
		for _, r := range raw {
			if r.Device == device {
				c = append(c, r)
			}
		}
		_, decisions := Derive(c, st.Policy, "live", 0)
		for _, decision := range decisions {
			if decision.Message == d.Location.ID && decision.Reason != "accepted" && decision.Reason != "below_movement_threshold" && decision.Reason != "same_observation_time" {
				d.LocationReason = decision.Reason
			}
		}
	}
	if st.Recording == nil {
		return nil
	}
	ids := []string{}
	for k := range st.Devices {
		ids = append(ids, k)
	}
	sort.Strings(ids)
	for _, w := range st.Recording.Windows {
		for _, device := range ids {
			c := []Candidate{}
			for _, r := range raw {
				t := instant(r.Fix.Observed)
				if r.Device == device && !t.Before(w.Start) && (w.Stop == nil || t.Before(*w.Stop)) {
					c = append(c, r)
				}
			}
			points, decisions := Derive(c, w.Policy, w.ID, st.Devices[device].Total)
			if len(points) > 0 {
				points[0].Reason = w.Reason + ":" + points[0].Reason
				st.Devices[device].Total = points[len(points)-1].Distance
			}
			st.Points = append(st.Points, points...)
			st.Decisions = append(st.Decisions, decisions...)
		}
	}
	return nil
}
func (s *Store) change(kind, device string, fn func(*State, time.Time, *sql.Tx) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	st, err := loadState(tx)
	if err != nil {
		return err
	}
	before := recordingEvidence(st)
	now := s.Now().UTC()
	if err = fn(&st, now, tx); err != nil {
		return err
	}
	if err = rebuild(tx, &st); err != nil {
		return err
	}
	e := Evidence{At: now.Format(wireTime), Kind: kind, Device: device, Before: before, After: recordingEvidence(st)}
	if kind == "override_requested" {
		desired := st.Devices[device].Desired
		e.Offered = &desired
		e.Before = nil
		e.After = nil
	}
	if err = writeEvidence(tx, e); err != nil {
		return err
	}
	if err = saveState(tx, st); err != nil {
		return err
	}
	return tx.Commit()
}
func (s *Store) AcknowledgeSOS(device, event string) error {
	if !uuid.MatchString(device) || !uuid.MatchString(event) {
		return fmt.Errorf("device and event UUID required")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	st, err := loadState(tx)
	if err != nil {
		return err
	}
	for i := range st.Alerts {
		a := &st.Alerts[i]
		if a.Device != device || a.Event != event {
			continue
		}
		if a.Acknowledged != nil {
			return nil
		}
		at := s.Now().UTC().Format(wireTime)
		a.Acknowledged = &at
		if err = writeEvidence(tx, Evidence{At: at, Kind: "sos_operator_acknowledged", Device: a.Device,
			SOSRef: sosRef(a.Event), SOSAcknowledged: &at, FirstReceived: a.Received}); err != nil {
			return err
		}
		if err = saveState(tx, st); err != nil {
			return err
		}
		return tx.Commit()
	}
	return fmt.Errorf("unknown SOS device/event pair")
}

func (s *Store) Action(action string) error {
	return s.change("recording_"+action, "", func(st *State, now time.Time, tx *sql.Tx) error {
		r := st.Recording
		open := func(reason string) {
			r.Active = true
			r.Windows = append(r.Windows, Window{ID: id(), Start: now, Policy: st.Policy, Reason: reason})
		}
		switch action {
		case "start":
			if r != nil {
				if r.Active {
					return nil
				}
				return ErrAction
			}
			r = &Recording{ID: id()}
			st.Recording = r
			open("start")
		case "stop":
			if r == nil {
				return ErrAction
			}
			if r.Active {
				r.Windows[len(r.Windows)-1].Stop = &now
				r.Active = false
			}
		case "resume":
			if r == nil {
				return ErrAction
			}
			if !r.Active {
				open("resume")
			}
		case "clear":
			if r != nil {
				b, _ := json.Marshal(r)
				if _, err := tx.Exec("INSERT INTO retired VALUES(?,?)", r.ID, string(b)); err != nil {
					return err
				}
			}
			st.Recording = nil
		default:
			return ErrAction
		}
		return nil
	})
}
func (s *Store) SetPolicy(p Policy) error {
	if err := p.Validate(); err != nil {
		return err
	}
	return s.change("policy_changed", "", func(st *State, now time.Time, _ *sql.Tx) error {
		p.Revision = st.Policy.Revision + 1
		st.Policy = p
		if r := st.Recording; r != nil && r.Active {
			r.Windows[len(r.Windows)-1].Stop = &now
			r.Windows = append(r.Windows, Window{ID: id(), Start: now, Policy: p, Reason: "policy_change"})
		}
		return nil
	})
}
func (s *Store) SetOverride(device string, v *int) error {
	if v != nil && (*v < 5 || *v > 86400 || *v%5 != 0) {
		return fmt.Errorf("interval must be 5..86400 in multiples of 5")
	}
	return s.change("override_requested", device, func(st *State, _ time.Time, _ *sql.Tx) error {
		d := st.Devices[device]
		if d == nil {
			return fmt.Errorf("unknown device")
		}
		if d.Desired.Version >= maxInteger {
			return fmt.Errorf("config version exhausted")
		}
		d.Desired.Version++
		d.Desired.Override = v
		return nil
	})
}
func (s *Store) Snapshot(dot int) (State, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return State{}, err
	}
	defer tx.Rollback()
	st, err := loadState(tx)
	if err != nil {
		return st, err
	}
	now := s.Now()
	for _, d := range st.Devices {
		d.Converged = configEqual(d.Desired, d.Snapshot.Config.Config)
		cadence := float64(d.Snapshot.Config.Effective)
		age := now.Sub(instant(d.Contact)).Seconds()
		d.ContactCondition = "healthy"
		if age > 2*cadence+5 {
			d.ContactCondition = "delayed"
		}
		if age > 4*cadence+10 {
			d.ContactCondition = "contact_lost"
		}
		d.GNSSCondition = d.Snapshot.Health.GNSS
		if d.Location != nil {
			locationAge := mathMax(float64(d.Location.Fix.Age)/1000, now.Sub(instant(d.Location.Fix.Observed)).Seconds())
			d.LocationAge = &locationAge
			if d.Snapshot.Health.GNSS == "fix" {
				d.GNSSCondition = d.LocationReason
				if locationAge > st.Policy.Age {
					d.GNSSCondition = "stale"
				}
				if instant(d.Location.Captured).After(now.Add(time.Duration(st.Policy.Clock * float64(time.Second)))) {
					d.GNSSCondition = "clock_anomaly"
				}
			}
		}
	}
	report, err := s.fieldReport(tx)
	if err != nil {
		return st, err
	}
	for id, d := range st.Devices {
		d.Evidence = report.Devices[id]
	}
	st.Points = Present(st.Points, dot)
	return st, nil
}
func mathMax(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}

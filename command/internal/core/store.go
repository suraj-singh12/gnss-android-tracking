package core

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	_ "github.com/ncruces/go-sqlite3/driver"
	_ "github.com/ncruces/go-sqlite3/embed"
)

type ReportedSession struct {
	ID       string `json:"tracking_session_id"`
	Start    string `json:"session_started_at"`
	Reported string `json:"reported_at"`
	Envelope int64  `json:"report_sequence"`
}
type Device struct {
	Session          *ReportedSession         `json:"reported_tracking_session,omitempty"`
	CurrentPosition  bool                     `json:"current_position"`
	LiveX            *float64                 `json:"live_x_m"`
	LiveY            *float64                 `json:"live_y_m"`
	LocationReceived string                   `json:"location_received_at"`
	LocationReason   string                   `json:"location_reason"`
	Color            string                   `json:"track_color"`
	Dash             string                   `json:"track_dash"`
	ID               string                   `json:"device_id"`
	Snapshot         Message                  `json:"snapshot"`
	Location         *Message                 `json:"location"`
	Contact          string                   `json:"last_contact"`
	Desired          Config                   `json:"desired_config"`
	Converged        bool                     `json:"config_converged"`
	ContactCondition string                   `json:"contact_condition"`
	GNSSCondition    string                   `json:"gnss_condition"`
	LocationAge      *float64                 `json:"location_age_s"`
	UsefulPoints     int                      `json:"useful_points"`
	Total            float64                  `json:"total_m"`
	PhoneQueue       *QueueProgress           `json:"last_reported_phone_queue"`
	HistorySessions  map[string]*HistoryState `json:"history_sessions"`
	History          *HistoryState            `json:"history"`
	Evidence         *Counters                `json:"field_evidence,omitempty"`
}
type Window struct {
	ID       string            `json:"segment_id"`
	Start    time.Time         `json:"start"`
	Stop     *time.Time        `json:"stop"`
	Policy   Policy            `json:"policy"`
	Reason   string            `json:"reason"`
	Sessions map[string]string `json:"sessions,omitempty"`
}
type Recording struct {
	ID      string   `json:"recording_id"`
	Active  bool     `json:"active"`
	Windows []Window `json:"windows"`
	Mode    string   `json:"mode"`
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
	Alerts            []SOSAlert         `json:"sos_alerts"`
	Authority         string             `json:"authority_id"`
	Policy            Policy             `json:"policy"`
	Devices           map[string]*Device `json:"devices"`
	Recording         *Recording         `json:"recording"`
	Points            []Point            `json:"points"`
	Decisions         []Decision         `json:"decisions"`
	RawVersion        int64              `json:"raw_version"`
	ProjectionVersion int64              `json:"projection_version"`
	ProjectionPending bool               `json:"projection_pending"`
	ProjectionError   string             `json:"projection_error,omitempty"`
	RawPoints         []Point            `json:"raw_points,omitempty"`
	Provisional       []Point            `json:"provisional_points,omitempty"`
}
type Store struct {
	db            *sql.DB
	mu            sync.Mutex
	Now           func() time.Time
	wake          chan struct{}
	stop          chan struct{}
	done          chan struct{}
	closeOnce     sync.Once
	closeError    error
	projectionMu  sync.Mutex
	projectionRaw map[string]*rawCursor
	projections   map[string]*ProjectionCache
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
	s := &Store{db: db, Now: time.Now, wake: make(chan struct{}, 1), stop: make(chan struct{}), done: make(chan struct{}), projections: map[string]*ProjectionCache{}, projectionRaw: map[string]*rawCursor{}}
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
	// Backfill the first receipt of the existing latest observation in older DBs.
	tx, err := db.Begin()
	if err != nil {
		return fail(err)
	}
	// Additive migration: derived arrays leave the frequently updated receipt state.
	// They remain the one authoritative cached projection, not another raw store.
	if _, err = tx.Exec(`CREATE TABLE IF NOT EXISTS projection_state(id INTEGER PRIMARY KEY CHECK(id=1),data TEXT NOT NULL)`); err != nil {
		tx.Rollback()
		return fail(err)
	}
	if _, err = tx.Exec(`INSERT OR IGNORE INTO projection_state SELECT 1,json_object('points',json_extract(data,'$.points'),'decisions',json_extract(data,'$.decisions')) FROM state WHERE id=1`); err != nil {
		tx.Rollback()
		return fail(err)
	}
	existing, err := loadState(tx)
	if err == nil {
		for _, d := range existing.Devices {
			if d.Location != nil && d.LocationReceived == "" {
				err = tx.QueryRow("SELECT received FROM raw WHERE device=? AND message=?", d.ID, d.Location.ID).Scan(&d.LocationReceived)
				if err != nil {
					break
				}
			}
		}
	}
	if err == nil {
		err = saveState(tx, existing)
	}
	if err == nil {
		for _, a := range existing.Alerts {
			err = writeEvidence(tx, Evidence{At: s.Now().UTC().Format(wireTime), Kind: "sos_restored", Device: a.Device,
				SOSRef: sosRef(a.Event), SOSAcknowledged: a.Acknowledged, FirstReceived: a.Received})
			if err != nil {
				break
			}
		}
	}
	if err == nil {
		err = tx.Commit()
	} else {
		tx.Rollback()
	}
	if err != nil {
		return fail(err)
	}
	revision := BuildRevision()
	event, _ := json.Marshal(Evidence{At: s.Now().UTC().Format(wireTime), Kind: "command_open", Revision: &revision})
	if _, err = db.Exec("INSERT INTO field_evidence(data) VALUES(?)", string(event)); err != nil {
		return fail(err)
	}
	for _, q := range []string{
		`CREATE TABLE IF NOT EXISTS observation_identity(device TEXT NOT NULL, session TEXT NOT NULL, sequence INTEGER NOT NULL, observation TEXT NOT NULL, message TEXT NOT NULL, PRIMARY KEY(device,session,sequence), UNIQUE(device,observation))`,
		`CREATE TABLE IF NOT EXISTS receipt_roles(device TEXT NOT NULL,message TEXT NOT NULL,role TEXT NOT NULL,PRIMARY KEY(device,message))`,
		`CREATE TABLE IF NOT EXISTS projection_work(device TEXT PRIMARY KEY, earliest TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0)`,
	} {
		if _, err = db.Exec(q); err != nil {
			return fail(err)
		}
	}
	rows, err := db.Query("PRAGMA table_info(projection_work)")
	if err != nil {
		return fail(err)
	}
	found := false
	for rows.Next() {
		var cid, notnull, pk int
		var name, kind string
		var def any
		if err = rows.Scan(&cid, &name, &kind, &notnull, &def, &pk); err != nil {
			rows.Close()
			return fail(err)
		}
		found = found || name == "generation"
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return fail(err)
	}
	if !found {
		if _, err = db.Exec("ALTER TABLE projection_work ADD COLUMN generation INTEGER NOT NULL DEFAULT 0"); err != nil {
			return fail(err)
		}
	}
	if err = s.backfillObservationIdentity(); err != nil {
		return fail(err)
	}
	go s.projectionLoop()
	s.notifyProjection()
	return s, nil
}
func (s *Store) Close() error {
	s.closeOnce.Do(func() { close(s.stop); <-s.done; s.closeError = s.db.Close() })
	return s.closeError
}

func loadReceiptState(tx *sql.Tx) (State, error) {
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
func loadState(tx *sql.Tx) (State, error) {
	st, err := loadReceiptState(tx)
	if err != nil {
		return st, err
	}
	var b string
	if err = tx.QueryRow("SELECT data FROM projection_state WHERE id=1").Scan(&b); err != nil {
		return st, err
	}
	var projection struct {
		Points    []Point    `json:"points"`
		Decisions []Decision `json:"decisions"`
	}
	if err = json.Unmarshal([]byte(b), &projection); err == nil {
		st.Points, st.Decisions = projection.Points, projection.Decisions
	}
	return st, err
}
func saveReceiptState(tx *sql.Tx, st State) error {
	st.Points = nil
	st.Decisions = nil
	st.RawPoints = nil
	st.Provisional = nil
	b, err := json.Marshal(st)
	if err != nil {
		return err
	}
	_, err = tx.Exec("UPDATE state SET data=? WHERE id=1", string(b))
	return err
}
func saveState(tx *sql.Tx, st State) error {
	for _, d := range st.Devices {
		d.UsefulPoints = 0
	}
	for _, p := range st.Points {
		if d := st.Devices[p.Device]; d != nil {
			d.UsefulPoints++
		}
	}
	b, err := json.Marshal(struct {
		Points    []Point    `json:"points"`
		Decisions []Decision `json:"decisions"`
	}{st.Points, st.Decisions})
	if err != nil {
		return err
	}
	if _, err = tx.Exec("UPDATE projection_state SET data=? WHERE id=1", string(b)); err != nil {
		return err
	}
	return saveReceiptState(tx, st)
}
func configEqual(a, b Config) bool {
	if a.Authority == nil || b.Authority == nil || *a.Authority != *b.Authority || a.Version != b.Version {
		return false
	}
	return (a.Override == nil && b.Override == nil) || (a.Override != nil && b.Override != nil && *a.Override == *b.Override)
}
func (s *Store) Ingest(b []byte) (Ack, error) { return s.ingestAt(b, s.Now().UTC()) }

func (s *Store) ingestAt(b []byte, ingress time.Time) (Ack, error) {
	return s.ingestRole(b, ingress, "live")
}
func (s *Store) ingestRole(b []byte, ingress time.Time, role string) (Ack, error) {
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
	st, err := loadReceiptState(tx)
	if err != nil {
		return Ack{}, err
	}
	if _, err = tx.Exec("INSERT OR IGNORE INTO receipt_roles VALUES(?,?,?)", m.Device, m.ID, role); err != nil {
		return Ack{}, err
	}
	ack, err := s.ingestMessage(tx, &st, m, b, received, role)
	if err != nil && !errors.Is(err, ErrConflict) {
		return Ack{}, err
	}
	if e := saveReceiptState(tx, st); e != nil {
		return Ack{}, e
	}
	if e := tx.Commit(); e != nil {
		return Ack{}, e
	}
	s.notifyProjection()
	return ack, err
}
func (s *Store) ingestMessage(tx *sql.Tx, st *State, m Message, b []byte, received string, role string) (Ack, error) {
	known, _ := json.Marshal(m)
	var old, first string
	err := tx.QueryRow("SELECT known,received FROM raw WHERE device=? AND (message=? OR sequence=?)", m.Device, m.ID, m.Sequence).Scan(&old, &first)
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
			return Ack{}, ErrConflict
		}
		result = "duplicate"
	} else if !errors.Is(err, sql.ErrNoRows) {
		return Ack{}, err
	} else {
		first = received
		if m.Observation != nil {
			o := m.Observation
			var start string
			startError := tx.QueryRow(`SELECT json_extract(r.known,'$.observation.session_started_at') FROM observation_identity i JOIN raw r ON r.device=i.device AND r.message=i.message WHERE i.device=? AND i.session=? LIMIT 1`, m.Device, o.Session).Scan(&start)
			if startError == nil && start != o.SessionStart {
				if err = writeObservationConflict(tx, st, m, received, role, "session_start_conflict", ""); err != nil {
					return Ack{}, err
				}
				return Ack{}, ErrConflict
			}
			if startError != nil && !errors.Is(startError, sql.ErrNoRows) {
				return Ack{}, startError
			}
			var existing string
			e := tx.QueryRow("SELECT message FROM observation_identity WHERE device=? AND ((session=? AND sequence=?) OR observation=?)", m.Device, o.Session, o.Sequence, o.ID).Scan(&existing)
			if e == nil {
				if err = writeObservationConflict(tx, st, m, received, role, "observation_sequence_conflict", existing); err != nil {
					return Ack{}, err
				}
				return Ack{}, ErrConflict
			}
			if !errors.Is(e, sql.ErrNoRows) {
				return Ack{}, e
			}
			if _, err = tx.Exec("INSERT INTO observation_identity VALUES(?,?,?,?,?)", m.Device, o.Session, o.Sequence, o.ID, m.ID); err != nil {
				return Ack{}, err
			}
		}
		if _, err = tx.Exec("INSERT INTO raw VALUES(?,?,?,?,?,?)", m.Device, m.ID, m.Sequence, string(known), b, first); err != nil {
			return Ack{}, err
		}
	}
	evidence := receiptEvidence(m, received, first, result, st.Devices[m.Device])
	evidence.DeliveryRole = role
	if d := st.Devices[m.Device]; d != nil && d.Location != nil && futureCandidate(*d.Location, received, st.Policy) {
		evidence.LiveBefore = "" // The anomalous future candidate was never a qualified current position.
	}
	quality, fresh := receiptQuality(m, received, st.Policy)
	evidence.SourceQuality, evidence.FreshAtReceipt = quality, &fresh
	evidence.TimingClass, _ = receiptClass(m, received, st.Policy)
	evidence.ClockTolerance = st.Policy.Clock
	evidence.UsefulBefore, evidence.DistanceBefore = trackEffect(*st, m.Device)
	d := st.Devices[m.Device]
	if d == nil {
		authority := st.Authority
		palette := []string{"#245fa5", "#8857a5", "#ad5525", "#267b7b", "#925a80", "#617224", "#5449a4", "#3b718a", "#996329", "#9b4660", "#4e6e50", "#76583f"}
		styles := []string{"", "10 4", "3 4", "12 4 3 4"}
		index := len(st.Devices)
		d = &Device{ID: m.Device, Snapshot: m, Desired: Config{Authority: &authority}, Color: palette[index%len(palette)], Dash: styles[(index/len(palette))%len(styles)]}
		st.Devices[m.Device] = d
	}
	var reported *ReportedSession
	captureAge := instant(received).Sub(instant(m.Captured)).Seconds()
	if o := m.Observation; o != nil && role == "live" && captureAge >= -liveClockTolerance(st.Policy) && captureAge <= 30 && instant(received).Sub(instant(m.Fix.Observed)).Seconds() >= -liveClockTolerance(st.Policy) && instant(received).Sub(instant(m.Fix.Observed)).Seconds() <= 30 {
		reported = &ReportedSession{o.Session, o.SessionStart, m.Captured, m.Sequence}
	}
	if q := m.Progress; q != nil && captureAge >= -liveClockTolerance(st.Policy) && captureAge <= float64(2*m.Config.Effective+5) {
		reported = &ReportedSession{q.Session, q.SessionStart, q.Measured, m.Sequence}
	}
	if reported != nil && (d.Session == nil || reported.Reported > d.Session.Reported || (reported.Reported == d.Session.Reported && reported.Envelope > d.Session.Envelope)) {
		d.Session = reported
	}
	if m.Progress != nil && (d.PhoneQueue == nil || m.Progress.Measured > d.PhoneQueue.Measured) {
		q := *m.Progress
		d.PhoneQueue = &q
	}
	if received > d.Contact {
		d.Contact = received
	}
	if !futureCapture(m, received, st.Policy) && (futureCapture(d.Snapshot, received, st.Policy) || less(d.Snapshot, m, false)) {
		d.Snapshot = m
	}
	if m.Fix != nil && !futureCandidate(m, received, st.Policy) && (d.Location == nil || futureCandidate(*d.Location, received, st.Policy) || lessLive(*d.Location, m)) {
		copy := m
		d.Location = &copy
		d.LocationReceived = first
	}
	if r := st.Recording; r != nil && r.Active && r.Mode == "session_beginning" {
		w := &r.Windows[len(r.Windows)-1]
		if w.Sessions == nil {
			w.Sessions = map[string]string{}
		}
		session, start := "", ""
		if d.Session != nil {
			session, start = d.Session.ID, d.Session.Start
		}
		if session != "" && w.Sessions[m.Device] == "" {
			w.Sessions[m.Device] = session
			if w.Reason == "start" && instant(start).Before(w.Start) {
				w.Start = instant(start)
			}
		}
	}
	refreshLive(st)
	if result == "stored" && m.Type == "sos" {
		st.Alerts = append(st.Alerts, alertFrom(m, first))
	}
	if result == "stored" && m.Type == "location" {
		if _, err = tx.Exec(`INSERT INTO projection_work(device,earliest) VALUES(?,?) ON CONFLICT(device) DO UPDATE SET earliest=MIN(earliest,excluded.earliest),generation=generation+1`, m.Device, m.Fix.Observed); err != nil {
			return Ack{}, err
		}
		st.RawVersion++
		st.ProjectionPending = true
	}
	evidence.ProjectionPending = st.ProjectionPending
	evidence.ProjectionRawVersion = st.RawVersion
	evidence.LiveAfter = liveObserved(d)
	evidence.UsefulAfter, evidence.DistanceAfter = trackEffect(*st, m.Device)
	offered := d.Desired
	evidence.Offered = &offered
	if err = writeEvidence(tx, evidence); err != nil {
		return Ack{}, err
	}
	return Ack{1, m.Device, m.ID, m.Sequence, result, first, d.Desired}, nil
}

func (s *Store) change(kind, device string, fn func(*State, time.Time, *sql.Tx) error) error {
	if kind == "policy_changed" || strings.HasPrefix(kind, "recording_") {
		s.projectionMu.Lock()
		if e := s.processProjectionLocked(); e != nil {
			s.recordProjectionFailure(e)
		}
		s.projectionMu.Unlock()
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
	before := recordingEvidence(st)
	now := s.Now().UTC()
	if err = fn(&st, now, tx); err != nil {
		return err
	}
	if kind == "policy_changed" || strings.HasPrefix(kind, "recording_") {
		st.ProjectionVersion++
		st.ProjectionPending = true
		for id := range st.Devices {
			if _, err = tx.Exec(`INSERT INTO projection_work(device,earliest) VALUES(?, '') ON CONFLICT(device) DO UPDATE SET earliest='',generation=generation+1`, id); err != nil {
				return err
			}
		}
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
	if err = tx.Commit(); err == nil {
		s.notifyProjection()
	}
	return err
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
	st, err := loadReceiptState(tx)
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
		if err = saveReceiptState(tx, st); err != nil {
			return err
		}
		return tx.Commit()
	}
	return fmt.Errorf("unknown SOS device/event pair")
}

func (s *Store) Action(action string) error { return s.ActionMode(action, "from_now") }
func (s *Store) ActionMode(action, mode string) error {
	return s.change("recording_"+action, "", func(st *State, now time.Time, tx *sql.Tx) error {
		r := st.Recording
		open := func(reason string) {
			r.Active = true
			w := Window{ID: id(), Start: now, Policy: st.Policy, Reason: reason}
			if r.Mode == "session_beginning" {
				w.Sessions = map[string]string{}
				for device, d := range st.Devices {
					if d.Session != nil {
						w.Sessions[device] = d.Session.ID
					}
				}
			}
			r.Windows = append(r.Windows, w)
		}
		switch action {
		case "start":
			if r != nil {
				if r.Active {
					return nil
				}
				return ErrAction
			}
			if mode != "from_now" && mode != "session_beginning" {
				return fmt.Errorf("unknown recording mode")
			}
			r = &Recording{ID: id(), Mode: mode}
			st.Recording = r
			open("start")
			if mode == "session_beginning" {
				w := &r.Windows[len(r.Windows)-1]
				w.Sessions = map[string]string{}
				for device, d := range st.Devices {
					if d.Session != nil {
						w.Sessions[device] = d.Session.ID
						t := instant(d.Session.Start)
						if t.Before(w.Start) {
							w.Start = t
						}
					}
				}

				if len(w.Sessions) == 0 {
					return fmt.Errorf("no reported Android tracking session; wait for session metadata")
				}
			}
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
			st.Points = []Point{}
			st.Decisions = []Decision{}
			for _, d := range st.Devices {
				d.Total = 0
			}
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
		// An explicit quality edit reprojects the whole current recording, including
		// stopped/resumed and legacy policy windows. Lifecycle boundaries remain.
		if r := st.Recording; r != nil {
			windows := []Window{}
			for _, w := range r.Windows {
				w.Policy = p
				// Old policy-only boundaries are not recording lifecycle breaks.
				if w.Reason == "policy_change" && len(windows) > 0 && windows[len(windows)-1].Stop != nil && windows[len(windows)-1].Stop.Equal(w.Start) {
					windows[len(windows)-1].Stop = w.Stop
				} else {
					windows = append(windows, w)
				}
			}
			r.Windows = windows
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
	refreshLive(&st)
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
				if locationAge > liveAgeLimit(st.Policy) {
					d.GNSSCondition = "stale"
				}
				if instant(d.Location.Captured).After(now.Add(time.Duration(liveClockTolerance(st.Policy)*float64(time.Second)))) || instant(d.Location.Fix.Observed).After(now.Add(time.Duration(liveClockTolerance(st.Policy)*float64(time.Second)))) {
					d.GNSSCondition = "clock_anomaly"
				}
			}
		}
		d.CurrentPosition = d.Location != nil && d.GNSSCondition == "valid" && d.LocationAge != nil && *d.LocationAge <= liveAgeLimit(st.Policy)
	}
	if err := populateHistory(tx, &st, now); err != nil {
		return st, err
	}
	if err := populateViews(tx, &st); err != nil {
		return st, err
	}
	// Use the same origin for qualified, unfiltered, provisional and live layers.
	st.Points = Present(st.Points, dot)
	var origin *Fix
	var firstPoint *Point
	all := append(append(append([]Point{}, st.Points...), st.RawPoints...), st.Provisional...)
	for i := range all {
		if firstPoint == nil || originLess(all[i], *firstPoint) {
			firstPoint = &all[i]
		}
	}
	if firstPoint != nil {
		origin = &firstPoint.Fix
	}
	ids := []string{}
	for id := range st.Devices {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		d := st.Devices[id]
		if origin == nil && d.CurrentPosition {
			origin = d.Location.Fix
		}
	}
	for _, d := range st.Devices {
		if origin != nil && d.CurrentPosition {
			x, y := Project(*d.Location.Fix, *origin)
			d.LiveX, d.LiveY = &x, &y
		}
	}
	if origin != nil {
		for i := range st.Points {
			st.Points[i].X, st.Points[i].Y = Project(st.Points[i].Fix, *origin)
		}
	}
	if origin != nil {
		for i := range st.RawPoints {
			st.RawPoints[i].X, st.RawPoints[i].Y = Project(st.RawPoints[i].Fix, *origin)
		}
		for i := range st.Provisional {
			st.Provisional[i].X, st.Provisional[i].Y = Project(st.Provisional[i].Fix, *origin)
		}
	}
	report, err := s.fieldReport(tx)
	if err != nil {
		return st, err
	}
	for id, d := range st.Devices {
		d.Evidence = report.Devices[id]
	}
	return st, nil
}
func mathMax(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}

func liveAgeLimit(p Policy) float64 {
	if p.on("maximum_fix_age_s") && p.Age < 30 {
		return p.Age
	}
	return 30 // Historical policy cannot manufacture current GNSS freshness.
}

func liveClockTolerance(p Policy) float64 {
	if p.on("clock_tolerance_s") && p.Clock < 5 {
		return p.Clock
	}
	return 5 // A historical switch cannot make future measurements current.
}

// Live source integrity is independent of historical movement/segment decisions.
func refreshLive(st *State) {
	for _, d := range st.Devices {
		d.CurrentPosition = false
		d.LiveX, d.LiveY = nil, nil
		if d.Location == nil {
			continue
		}
		d.LocationReason = Quality(*d.Location, st.Policy)
		if st.Policy.on("clock_tolerance_s") && d.LocationReceived != "" && instant(d.Location.Captured).After(instant(d.LocationReceived).Add(time.Duration(st.Policy.Clock*float64(time.Second)))) {
			d.LocationReason = "future_capture"
		}
	}
}

func writeObservationConflict(tx *sql.Tx, st *State, m Message, received, role, code, existing string) error {
	e := receiptEvidence(m, received, "", "conflict", st.Devices[m.Device])
	e.DeliveryRole = role
	e.FailureCode = code
	e.ConflictingMessage = existing
	return writeEvidence(tx, e)
}

// Retain clock-anomalous raw data without allowing its timestamp to pin the
// operational snapshot/marker ahead of later genuinely current observations.
func futureCapture(m Message, received string, p Policy) bool {
	return instant(m.Captured).Sub(instant(received)).Seconds() > liveClockTolerance(p)
}
func futureCandidate(m Message, received string, p Policy) bool {
	return futureCapture(m, received, p) || (m.Fix != nil && instant(m.Fix.Observed).Sub(instant(received)).Seconds() > liveClockTolerance(p))
}

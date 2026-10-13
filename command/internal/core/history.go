package core

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"sort"
	"time"
)

func validateObservation(v map[string]any) error {
	if v["observation"] != nil {
		o := object(v["observation"])
		if v["type"] != "location" || !uuid.MatchString(str(o["observation_id"])) || o["observation_id"] != v["message_id"] || !uuid.MatchString(str(o["tracking_session_id"])) || !stamp(o["session_started_at"]) || !num(o["observation_sequence"], 1, maxInteger, true) || !num(o["measurement_elapsed_ms"], 1, maxInteger, true) {
			return fmt.Errorf("invalid observation identity")
		}
	}
	if v["history_progress"] != nil {
		q := object(v["history_progress"])
		if !uuid.MatchString(str(q["tracking_session_id"])) || !stamp(q["session_started_at"]) || !stamp(q["measured_at"]) || !num(q["latest_committed_sequence"], 0, maxInteger, true) || !num(q["pending_observations"], 0, maxInteger, true) || !num(q["known_collection_loss"], 0, maxInteger, true) {
			return fmt.Errorf("invalid history progress")
		}
		if q["oldest_pending_sequence"] != nil && !num(q["oldest_pending_sequence"], 1, number(q["latest_committed_sequence"]), true) {
			return fmt.Errorf("invalid oldest pending sequence")
		}
		a, ok := q["unresolved_sequences"].([]any)
		if !ok {
			return fmt.Errorf("unresolved sequences required")
		}
		for _, v := range a {
			if !num(v, 1, number(q["latest_committed_sequence"]), true) {
				return fmt.Errorf("invalid unresolved sequence")
			}
		}
	}
	return nil
}

type HistoryState struct {
	Session           string         `json:"tracking_session_id"`
	ReceivedThrough   int64          `json:"received_through"`
	ProcessedThrough  int64          `json:"processed_through"`
	Received          int            `json:"received_observations"`
	LatestReceived    int64          `json:"latest_received_sequence"`
	LatestReported    *QueueProgress `json:"last_reported_phone_queue"`
	PhoneStateCurrent bool           `json:"phone_state_recent"`
	Condition         string         `json:"condition"`
}

func populateHistory(tx *sql.Tx, st *State, now time.Time) error {
	for _, d := range st.Devices {
		current := ""
		if d.Session != nil {
			current = d.Session.ID
		}
		if o := d.Snapshot.Observation; o != nil && current == "" {
			current = o.Session
		} else if q := d.Snapshot.Progress; q != nil && current == "" {
			current = q.Session
		} else if d.PhoneQueue != nil && current == "" {
			current = d.PhoneQueue.Session
		}
		d.HistorySessions = map[string]*HistoryState{}
		rows, e := tx.Query("SELECT session,sequence FROM observation_identity WHERE device=? ORDER BY session,sequence", d.ID)
		if e != nil {
			return e
		}
		sets := map[string]map[int64]bool{}
		for rows.Next() {
			var session string
			var n int64
			if e = rows.Scan(&session, &n); e != nil {
				break
			}
			if sets[session] == nil {
				sets[session] = map[int64]bool{}
			}
			sets[session][n] = true
		}
		if e == nil {
			e = rows.Err()
		}
		rows.Close()
		if e != nil {
			return e
		}
		if current != "" && sets[current] == nil {
			sets[current] = map[int64]bool{}
		}
		for session, seen := range sets {
			h := &HistoryState{Session: session, Condition: "unknown", Received: len(seen)}
			for n := range seen {
				if n > h.LatestReceived {
					h.LatestReceived = n
				}
			}
			for seen[h.ReceivedThrough+1] {
				h.ReceivedThrough++
			}
			h.ProcessedThrough = h.ReceivedThrough
			var known string
			e = tx.QueryRow(`SELECT known FROM raw WHERE device=? AND json_extract(known,'$.history_progress.tracking_session_id')=? ORDER BY json_extract(known,'$.history_progress.measured_at') DESC LIMIT 1`, d.ID, session).Scan(&known)
			if e != nil && e != sql.ErrNoRows {
				return e
			}
			if e == nil {
				var m Message
				if e = json.Unmarshal([]byte(known), &m); e != nil {
					return e
				}
				h.LatestReported = m.Progress
			}
			if q := h.LatestReported; q != nil {
				unresolved := map[int64]bool{}
				for _, n := range q.Unresolved {
					unresolved[n] = true
				}
				for seen[h.ProcessedThrough+1] || unresolved[h.ProcessedThrough+1] {
					h.ProcessedThrough++
				}
				age := now.Sub(instant(q.Measured)).Seconds()
				h.PhoneStateCurrent = age >= 0 && age <= float64(2*d.Snapshot.Config.Effective+5) && now.Sub(instant(d.Contact)).Seconds() <= float64(2*d.Snapshot.Config.Effective+5)
				if h.PhoneStateCurrent {
					h.Condition = "catching_up"
				}
				if len(unresolved) > 0 {
					h.Condition = "unresolved"
				} else if q.KnownLoss > 0 {
					h.Condition = "incomplete"
				} else if h.ReceivedThrough >= q.Latest && h.PhoneStateCurrent && q.Pending == 0 {
					h.Condition = "synchronized"
				}
			}
			d.HistorySessions[session] = h
		}
		d.History = d.HistorySessions[current]
		if d.History == nil {
			d.History = &HistoryState{Session: current, Condition: "unknown"}
		}
	}
	return nil
}

type projectionCheckpoint struct {
	At, Points, Decisions int
	State                 Derivation
}
type ProjectionCache struct {
	Raw         []Candidate
	Points      []Point
	Decisions   []Decision
	Checkpoints []projectionCheckpoint
	Base        float64
	Policy      Policy
}

func candidateEqual(a, b Candidate) bool { return a.ID == b.ID && a.Received == b.Received }
func (c *ProjectionCache) update(raw []Candidate, p Policy, window string, base float64) {
	if !sort.SliceIsSorted(raw, func(i, j int) bool { return less(raw[i].Message, raw[j].Message, true) }) {
		sort.Slice(raw, func(i, j int) bool { return less(raw[i].Message, raw[j].Message, true) })
	}
	i := 0
	for i < len(raw) && i < len(c.Raw) && candidateEqual(raw[i], c.Raw[i]) {
		i++
	}
	pb, _ := json.Marshal(p)
	cb, _ := json.Marshal(c.Policy)
	state := NewDerivation(base)
	start, pc, dc := 0, 0, 0
	if c.Base == base && string(pb) == string(cb) {
		for _, cp := range c.Checkpoints {
			if cp.At <= i {
				start, pc, dc, state = cp.At, cp.Points, cp.Decisions, cloneDerivation(cp.State)
			}
		}
	}
	points := append([]Point{}, c.Points[:pc]...)
	decisions := append([]Decision{}, c.Decisions[:dc]...)
	checkpoints := []projectionCheckpoint{}
	for _, cp := range c.Checkpoints {
		if cp.At <= start {
			checkpoints = append(checkpoints, cp)
		}
	}
	for j := start; j < len(raw); j++ {
		pp, dd := state.Feed(raw[j], p, window)
		points = append(points, pp...)
		decisions = append(decisions, dd...)
		if (j+1)%64 == 0 || j+1 == len(raw) {
			checkpoints = append(checkpoints, projectionCheckpoint{j + 1, len(points), len(decisions), cloneDerivation(state)})
		}
	}
	c.Raw, c.Points, c.Decisions, c.Checkpoints, c.Base, c.Policy = raw, points, decisions, checkpoints, base, p
}
func candidates(tx *sql.Tx, device string) ([]Candidate, error) {
	query := "SELECT known,received FROM raw WHERE json_extract(known,'$.type')='location'"
	args := []any{}
	if device != "" {
		query += " AND device=?"
		args = append(args, device)
	}
	rows, e := tx.Query(query, args...)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Candidate{}
	for rows.Next() {
		var b, r string
		if e = rows.Scan(&b, &r); e != nil {
			return nil, e
		}
		var m Message
		if e = json.Unmarshal([]byte(b), &m); e != nil {
			return nil, e
		}
		out = append(out, Candidate{m, r})
	}
	return out, rows.Err()
}

// Arrival cursor avoids re-reading/decoding every retained raw envelope for each
// receipt. In-memory only: SQLite raw remains the restart-safe source of truth.
type rawCursor struct {
	Row        int64
	Candidates []Candidate
}

func (s *Store) projectionCandidates(tx *sql.Tx, device string) ([]Candidate, error) {
	c := s.projectionRaw[device]
	if c == nil {
		c = &rawCursor{}
		s.projectionRaw[device] = c
	}
	rows, e := tx.Query("SELECT rowid,known,received FROM raw WHERE device=? AND rowid>? AND json_extract(known,'$.type')='location' ORDER BY rowid", device, c.Row)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	// Advance only complete rows; retry after a read error resumes safely.
	for rows.Next() {
		var n int64
		var b, r string
		if e = rows.Scan(&n, &b, &r); e != nil {
			return nil, e
		}
		var m Message
		if e = json.Unmarshal([]byte(b), &m); e != nil {
			return nil, e
		}
		v := Candidate{m, r}
		at := sort.Search(len(c.Candidates), func(i int) bool { return !less(c.Candidates[i].Message, m, true) })
		c.Candidates = append(c.Candidates, Candidate{})
		copy(c.Candidates[at+1:], c.Candidates[at:])
		c.Candidates[at] = v
		c.Row = n
	}
	return c.Candidates, rows.Err()
}
func windowIncludes(w Window, m Message) bool {
	t := instant(m.Fix.Observed)
	if t.Before(w.Start) || (w.Stop != nil && !t.Before(*w.Stop)) {
		return false
	}
	if w.Sessions != nil {
		return m.Observation != nil && w.Sessions[m.Device] == m.Observation.Session
	}
	return true
}
func reconstructCandidates(raw []Candidate, st *State, cache map[string]*ProjectionCache, full bool) error {
	st.Points = []Point{}
	st.Decisions = []Decision{}
	for _, d := range st.Devices {
		d.Total = 0
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
				if r.Device == device && windowIncludes(w, r.Message) {
					c = append(c, r)
				}
			}
			key := w.ID + "/" + device
			engine := &ProjectionCache{}
			if !full && cache[key] != nil {
				engine = cache[key]
			}
			engine.update(c, w.Policy, w.ID, st.Devices[device].Total)
			if cache != nil {
				cache[key] = engine
			}
			points := append([]Point{}, engine.Points...)
			if len(points) > 0 {
				points[0].Reason = w.Reason + ":" + points[0].Reason
				st.Devices[device].Total = points[len(points)-1].Distance
			}
			st.Points = append(st.Points, points...)
			st.Decisions = append(st.Decisions, engine.Decisions...)
		}
	}
	return nil
}
func (s *Store) notifyProjection() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}
func cloneDerivation(s Derivation) Derivation {
	s.Recent = append([]Point{}, s.Recent...)
	if s.Anchor != nil {
		a := *s.Anchor
		s.Anchor = &a
	}
	return s
}
func (s *Store) projectionLoop() {
	defer close(s.done)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			if err := s.processProjection(); err != nil {
				s.recordProjectionFailure(err)
			}
		case <-s.stop:
			return
		case <-s.wake:
			if err := s.processProjection(); err != nil {
				s.recordProjectionFailure(err)
			}
		}
	}
}

// Read and compute outside the ingestion mutex. Apply only an unchanged raw/policy
// generation; raw commits and SOS ACKs never wait for movement reconstruction.
func (s *Store) processProjection() error {
	if !s.projectionMu.TryLock() {
		return nil
	}
	defer s.projectionMu.Unlock()
	return s.processProjectionLocked()
}

// Test inspection/export boundaries may explicitly wait; ordinary live snapshots
// never wait on an in-flight history reconstruction.
func (s *Store) processProjectionLocked() error {
	s.mu.Lock()
	tx, e := s.db.Begin()
	if e != nil {
		s.mu.Unlock()
		return e
	}
	work := map[string]int64{}
	rows, e := tx.Query("SELECT device,generation FROM projection_work")
	if e != nil {
		tx.Rollback()
		s.mu.Unlock()
		return e
	}
	for rows.Next() {
		var device string
		var generation int64
		if e = rows.Scan(&device, &generation); e != nil {
			break
		}
		work[device] = generation
	}
	if e == nil {
		e = rows.Err()
	}
	rows.Close()
	if e != nil || len(work) == 0 {
		tx.Rollback()
		s.mu.Unlock()
		return e
	}
	st, e := loadState(tx)
	var raw []Candidate
	if e == nil {
		for device := range work {
			var rr []Candidate
			rr, e = s.projectionCandidates(tx, device)
			if e != nil {
				break
			}
			raw = append(raw, rr...)
		}
	}
	// Keep unaffected projections; compute only devices with durable pending work.
	previousPoints, previousDecisions := st.Points, st.Decisions
	allDevices := st.Devices
	affected := map[string]*Device{}
	for device := range work {
		if allDevices[device] != nil {
			affected[device] = allDevices[device]
		}
	}
	st.Devices = affected
	tx.Rollback()
	s.mu.Unlock()
	if e == nil {
		e = reconstructCandidates(raw, &st, s.projections, false)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	latest, err := loadState(tx)
	if err != nil {
		return err
	}
	if e != nil {
		latest.ProjectionPending = true
		latest.ProjectionError = e.Error()
		if err = saveState(tx, latest); err != nil {
			return err
		}
		if err = tx.Commit(); err != nil {
			return err
		}
		return e
	}
	if latest.ProjectionVersion != st.ProjectionVersion {
		s.notifyProjection()
		return nil
	}
	accepted := map[string]bool{}
	for device, generation := range work {
		var current int64
		if err = tx.QueryRow("SELECT generation FROM projection_work WHERE device=?", device).Scan(&current); err != nil {
			return err
		}
		if generation != current {
			continue
		}
		accepted[device] = true
		if d := st.Devices[device]; d != nil && latest.Devices[device] != nil {
			latest.Devices[device].Total = d.Total
		}
		if _, err = tx.Exec("DELETE FROM projection_work WHERE device=? AND generation=?", device, generation); err != nil {
			return err
		}
	}
	latest.Points = []Point{}
	latest.Decisions = []Decision{}
	for _, point := range previousPoints {
		if !accepted[point.Device] {
			latest.Points = append(latest.Points, point)
		}
	}
	for _, decision := range previousDecisions {
		if !accepted[decision.Device] {
			latest.Decisions = append(latest.Decisions, decision)
		}
	}
	for _, point := range st.Points {
		if accepted[point.Device] {
			latest.Points = append(latest.Points, point)
		}
	}
	for _, decision := range st.Decisions {
		if accepted[decision.Device] {
			latest.Decisions = append(latest.Decisions, decision)
		}
	}
	// Stable presentation order is recording-window order then device, as the oracle.
	order := map[string]int{}
	if latest.Recording != nil {
		for i, w := range latest.Recording.Windows {
			order[w.ID] = i
		}
	}
	sort.SliceStable(latest.Points, func(i, j int) bool {
		a, b := latest.Points[i], latest.Points[j]
		if order[a.Window] != order[b.Window] {
			return order[a.Window] < order[b.Window]
		}
		return a.Device < b.Device
	})
	sort.SliceStable(latest.Decisions, func(i, j int) bool {
		a, b := latest.Decisions[i], latest.Decisions[j]
		if order[a.Window] != order[b.Window] {
			return order[a.Window] < order[b.Window]
		}
		return a.Device < b.Device
	})
	var pending int
	if err = tx.QueryRow("SELECT COUNT(*) FROM projection_work").Scan(&pending); err != nil {
		return err
	}
	latest.ProjectionPending = pending > 0
	latest.ProjectionError = ""
	for device := range accepted {
		count, total := trackEffect(latest, device)
		event := Evidence{At: time.Now().UTC().Format(wireTime), Kind: "projection_updated", Device: device, UsefulAfter: count, DistanceAfter: total, ProjectionPending: latest.ProjectionPending, ProjectionRawVersion: latest.RawVersion}
		if err = writeEvidence(tx, event); err != nil {
			return err
		}
	}
	if pending > 0 {
		s.notifyProjection()
	}
	if err = saveState(tx, latest); err != nil {
		return err
	}
	return tx.Commit()
}
func populateViews(tx *sql.Tx, st *State) error {
	st.RawPoints = []Point{}
	st.Provisional = []Point{}
	if st.Recording == nil {
		return nil
	}
	raw, e := candidates(tx, "")
	if e != nil {
		return e
	}
	sort.Slice(raw, func(i, j int) bool { return less(raw[i].Message, raw[j].Message, true) })
	seqs := map[string]map[int64]bool{}
	through := map[string]int64{}
	identities := map[string]*ObservationIdentity{}
	for _, c := range raw {
		if o := c.Observation; o != nil {
			key := c.Device + "/" + o.Session
			if seqs[key] == nil {
				seqs[key] = map[int64]bool{}
			}
			seqs[key][o.Sequence] = true
			identities[c.Device+"/"+c.ID] = o
		}
	}
	for key, set := range seqs {
		for set[through[key]+1] {
			through[key]++
		}
	}
	// An explicitly unresolved protocol entry is a known outcome, not a
	// perpetual projection barrier. Receipt completeness stays separately false.
	for device, d := range st.Devices {
		for session, h := range d.HistorySessions {
			if h.ProcessedThrough > through[device+"/"+session] {
				through[device+"/"+session] = h.ProcessedThrough
			}
		}
	}
	filtered := []Point{}
	for _, point := range st.Points {
		o := identities[point.Device+"/"+point.Message]
		if o == nil || o.Sequence <= through[point.Device+"/"+o.Session] {
			filtered = append(filtered, point)
		}
	}
	st.Points = filtered
	for _, d := range st.Devices {
		d.Total = 0
	}
	for _, point := range filtered {
		if point.Distance > st.Devices[point.Device].Total {
			st.Devices[point.Device].Total = point.Distance
		}
	}
	for _, w := range st.Recording.Windows {
		for device := range st.Devices {
			group := []Candidate{}
			lastReceived := time.Time{}
			section := 0
			flush := func() {
				if len(group) > 0 {
					pp, _ := Derive(group, w.Policy, fmt.Sprintf("%s/provisional/%s/%d", w.ID, device, section), 0)
					origins := map[string]float64{}
					for i := range pp {
						if _, ok := origins[pp[i].Segment]; !ok {
							origins[pp[i].Segment] = pp[i].Distance
						}
						pp[i].Distance -= origins[pp[i].Segment]
						pp[i].Reason = "provisional"
						pp[i].Dot = true
					}
					st.Provisional = append(st.Provisional, pp...)
					group = nil
					section++
				}
			}
			for _, c := range raw {
				if c.Device != device || !windowIncludes(w, c.Message) {
					continue
				}
				st.RawPoints = append(st.RawPoints, Point{Device: device, Message: c.ID, Sequence: c.Sequence, Fix: *c.Fix, Window: w.ID, Segment: w.ID + "/" + device + "/" + sourceSession(c.Message), Reason: "unfiltered_diagnostic", Dot: true})
				if c.Observation == nil || c.Observation.Sequence <= through[device+"/"+c.Observation.Session] {
					continue
				}
				var role string
				_ = tx.QueryRow("SELECT role FROM receipt_roles WHERE device=? AND message=?", device, c.ID).Scan(&role)
				_, fresh := receiptQuality(c.Message, c.Received, st.Policy)
				if role != "live" || !fresh {
					continue
				}
				r := instant(c.Received)
				if !lastReceived.IsZero() && r.Sub(lastReceived) > time.Duration(4*c.Config.Effective+10)*time.Second {
					flush()
				}
				group = append(group, c)
				lastReceived = r
			}
			flush()
		}
	}
	return nil
}

func (s *Store) recordProjectionFailure(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, e := s.db.Begin()
	if e != nil {
		return
	}
	defer tx.Rollback()
	st, e := loadState(tx)
	if e != nil {
		return
	}
	st.ProjectionPending = true
	st.ProjectionError = err.Error()
	if saveState(tx, st) == nil {
		_ = tx.Commit()
	}
}

// An older v1 receiver retained extension fields in wire even when its canonical
// model ignored them. Recover only validated additive metadata, never rewrite wire.
func (s *Store) backfillObservationIdentity() error {
	tx, e := s.db.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	st, e := loadState(tx)
	if e != nil {
		return e
	}
	rows, e := tx.Query("SELECT wire,known FROM raw")
	if e != nil {
		return e
	}
	type update struct {
		message Message
		known   []byte
	}
	updates := []update{}
	for rows.Next() {
		var wire []byte
		var known string
		if e = rows.Scan(&wire, &known); e != nil {
			rows.Close()
			return e
		}
		m, err := Parse(wire)
		if err != nil || (m.Observation == nil && m.Progress == nil) {
			continue
		}
		var old Message
		if e = json.Unmarshal([]byte(known), &old); e != nil {
			rows.Close()
			return e
		}
		if (m.Observation != nil && old.Observation == nil) || (m.Progress != nil && old.Progress == nil) {
			canonical, _ := json.Marshal(m)
			updates = append(updates, update{m, canonical})
		}
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return e
	}
	for _, v := range updates {
		m := v.message
		o := m.Observation
		if o != nil {
			if _, e = tx.Exec("INSERT INTO observation_identity VALUES(?,?,?,?,?)", m.Device, o.Session, o.Sequence, o.ID, m.ID); e != nil {
				return e
			}
		}
		if _, e = tx.Exec("UPDATE raw SET known=? WHERE device=? AND message=?", string(v.known), m.Device, m.ID); e != nil {
			return e
		}
		if d := st.Devices[m.Device]; d != nil {
			if d.Location != nil && d.Location.ID == m.ID {
				copy := m
				d.Location = &copy
			}
			if d.Snapshot.ID == m.ID {
				d.Snapshot = m
			}
			if m.Progress != nil && (d.PhoneQueue == nil || m.Progress.Measured > d.PhoneQueue.Measured) {
				d.PhoneQueue = m.Progress
			}
		}
		if m.Observation != nil {
			if _, e = tx.Exec(`INSERT INTO projection_work(device,earliest) VALUES(?,?) ON CONFLICT(device) DO UPDATE SET earliest=MIN(earliest,excluded.earliest),generation=generation+1`, m.Device, m.Fix.Observed); e != nil {
				return e
			}
			st.RawVersion++
			st.ProjectionPending = true
		}
	}
	if e = saveState(tx, st); e != nil {
		return e
	}
	return tx.Commit()
}

func sourceSession(m Message) string {
	if m.Observation == nil {
		return "legacy"
	}
	return m.Observation.Session
}

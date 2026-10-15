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
	raw, err := candidates(tx, "")
	if err != nil {
		return err
	}
	return populateHistoryFromRaw(tx, st, now, raw)
}
func populateHistoryFromRaw(tx *sql.Tx, st *State, now time.Time, raw []Candidate) error {
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
		sets := map[string]map[int64]bool{}
		for _, c := range raw {
			if c.Device == d.ID && c.Observation != nil {
				o := c.Observation
				if sets[o.Session] == nil {
					sets[o.Session] = map[int64]bool{}
				}
				sets[o.Session][o.Sequence] = true
			}
		}
		var e error
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
			e = tx.QueryRow(`SELECT known FROM raw WHERE device=? AND json_extract(known,'$.history_progress') IS NOT NULL AND json_extract(known,'$.history_progress.tracking_session_id')=? ORDER BY json_extract(known,'$.history_progress.measured_at') DESC LIMIT 1`, d.ID, session).Scan(&known)
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
		c.Candidates = append(c.Candidates, v)
		c.Row = n
	}
	if !sort.SliceIsSorted(c.Candidates, func(i, j int) bool { return less(c.Candidates[i].Message, c.Candidates[j].Message, true) }) {
		sort.Slice(c.Candidates, func(i, j int) bool { return less(c.Candidates[i].Message, c.Candidates[j].Message, true) })
	}
	return c.Candidates, rows.Err()
}
func windowIncludes(w Window, m Message) bool {
	t := instant(m.Fix.Observed)
	if (!w.AllHistory && t.Before(w.Start)) || (w.Stop != nil && !t.Before(*w.Stop)) {
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
	if e != nil {
		tx.Rollback()
		s.mu.Unlock()
		return e
	}
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

// Render the authoritative projection, including independent qualified sections
// after sequence gaps. Transport prefixes must never gate successful derivation.
type ReconstructionState struct {
	Received       int            `json:"durably_received"`
	Eligible       int            `json:"eligible"`
	Excluded       int            `json:"excluded_by_boundaries"`
	Processed      int            `json:"projected"`
	Accepted       int            `json:"accepted"`
	Rejected       int            `json:"rejected"`
	Reasons        map[string]int `json:"rejection_reasons"`
	Qualified      int            `json:"qualified_points"`
	Segments       int            `json:"qualified_segments"`
	Missing        int64          `json:"known_missing_sequences"`
	Unresolved     int            `json:"reported_unresolved"`
	KnownLoss      int64          `json:"reported_collection_loss"`
	Earliest       string         `json:"earliest_received_observed_at,omitempty"`
	ClockUncertain int            `json:"clock_uncertain"`
	Projection     string         `json:"projection"`
	Error          string         `json:"projection_error,omitempty"`
	Route          string         `json:"route_condition"`
	Inventory      string         `json:"inventory_scope"`
}

func populateViews(tx *sql.Tx, st *State) error {
	raw, err := candidates(tx, "")
	if err != nil {
		return err
	}
	return populateViewsFromRaw(tx, st, raw, true)
}
func populateViewsFromRaw(tx *sql.Tx, st *State, raw []Candidate, diagnosticRaw bool) error {
	st.RawPoints = []Point{}
	st.Provisional = []Point{} // Retained API field; live markers are independent.
	pending := map[string]bool{}
	rows, err := tx.Query("SELECT device FROM projection_work")
	if err != nil {
		return err
	}
	for rows.Next() {
		var device string
		if err = rows.Scan(&device); err != nil {
			break
		}
		pending[device] = true
	}
	if err == nil {
		err = rows.Err()
	}
	rows.Close()
	if err != nil {
		return err
	}
	revisions := map[string]int64{}
	if st.Recording != nil {
		for _, w := range st.Recording.Windows {
			revisions[w.ID] = w.Policy.Revision
		}
	}
	members := map[string]bool{}
	segments := map[string]map[string]bool{}
	for device, d := range st.Devices {
		r := &ReconstructionState{Reasons: map[string]int{}, Projection: "completed", Inventory: "Only received observations and timestamped per-session reports are known; unreported retained sessions and offline backlog are unknown."}
		if pending[device] {
			r.Projection = "pending"
			if st.ProjectionError != "" {
				r.Projection = "failed"
				r.Error = st.ProjectionError
			}
		}
		for _, h := range d.HistorySessions {
			latest := h.LatestReceived
			if q := h.LatestReported; q != nil {
				if q.Latest > latest {
					latest = q.Latest
				}
				r.Unresolved += len(q.Unresolved)
				r.KnownLoss += q.KnownLoss
			}
			if latest > int64(h.Received) {
				r.Missing += latest - int64(h.Received)
			}
		}
		d.Reconstruction = r
		segments[device] = map[string]bool{}
	}
	for _, c := range raw {
		r := st.Devices[c.Device].Reconstruction
		r.Received++
		if r.Earliest == "" || c.Fix.Observed < r.Earliest {
			r.Earliest = c.Fix.Observed
		}
		if absClock(c.Message) > liveClockTolerance(st.Policy) || futureCandidate(c.Message, c.Received, st.Policy) {
			r.ClockUncertain++
		}
		included := false
		if st.Recording != nil {
			for _, w := range st.Recording.Windows {
				if !windowIncludes(w, c.Message) {
					continue
				}
				included = true
				members[w.ID+"/"+c.Device+"/"+c.ID] = true
				if diagnosticRaw {
					st.RawPoints = append(st.RawPoints, Point{Device: c.Device, Message: c.ID, Sequence: c.Sequence, Fix: *c.Fix, Window: w.ID, Segment: w.ID + "/" + c.Device + "/" + sourceSession(c.Message), Reason: "unfiltered_diagnostic", Dot: true})
				}
			}
		}
		if included {
			r.Eligible++
		} else {
			r.Excluded++
		}
	}
	for _, d := range st.Decisions {
		if !members[d.Window+"/"+d.Device+"/"+d.Message] || d.Revision != revisions[d.Window] {
			continue
		}
		r := st.Devices[d.Device].Reconstruction
		r.Processed++
		if d.Reason == "accepted" {
			r.Accepted++
		} else {
			r.Rejected++
			r.Reasons[d.Reason]++
		}
	}
	// A pending policy/Stop operation must not label obsolete qualification or
	// excluded measurements as current authoritative geometry. Keep cached source
	// results in SQLite for retry, but expose only the selected window/policy view.
	qualified := []Point{}
	for _, d := range st.Devices {
		d.Total = 0
		d.UsefulPoints = 0
	}
	for _, p := range st.Points {
		if members[p.Window+"/"+p.Device+"/"+p.Message] && p.Revision == revisions[p.Window] {
			qualified = append(qualified, p)
			d := st.Devices[p.Device]
			d.Reconstruction.Qualified++
			d.UsefulPoints++
			if p.Distance > d.Total {
				d.Total = p.Distance
			}
			segments[p.Device][p.Segment] = true
		}
	}
	st.Points = qualified
	for device, d := range st.Devices {
		r := d.Reconstruction
		r.Segments = len(segments[device])
		r.Route = "qualified_geometry"
		switch {
		case st.Recording == nil:
			r.Route = "no_recording"
		case r.Projection == "failed":
			r.Route = "projection_failed"
		case r.Projection == "pending":
			r.Route = "projection_pending"
		case r.Received == 0:
			r.Route = "no_observations_received"
		case r.Eligible == 0:
			r.Route = "excluded_by_recording_boundaries"
		case r.Processed != r.Eligible:
			r.Route = "projection_inconsistent"
		case r.Accepted == 0:
			r.Route = "quality_filtered"
		}
	}
	return nil
}

// Versioned non-destructive upgrade of the existing persisted JSON model. Keep
// lifecycle instants/IDs and raw bytes; invalidate only the derived projection.
func (s *Store) upgradeHistoryReconstruction() error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	st, err := loadState(tx)
	if err != nil {
		return err
	}
	if st.HistoryReconstructionVersion >= 1 {
		return nil
	}
	if r := st.Recording; r != nil && r.Mode == "session_beginning" {
		r.Mode = "from_beginning"
		for i := range r.Windows {
			r.Windows[i].Sessions = nil
			r.Windows[i].AllHistory = i == 0 && r.Windows[i].Reason == "start"
		}
	}
	st.HistoryReconstructionVersion = 1
	st.ProjectionVersion++
	for device := range st.Devices {
		if _, err = tx.Exec(`INSERT INTO projection_work(device,earliest) VALUES(?, '') ON CONFLICT(device) DO UPDATE SET earliest='',generation=generation+1`, device); err != nil {
			return err
		}
	}
	st.ProjectionPending = len(st.Devices) > 0
	if err = saveState(tx, st); err != nil {
		return err
	}
	return tx.Commit()
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

// The dashboard consumes only newly committed rows. This cache has no authority:
// SQLite is reread on restart, and quality/projection still run exclusively in the
// worker. It avoids JSON decoding every retained envelope on every two-second poll.
func (s *Store) viewCandidates(tx *sql.Tx, p Policy) ([]Candidate, error) {
	if s.dashboardCounters == nil {
		s.dashboardCounters = map[string]*Counters{}
	}
	rows, err := tx.Query(`SELECT r.rowid,r.known,r.received,COALESCE(rr.role,'') FROM raw r LEFT JOIN receipt_roles rr ON rr.device=r.device AND rr.message=r.message WHERE r.rowid>? ORDER BY r.rowid`, s.viewRaw.Row)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var row int64
		var known, received, role string
		if err = rows.Scan(&row, &known, &received, &role); err != nil {
			return nil, err
		}
		var m Message
		if err = json.Unmarshal([]byte(known), &m); err != nil {
			return nil, err
		}
		c := s.dashboardCounters[m.Device]
		if c == nil {
			c = &Counters{}
			s.dashboardCounters[m.Device] = c
		}
		c.Reports++
		c.LastReceipt = received
		switch m.Type {
		case "location":
			c.LocationReports++
			c.RawFixes++
		case "sos":
			c.SOSReports++
		default:
			c.RoutineReports++
		}
		class, _ := receiptClass(m, received, p)
		if class == "delayed_backlog" || role == "history" {
			c.DelayedReports++
			if m.Type == "location" {
				c.DelayedLocations++
			} else if m.Type == "status" {
				c.DelayedRoutine++
			}
		}
		if m.Type == "location" {
			v := Candidate{m, received}
			s.viewRaw.Candidates = append(s.viewRaw.Candidates, v)
		}
		s.viewRaw.Row = row
	}
	if !sort.SliceIsSorted(s.viewRaw.Candidates, func(i, j int) bool {
		return less(s.viewRaw.Candidates[i].Message, s.viewRaw.Candidates[j].Message, true)
	}) {
		sort.Slice(s.viewRaw.Candidates, func(i, j int) bool {
			return less(s.viewRaw.Candidates[i].Message, s.viewRaw.Candidates[j].Message, true)
		})
	}
	return s.viewRaw.Candidates, rows.Err()
}

// Cadence/retry summaries consume the existing evidence journal incrementally;
// exports retain full events and verdicts. No second persistent authority exists.
func (s *Store) populateDashboardEvidence(tx *sql.Tx, st *State, now time.Time) error {
	if s.dashboardReceipts == nil {
		s.dashboardReceipts = map[string][]Receipt{}
	}
	rows, err := tx.Query("SELECT ordinal,data FROM field_evidence WHERE ordinal>? ORDER BY ordinal", s.dashboardEvidenceRow)
	if err != nil {
		return err
	}
	for rows.Next() {
		var ordinal int64
		var b string
		if err = rows.Scan(&ordinal, &b); err != nil {
			break
		}
		var e Evidence
		if err = json.Unmarshal([]byte(b), &e); err != nil {
			break
		}
		e.Ordinal = ordinal
		if c := s.dashboardCounters[e.Device]; c != nil {
			if e.Kind == "duplicate" {
				c.Duplicates++
			}
			if e.Kind == "conflict" || (e.Kind == "batch_rejected" && e.FailureCode == "identity_conflict") {
				c.Conflicts++
			}
			if e.Kind == "stored" && e.DeliveryRole != "history" && e.TimingClass == "current_like" && (e.Type == "location" || e.Type == "status") {
				key := e.Device + "/" + e.Type
				sample := s.dashboardReceipts[key]
				if len(sample) == 0 || e.Captured > sample[len(sample)-1].Captured {
					sample = append(sample, Receipt{Evidence: e, Class: e.TimingClass})
					if len(sample) > 16 {
						sample = sample[len(sample)-16:]
					}
					s.dashboardReceipts[key] = sample
				}
			}
		}
		s.dashboardEvidenceRow = ordinal
	}
	if err == nil {
		err = rows.Err()
	}
	rows.Close()
	if err != nil {
		return err
	}
	report := FieldReport{Generated: now.UTC().Format(wireTime), Devices: map[string]*Counters{}}
	for device, d := range st.Devices {
		if c := s.dashboardCounters[device]; c != nil {
			copy := *c
			copy.Expected = d.Snapshot.Config.Effective
			copy.Useful = d.Reconstruction.Qualified
			copy.ProjectionReasons = d.Reconstruction.Reasons
			report.Devices[device] = &copy
		}
	}
	for _, sample := range s.dashboardReceipts {
		report.Receipts = append(report.Receipts, sample...)
	}
	sort.Slice(report.Receipts, func(i, j int) bool { return report.Receipts[i].Ordinal < report.Receipts[j].Ordinal })
	analyzeField(&report, st.Policy)
	for device, d := range st.Devices {
		d.Evidence = report.Devices[device]
	}
	return nil
}

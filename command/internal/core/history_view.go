package core

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

const dashboardPointLimit = 2048

// Both endpoints read the same receipt/cache authority. They never reconstruct.
// LIVE/SOS reads bypass the serialized full inspection/export connection.
func (s *Store) boundedDashboard(ctx context.Context, dot int, liveOnly bool) (State, bool, error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	db := s.viewDB
	if liveOnly && s.liveDB != nil {
		db = s.liveDB
	}
	if db == nil {
		db = s.db
		s.mu.Lock()
		defer s.mu.Unlock()
	}
	tx, err := db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return State{}, false, err
	}
	defer tx.Rollback()
	st, err := loadReceiptState(tx)
	if err != nil {
		return st, false, err
	}
	header, err := readProjectionHeader(tx)
	if err != nil {
		return st, false, err
	}
	total := 0
	var received int64
	for _, d := range st.Devices {
		received += d.DurableObservations
	}
	for _, count := range header.Counts {
		total += count
	}
	// Small existing views keep their exact diagnostic/evidence semantics. Large
	// views are explicitly sampled, with full geometry available by bounded page.
	chunks := 0
	for _, count := range header.Chunks {
		chunks += count
	}
	large := received > dashboardPointLimit || total > dashboardPointLimit || chunks > 4 || header.Chunked || header.LegacyLarge || len(header.Decisions) > dashboardPointLimit || len(header.Points) > dashboardPointLimit
	if !liveOnly && !large {
		tx.Rollback()
		if s.viewDB == nil {
			s.mu.Unlock()
			defer s.mu.Lock()
		}
		st, err = s.snapshot(dot, false, false)
		return st, false, err
	}
	refreshContactState(&st, s.Now())
	st.Points = []Point{}
	st.Decisions = nil
	st.RawPoints = []Point{}
	pending := map[string]bool{}
	rows, err := tx.QueryContext(ctx, "SELECT device FROM projection_work")
	if err != nil {
		return st, large, err
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
		return st, large, err
	}
	ids := make([]string, 0, len(st.Devices))
	for device := range st.Devices {
		ids = append(ids, device)
	}
	sort.Strings(ids)
	for _, device := range ids {
		d := st.Devices[device]
		if header.Versions[device] != st.ProjectionVersion {
			d.Total = 0
			d.UsefulPoints = 0
			d.Reconstruction = nil
		}
		if pending[device] {
			if d.Reconstruction == nil {
				d.Reconstruction = &ReconstructionState{Reasons: map[string]int{}, Received: int(d.DurableObservations), Inventory: "Only received devices known; offline/unreported backlog unknown"}
			}
			d.Reconstruction.Projection = "pending"
			d.Reconstruction.Route = "projection_pending"
			if st.ProjectionError != "" {
				d.Reconstruction.Projection = "failed"
				d.Reconstruction.Error = st.ProjectionError
			}
		}
		// Phone queue freshness is evaluated now, never inherited from cache time.
		for _, h := range d.HistorySessions {
			if h.LatestReported != nil {
				age := s.Now().Sub(instant(h.LatestReported.Measured)).Seconds()
				h.PhoneStateCurrent = age >= 0 && age <= float64(2*d.Snapshot.Config.Effective+5)
				if !h.PhoneStateCurrent && h.Condition == "synchronized" {
					h.Condition = "unknown"
				}
			}
		}
		if liveOnly {
			continue
		}
		preview := header.Preview[device]
		if generation := header.Generations[device]; generation != "" {
			var b string
			err = tx.QueryRowContext(ctx, "SELECT data FROM projection_device_chunks WHERE device=? AND generation=? AND ordinal=-1", device, generation).Scan(&b)
			if err != nil {
				return st, large, err
			}
			var part savedProjection
			if err = json.Unmarshal([]byte(b), &part); err != nil {
				return st, large, err
			}
			preview = part.Points
		}
		allowance := max(1, dashboardPointLimit/max(1, len(ids)))
		stride := max(1, (len(preview)+allowance-1)/allowance)
		for index, point := range preview {
			if index%stride != 0 {
				continue
			}
			if len(st.Points) >= dashboardPointLimit {
				break
			}
			if currentProjectionPoint(st, point) {
				st.Points = append(st.Points, point)
			}
		}
	}
	st.Points = Present(st.Points, dot)
	return st, large, nil
}
func currentProjectionPoint(st State, p Point) bool {
	if st.Recording == nil {
		return false
	}
	for _, w := range st.Recording.Windows {
		if w.ID == p.Window && w.Policy.Revision == p.Revision && (w.AllHistory || !instant(p.Fix.Observed).Before(w.Start)) && (w.Stop == nil || instant(p.Fix.Observed).Before(*w.Stop)) {
			return true
		}
	}
	return false
}

// Exact full-resolution inspection, 512 points/decisions at most. Cursor pins a
// published generation; a changed generation returns 409 rather than mixed pages.
type RetainedObservation struct {
	Message  Message `json:"message"`
	Received string  `json:"received_at"`
}
type HistoryPage struct {
	ProjectionPending bool                  `json:"projection_pending"`
	SourceRawVersion  int64                 `json:"source_raw_version"`
	Originals         []RetainedObservation `json:"original_observations,omitempty"`
	Points            []Point               `json:"points"`
	Decisions         []Decision            `json:"decisions"`
	Next              string                `json:"next_cursor,omitempty"`
	Generation        string                `json:"generation"`
	Authoritative     bool                  `json:"authoritative_geometry"`
}

var errHistoryChanged = errors.New("projection changed; restart inspection")

func (s *Store) historyPage(ctx context.Context, device, cursor string) (HistoryPage, error) {
	out := HistoryPage{Points: []Point{}, Decisions: []Decision{}, Authoritative: true}
	if !uuid.MatchString(device) {
		return out, fmt.Errorf("valid device_id required")
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	db := s.viewDB
	if db == nil {
		db = s.db
		s.mu.Lock()
		defer s.mu.Unlock()
	}
	tx, err := db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return out, err
	}
	defer tx.Rollback()
	header, err := readProjectionHeader(tx)
	if err != nil {
		return out, err
	}
	generation := header.Generations[device]
	out.Generation = generation
	if generation == "" {
		return out, fmt.Errorf("derived cache not available; reconstruction may be pending")
	}
	ordinal := 0
	if cursor != "" {
		parts := strings.Split(cursor, ":")
		if len(parts) != 2 || parts[0] != generation {
			return out, errHistoryChanged
		}
		ordinal, err = strconv.Atoi(parts[1])
		if err != nil || ordinal < 0 {
			return out, fmt.Errorf("invalid cursor")
		}
	}
	var b string
	err = tx.QueryRowContext(ctx, "SELECT data FROM projection_device_chunks WHERE device=? AND generation=? AND ordinal=?", device, generation, ordinal).Scan(&b)
	if err == sql.ErrNoRows {
		return out, nil
	}
	if err != nil {
		return out, err
	}
	var part savedProjection
	if err = json.Unmarshal([]byte(b), &part); err != nil {
		return out, err
	}
	out.Points, out.Decisions = part.Points, part.Decisions
	if ordinal+1 < header.Chunks[device] {
		out.Next = fmt.Sprintf("%s:%d", generation, ordinal+1)
	}
	// Obsolete quality/window geometry is not presented as current authority.
	st, err := loadReceiptState(tx)
	if err != nil {
		return out, err
	}
	if header.Versions[device] != st.ProjectionVersion {
		return HistoryPage{}, errHistoryChanged
	}
	out.ProjectionPending = st.ProjectionPending
	out.SourceRawVersion = header.RawVersions[device]

	for _, p := range out.Points {
		if !currentProjectionPoint(st, p) {
			return HistoryPage{}, errHistoryChanged
		}
	}
	for _, decision := range out.Decisions {
		valid := false
		if st.Recording != nil {
			for _, window := range st.Recording.Windows {
				if window.ID == decision.Window && window.Policy.Revision == decision.Revision {
					valid = true
					break
				}
			}
		}
		if !valid {
			return HistoryPage{}, errHistoryChanged
		}
	}
	return out, nil
}

// Original raw envelopes are available separately from presentation and qualified
// geometry. Sixteen individual-v1-sized envelopes bound the body to about 1 MiB.
func (s *Store) rawHistoryPage(ctx context.Context, device, cursor string) (HistoryPage, error) {
	out := HistoryPage{Points: []Point{}, Decisions: []Decision{}, Authoritative: false}
	if !uuid.MatchString(device) {
		return out, fmt.Errorf("valid device_id required")
	}
	after := int64(0)
	var err error
	if cursor != "" {
		after, err = strconv.ParseInt(cursor, 10, 64)
		if err != nil || after < 0 {
			return out, fmt.Errorf("invalid raw cursor")
		}
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	db := s.viewDB
	if db == nil {
		db = s.db
		s.mu.Lock()
		defer s.mu.Unlock()
	}
	tx, err := db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return out, err
	}
	defer tx.Rollback()
	st, err := loadReceiptState(tx)
	if err != nil {
		return out, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT known,received FROM raw WHERE device=? AND sequence>? AND json_extract(known,'$.type')='location' ORDER BY sequence LIMIT 16", device, after)
	if err != nil {
		return out, err
	}
	defer rows.Close()
	count := 0
	for rows.Next() {
		var b, received string
		if err = rows.Scan(&b, &received); err != nil {
			return out, err
		}
		var m Message
		if err = json.Unmarshal([]byte(b), &m); err != nil {
			return out, err
		}
		out.Originals = append(out.Originals, RetainedObservation{m, received})
		after = m.Sequence
		count++
		if st.Recording != nil {
			for _, w := range st.Recording.Windows {
				if windowIncludes(w, m) {
					out.Points = append(out.Points, Point{Device: device, Message: m.ID, Sequence: m.Sequence, Fix: *m.Fix, Window: w.ID, Segment: w.ID + "/" + device + "/" + sourceSession(m), Reason: "unfiltered_diagnostic", Dot: true})
				}
			}
		}
	}
	if err = rows.Err(); err != nil {
		return out, err
	}
	if count == 16 {
		out.Next = strconv.FormatInt(after, 10)
	}
	return out, nil
}

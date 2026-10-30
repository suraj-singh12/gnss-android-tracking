package core

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"
)

// TravelView is a consistent read of retained originals, independent of recording
// windows. Quality and distance use the same derivation as the normal projection.
// It is ephemeral reconstruction, never sealed presentation evidence.
type TravelView struct {
	ProjectionPending bool                         `json:"projection_pending"`
	ProjectionError   string                       `json:"projection_error,omitempty"`
	Contacts          map[string]string            `json:"contacts"`
	Styles            map[string]map[string]string `json:"styles"`
	Start             string                       `json:"start"`
	End               string                       `json:"end"`
	RetainedCount     int64                        `json:"retained_observation_count"`
	Ceiling           int64                        `json:"ceiling"`
	Policy            Policy                       `json:"policy"`
	Points            []Point                      `json:"points"`
	Decisions         []Decision                   `json:"decisions"`
	Originals         []RetainedObservation        `json:"originals"`
}

func (s *Store) travelView(ctx context.Context, start, end string) (TravelView, error) {
	out := TravelView{Start: start, End: end, Points: []Point{}, Decisions: []Decision{}, Originals: []RetainedObservation{}}
	a, err := time.Parse(time.RFC3339Nano, start)
	if err != nil {
		return out, fmt.Errorf("valid capture start required")
	}
	b, err := time.Parse(time.RFC3339Nano, end)
	if err != nil || !b.After(a) {
		return out, fmt.Errorf("capture end must follow start")
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
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
	out.Policy = st.Policy
	out.ProjectionPending = st.ProjectionPending
	out.ProjectionError = st.ProjectionError
	refreshContactState(&st, s.Now())
	out.Contacts = map[string]string{}
	out.Styles = map[string]map[string]string{}
	for id, device := range st.Devices {
		out.RetainedCount += device.DurableObservations
		out.Contacts[id] = device.ContactCondition
		out.Styles[id] = map[string]string{"track_color": device.Color, "track_dash": device.Dash}
	}
	if err = tx.QueryRowContext(ctx, "SELECT COALESCE(MAX(rowid),0) FROM raw").Scan(&out.Ceiling); err != nil {
		return out, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT known,received FROM raw WHERE json_extract(known,'$.type')='location' AND json_extract(known,'$.fix.observed_at')>=? AND json_extract(known,'$.fix.observed_at')<=?", a.UTC().Format(wireTime), b.UTC().Format(wireTime))
	if err != nil {
		return out, err
	}
	defer rows.Close()
	parties := map[string][]Candidate{}
	bytes := 0
	for rows.Next() {
		var data, received string
		if err = rows.Scan(&data, &received); err != nil {
			return out, err
		}
		var m Message
		if err = json.Unmarshal([]byte(data), &m); err != nil {
			return out, err
		}
		if m.Fix == nil || instant(m.Fix.Observed).Before(a) || instant(m.Fix.Observed).After(b) {
			continue
		}
		bytes += len(data)
		if bytes > 32<<20 {
			return out, fmt.Errorf("range exceeds interactive history budget; choose a smaller capture range")
		}
		parties[m.Device] = append(parties[m.Device], Candidate{m, received})
		out.Originals = append(out.Originals, RetainedObservation{m, received})
	}
	if err = rows.Err(); err != nil {
		return out, err
	}
	for _, raw := range parties {
		points, decisions := Derive(raw, out.Policy, "travel-back", 0)
		out.Points = append(out.Points, points...)
		out.Decisions = append(out.Decisions, decisions...)
	}
	return out, nil
}

// lastCredibleLocation consults original measurements rather than promoting a
// rejected latest report (or an unqualified SOS fix) to a credible position.
func (s *Store) lastCredibleLocation(ctx context.Context, device string) (*Fix, error) {
	if !uuid.MatchString(device) {
		return nil, fmt.Errorf("valid device_id required")
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	db := s.liveDB
	if db == nil {
		db = s.viewDB
	}
	if db == nil {
		db = s.db
		s.mu.Lock()
		defer s.mu.Unlock()
	}
	tx, err := db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	st, err := loadReceiptState(tx)
	if err != nil {
		return nil, err
	}
	now := s.Now().UTC().Format(wireTime)
	credible := func(m Message, received string) bool {
		return m.Fix != nil && Quality(m, st.Policy) == "valid" && !futureCandidate(m, now, st.Policy) && received != "" && !instant(m.Captured).After(instant(received).Add(time.Duration(st.Policy.Clock*float64(time.Second))))
	}
	if d := st.Devices[device]; d != nil && d.Location != nil && credible(*d.Location, d.LocationReceived) {
		return d.Location.Fix, nil
	}
	rows, err := tx.QueryContext(ctx, "SELECT known,received FROM raw WHERE device=? AND json_extract(known,'$.fix') IS NOT NULL ORDER BY json_extract(known,'$.fix.observed_at') DESC,sequence DESC", device)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var data, received string
		if err = rows.Scan(&data, &received); err != nil {
			return nil, err
		}
		var m Message
		if err = json.Unmarshal([]byte(data), &m); err != nil {
			return nil, err
		}
		if credible(m, received) {
			return m.Fix, nil
		}
	}
	return nil, rows.Err()
}

package core

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"
)

// SQLite's indexed order exactly matches less(..., observation=true). Each page
// closes its read transaction, so dense reconstruction cannot pin the WAL for an
// entire history. The captured raw row ceiling makes all pages one immutable set.
const chronologicalKey = `CASE WHEN json_extract(known,'$.observation') IS NULL THEN '0' ELSE '1' END || '|' || COALESCE(json_extract(known,'$.observation.session_started_at'),'') || '|' || COALESCE(json_extract(known,'$.observation.tracking_session_id'),'') || '|' || printf('%020d',COALESCE(json_extract(known,'$.observation.observation_sequence'),0)) || '|' || json_extract(known,'$.fix.observed_at') || '|' || printf('%020d',sequence) || '|' || message`

func candidateOrderKey(m Message) string {
	native, start, session, sequence := "0", "", "", int64(0)
	if m.Observation != nil {
		native = "1"
		start = m.Observation.SessionStart
		session = m.Observation.Session
		sequence = m.Observation.Sequence
	}
	return fmt.Sprintf("%s|%s|%s|%020d|%s|%020d|%s", native, start, session, sequence, m.Fix.Observed, m.Sequence, m.ID)
}

func (s *Store) streamCandidates(ctx context.Context, device string, ceiling int64, feed func(Candidate) error) error {
	var last *Message
	for {
		select {
		case <-s.stop:
			return fmt.Errorf("projection stopped")
		default:
		}
		page, err := func() ([]Candidate, error) {
			db := s.projectionDB
			if db == nil {
				db = s.db
				s.mu.Lock()
				defer s.mu.Unlock()
			}
			pageCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			defer cancel()
			tx, err := db.BeginTx(pageCtx, &sql.TxOptions{ReadOnly: true})
			if err != nil {
				return nil, err
			}
			defer tx.Rollback()
			query := `SELECT known,received FROM raw WHERE device=? AND rowid<=? AND json_extract(known,'$.type')='location'`
			args := []any{device, ceiling}
			if last != nil {
				query += ` AND (` + chronologicalKey + `)>?`
				args = append(args, candidateOrderKey(*last))
			}
			query += ` ORDER BY ` + chronologicalKey + ` LIMIT 512`
			rows, err := tx.QueryContext(pageCtx, query, args...)
			if err != nil {
				return nil, err
			}
			defer rows.Close()
			out := make([]Candidate, 0, 512)
			for rows.Next() {
				var b, received string
				if err = rows.Scan(&b, &received); err != nil {
					return nil, err
				}
				var m Message
				if err = json.Unmarshal([]byte(b), &m); err != nil {
					return nil, err
				}
				out = append(out, Candidate{m, received})
			}
			return out, rows.Err()
		}()
		if err != nil {
			return err
		}
		if len(page) == 0 {
			return nil
		}
		for _, candidate := range page {
			if err = feed(candidate); err != nil {
				return err
			}
		}
		m := page[len(page)-1].Message
		last = &m
	}
}

func (s *Store) streamProjection(st *State, device string, ceiling int64) (preparedDeviceProjection, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	p := preparedDeviceProjection{generation: id(), version: st.ProjectionVersion, rawVersion: st.RawVersion}
	d := st.Devices[device]
	d.Total, d.UsefulPoints = 0, 0
	d.HistorySessions = map[string]*HistoryState{}
	r := &ReconstructionState{Reasons: map[string]int{}, Projection: "pending", Route: "projection_pending", Inventory: "Only received observations and timestamped reports are known; offline inventory unknown"}
	d.Reconstruction = r
	current := ""
	if d.Session != nil {
		current = d.Session.ID
	} else if d.PhoneQueue != nil {
		current = d.PhoneQueue.Session
	}
	unresolved := map[string]map[int64]bool{}
	stats := func(c Candidate) error {
		r.Received++
		if r.Earliest == "" || c.Fix.Observed < r.Earliest {
			r.Earliest = c.Fix.Observed
		}
		if absClock(c.Message) > liveClockTolerance(st.Policy) || futureCandidate(c.Message, c.Received, st.Policy) {
			r.ClockUncertain++
		}
		if c.Observation == nil {
			return nil
		}
		o := c.Observation
		h := d.HistorySessions[o.Session]
		if h == nil {
			h = &HistoryState{Session: o.Session, Condition: "unknown"}
			d.HistorySessions[o.Session] = h
			// One bounded indexed report lookup per session, outside any page reader.
			var b string
			db := s.projectionDB
			if db == nil {
				db = s.db
				s.mu.Lock()
			}
			err := db.QueryRowContext(ctx, `SELECT known FROM raw WHERE device=? AND json_extract(known,'$.history_progress.tracking_session_id')=? ORDER BY json_extract(known,'$.history_progress.measured_at') DESC LIMIT 1`, device, o.Session).Scan(&b)
			if s.projectionDB == nil {
				s.mu.Unlock()
			}
			if err != nil && err != sql.ErrNoRows {
				return err
			}
			if err == nil {
				var m Message
				if err = json.Unmarshal([]byte(b), &m); err != nil {
					return err
				}
				h.LatestReported = m.Progress
			}
			unresolved[o.Session] = map[int64]bool{}
			if h.LatestReported != nil {
				for _, n := range h.LatestReported.Unresolved {
					unresolved[o.Session][n] = true
				}
			}
		}
		h.Received++
		h.LatestReceived = max(h.LatestReceived, o.Sequence)
		if o.Sequence == h.ReceivedThrough+1 {
			h.ReceivedThrough++
		}
		for unresolved[o.Session][h.ProcessedThrough+1] {
			h.ProcessedThrough++
		}
		if o.Sequence == h.ProcessedThrough+1 {
			h.ProcessedThrough++
		}
		return nil
	}
	// Source counts are independent of window eligibility.
	if err := s.streamCandidates(ctx, device, ceiling, stats); err != nil {
		return p, err
	}
	points, decisions := []Point{}, []Decision{}
	flush := func() error {
		if len(points) == 0 && len(decisions) == 0 {
			return nil
		}
		b, err := json.Marshal(savedProjection{Points: points, Decisions: decisions})
		if err != nil {
			return err
		}
		s.mu.Lock()
		_, err = s.db.Exec("INSERT INTO projection_device_chunks VALUES(?,?,?,?)", device, p.generation, p.stagedChunks, string(b))
		s.mu.Unlock()
		if err != nil {
			return err
		}
		p.stagedChunks++
		points, decisions = []Point{}, []Decision{}
		return nil
	}
	stride := 1
	if st.Recording != nil {
		for _, w := range st.Recording.Windows {
			state := NewDerivation(d.Total)
			first := true
			segment := ""
			err := s.streamCandidates(ctx, device, ceiling, func(c Candidate) error {
				if !windowIncludes(w, c.Message) {
					return nil
				}
				r.Eligible++
				pp, dd := state.Feed(c, w.Policy, w.ID)
				for i := range pp {
					if first {
						pp[i].Reason = w.Reason + ":" + pp[i].Reason
						first = false
					}
					if segment != pp[i].Segment {
						r.Segments++
						segment = pp[i].Segment
					}
					if p.count%stride == 0 {
						p.preview = append(p.preview, pp[i])
						if len(p.preview) > 256 {
							compact := make([]Point, 0, 129)
							for j := 0; j < len(p.preview); j += 2 {
								compact = append(compact, p.preview[j])
							}
							p.preview = compact
							stride *= 2
						}
					}
					p.count++
				}
				for _, decision := range dd {
					r.Processed++
					if decision.Reason == "accepted" {
						r.Accepted++
					} else {
						r.Rejected++
						r.Reasons[decision.Reason]++
					}
				}
				points = append(points, pp...)
				decisions = append(decisions, dd...)
				if len(points) >= 512 || len(decisions) >= 512 {
					return flush()
				}
				return nil
			})
			if err != nil {
				return p, err
			}
			d.Total = state.Total
		}
	}
	if err := flush(); err != nil {
		return p, err
	}
	d.UsefulPoints = p.count
	r.Qualified = p.count
	r.Excluded = r.Received - r.Eligible
	for _, h := range d.HistorySessions {
		for unresolved[h.Session][h.ProcessedThrough+1] {
			h.ProcessedThrough++
		}
		latest := h.LatestReceived
		if q := h.LatestReported; q != nil {
			latest = max(latest, q.Latest)
			r.Unresolved += q.unresolvedCount()
			r.KnownLoss += q.KnownLoss
			age := instant(d.Contact).Sub(instant(q.Measured)).Seconds()
			h.PhoneStateCurrent = age >= 0 && age <= float64(2*d.Snapshot.Config.Effective+5)
			if h.PhoneStateCurrent {
				h.Condition = "catching_up"
			}
			if q.unresolvedCount() > 0 {
				h.Condition = "unresolved"
			} else if q.KnownLoss > 0 {
				h.Condition = "incomplete"
			} else if h.ReceivedThrough >= q.Latest && h.PhoneStateCurrent && q.Pending == 0 {
				h.Condition = "synchronized"
			}
		}
		if latest > int64(h.Received) {
			r.Missing += latest - int64(h.Received)
		}
	}
	if err := s.stageProjectionPreview(device, p); err != nil {
		return p, err
	}
	d.History = d.HistorySessions[current]
	if d.History == nil {
		d.History = &HistoryState{Session: current, Condition: "unknown"}
	}
	return p, nil
}

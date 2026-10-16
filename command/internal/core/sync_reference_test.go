package core

import (
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"reflect"
	"testing"
	"time"
)

// Streaming acceptance oracle for sync-capacity.cjs's uniform one-window corpus.
// It independently orders original SQLite rows by their immutable source sequence,
// then compares EVERY full-resolution point/decision/unrounded distance using the
// existing authoritative derivation. No full-history arrays or browser samples.
func TestSyncStoredChronologicalReference(t *testing.T) {
	path := os.Getenv("GNSS_SYNC_REFERENCE_DB")
	if path == "" {
		t.Skip("explicit completed process-capacity database required")
	}
	db, err := sql.Open("sqlite3", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	db.SetMaxOpenConns(4)
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	st, err := loadReceiptState(tx)
	if err != nil {
		t.Fatal(err)
	}
	header, err := readProjectionHeader(tx)
	tx.Rollback()
	if err != nil {
		t.Fatal(err)
	}
	if st.ProjectionPending || st.ProjectionError != "" || st.Recording == nil || len(st.Recording.Windows) != 1 || !st.Recording.Windows[0].AllHistory {
		t.Fatal("oracle requires completed uniform From Beginning fixture")
	}
	window := st.Recording.Windows[0]
	raw, err := db.Query("SELECT known,received FROM raw WHERE json_extract(known,'$.type')='location' ORDER BY device,sequence")
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	type cursor struct {
		rows      *sql.Rows
		points    []Point
		decisions []Decision
	}
	openCursor := func(device string) *cursor {
		rows, e := db.Query("SELECT data FROM projection_device_chunks WHERE device=? AND generation=? AND ordinal>=0 ORDER BY ordinal", device, header.Generations[device])
		if e != nil {
			t.Fatal(e)
		}
		return &cursor{rows: rows}
	}
	advance := func(c *cursor, points bool) bool {
		for (points && len(c.points) == 0) || (!points && len(c.decisions) == 0) {
			if !c.rows.Next() {
				if e := c.rows.Err(); e != nil {
					t.Fatal(e)
				}
				return false
			}
			var b string
			if e := c.rows.Scan(&b); e != nil {
				t.Fatal(e)
			}
			var part savedProjection
			if e := json.Unmarshal([]byte(b), &part); e != nil {
				t.Fatal(e)
			}
			c.points, c.decisions = part.Points, part.Decisions
		}
		return true
	}
	var pointCursor, decisionCursor *cursor
	device := ""
	state := NewDerivation(0)
	first := true
	observations, points, decisions := 0, 0, 0
	finish := func() {
		if pointCursor != nil {
			if advance(pointCursor, true) || advance(decisionCursor, false) {
				t.Fatal("unexpected stored projection suffix", device)
			}
			pointCursor.rows.Close()
			decisionCursor.rows.Close()
			if state.Total != st.Devices[device].Total {
				t.Fatal("unrounded total differs", device, state.Total, st.Devices[device].Total)
			}
		}
	}
	defer func() {
		if pointCursor != nil {
			pointCursor.rows.Close()
			decisionCursor.rows.Close()
		}
	}()
	for raw.Next() {
		var b, received string
		if err = raw.Scan(&b, &received); err != nil {
			t.Fatal(err)
		}
		var m Message
		if err = json.Unmarshal([]byte(b), &m); err != nil {
			t.Fatal(err)
		}
		if m.Device != device {
			finish()
			device = m.Device
			state = NewDerivation(0)
			first = true
			pointCursor, decisionCursor = openCursor(device), openCursor(device)
		}
		observations++
		if !windowIncludes(window, m) {
			continue
		}
		pp, dd := state.Feed(Candidate{m, received}, window.Policy, window.ID)
		for _, p := range pp {
			if first {
				p.Reason = window.Reason + ":" + p.Reason
				first = false
			}
			if !advance(pointCursor, true) || !reflect.DeepEqual(p, pointCursor.points[0]) {
				t.Fatal("qualified point/segment/distance differs", device, m.ID)
			}
			pointCursor.points = pointCursor.points[1:]
			points++
		}
		for _, d := range dd {
			if !advance(decisionCursor, false) || !reflect.DeepEqual(d, decisionCursor.decisions[0]) {
				t.Fatal("quality decision differs", device, m.ID)
			}
			decisionCursor.decisions = decisionCursor.decisions[1:]
			decisions++
		}
	}
	if err = raw.Err(); err != nil {
		t.Fatal(err)
	}
	finish()
	fmt.Printf("REFERENCE VERIFIED original_observations=%d qualified_points=%d decisions=%d exact_distances=true\n", observations, points, decisions)
}

// Opt-in fixture-only cold-cache measurement. Raw receipts, recording windows and
// policy stay unchanged. Run the independent chronological oracle afterwards.
func TestSyncStoredColdReconstruction(t *testing.T) {
	path := os.Getenv("GNSS_SYNC_REFERENCE_DB")
	if path == "" {
		t.Skip("explicit completed process-capacity fixture required")
	}
	opened := time.Now()
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	startup := time.Since(opened)
	defer s.Close()
	fingerprint := func() ([32]byte, error) {
		h := sha256.New()
		rows, e := s.projectionDB.Query("SELECT device,message,wire,known FROM raw ORDER BY device,sequence")
		if e != nil {
			return [32]byte{}, e
		}
		defer rows.Close()
		for rows.Next() {
			var device, message, wire, known string
			if e = rows.Scan(&device, &message, &wire, &known); e != nil {
				return [32]byte{}, e
			}
			for _, value := range []string{device, message, wire, known} {
				io.WriteString(h, value)
				h.Write([]byte{0})
			}
		}
		var out [32]byte
		copy(out[:], h.Sum(nil))
		return out, rows.Err()
	}
	before, err := fingerprint()
	if err != nil {
		t.Fatal(err)
	}
	s.projectionMu.Lock()
	defer s.projectionMu.Unlock()
	err = func() error {
		s.mu.Lock()
		defer s.mu.Unlock()
		tx, e := s.db.Begin()
		if e != nil {
			return e
		}
		defer tx.Rollback()
		st, e := loadReceiptState(tx)
		if e != nil {
			return e
		}
		if st.ProjectionPending || st.Recording == nil || len(st.Recording.Windows) != 1 || !st.Recording.Windows[0].AllHistory {
			return fmt.Errorf("completed uniform fixture required")
		}
		if _, e = tx.Exec("DELETE FROM projection_device_chunks"); e != nil {
			return e
		}
		if e = writeProjectionHeader(tx, newProjectionHeader()); e != nil {
			return e
		}
		if _, e = tx.Exec(`INSERT INTO projection_work(device,earliest,generation,last_served) SELECT device,MIN(json_extract(known,'$.fix.observed_at')),0,0 FROM raw WHERE json_extract(known,'$.type')='location' GROUP BY device`); e != nil {
			return e
		}
		st.ProjectionPending = true
		if e = saveReceiptState(tx, st); e != nil {
			return e
		}
		return tx.Commit()
	}()
	if err != nil {
		t.Fatal(err)
	}
	began := time.Now()
	if err = s.processProjectionLocked(); err != nil {
		t.Fatal(err)
	}
	elapsed := time.Since(began)
	var pending int
	if err = s.db.QueryRow("SELECT COUNT(*) FROM projection_work").Scan(&pending); err != nil || pending != 0 {
		t.Fatal("cold rebuild incomplete", pending, err)
	}
	after, err := fingerprint()
	if err != nil || before != after {
		t.Fatal("cold rebuild changed original raw bytes", err)
	}
	fmt.Printf("COLD RECONSTRUCTION VERIFIED startup_ms=%.3f full_reconstruction_ms=%.3f raw_unchanged=true\n", float64(startup)/float64(time.Millisecond), float64(elapsed)/float64(time.Millisecond))
}

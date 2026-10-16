package core

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"testing"
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

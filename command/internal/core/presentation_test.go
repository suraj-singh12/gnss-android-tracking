package core

import (
	"encoding/json"
	"fmt"
	"sync"
	"testing"
	"time"
)

func claimPresentation(t *testing.T, s *Store) presentationRequest {
	t.Helper()
	v, e := s.presentationOperation("claim", presentationRequest{})
	if e != nil {
		t.Fatal(e)
	}
	l := v.(presentationLease)
	return presentationRequest{Token: l.Token, Epoch: l.Epoch}
}
func confirmPresentation(t *testing.T, s *Store, r presentationRequest, revision int64, now time.Time) presentationRequest {
	t.Helper()
	r.Revision = revision
	r.Presented = now.Format(time.RFC3339Nano)
	r.Opportunity = r.Presented
	r.State = displayState{Version: 1, Common: json.RawMessage(`{"viewport":[0,0,1,1],"layers":{"map":false}}`), Parties: map[string]json.RawMessage{device: json.RawMessage(`{"visible":true,"svg":"<circle cx='2'/>"}`)}}
	if _, e := s.presentationOperation("confirm", r); e != nil {
		t.Fatal(e)
	}
	return r
}
func TestPresentationLifecycleFrozenSubset(t *testing.T) {
	s, now, path := open(t)
	r := claimPresentation(t, s)
	for i := 0; i < 5; i++ {
		m := message(t, 1, 0, float64(i), 0, 1)
		m.Device = fmt.Sprintf("%08d-1111-4111-8111-111111111111", i+1)
		ingest(t, s, m)
	}
	action(t, s, "start")
	r = confirmPresentation(t, s, r, 1, *now)
	r.Name = "Subset"
	r.Included = []string{device}
	if _, e := s.presentationOperation("save", r); e == nil {
		t.Fatal("unknown participant saved")
	}
	r.Included = []string{"00000001-1111-4111-8111-111111111111", "00000002-1111-4111-8111-111111111111", "00000003-1111-4111-8111-111111111111"}
	v, e := s.presentationOperation("save", r)
	if e != nil {
		t.Fatal(e)
	}
	meta := v.(map[string]any)
	sid := meta["id"].(string)
	if meta["total_parties"] != 5 || meta["confirmed_events"] != 1 || meta["operator_selected_subset"] != true {
		t.Fatal(meta)
	}
	var before string
	if e = s.db.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=?`, sid).Scan(&before); e != nil {
		t.Fatal(e)
	}
	var originals int
	if e = s.db.QueryRow(`SELECT COUNT(*) FROM saved_originals WHERE snapshot=?`, sid).Scan(&originals); e != nil || originals != 3 {
		t.Fatal(originals, e)
	}
	for i := 0; i < 3; i++ {
		*now = now.Add(time.Second)
		action(t, s, "pause")
		if _, e = s.presentationOperation("save", r); e != nil {
			t.Fatal(e)
		}
		action(t, s, "resume")
	}
	*now = now.Add(time.Second)
	action(t, s, "stop")
	if e = s.Action("resume"); e == nil {
		t.Fatal("final Stop resumed")
	}
	action(t, s, "discard")
	ingest(t, s, message(t, 8, 3, 10, 0, 1))
	var after string
	_ = s.db.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=?`, sid).Scan(&after)
	if before != after {
		t.Fatal("snapshot mutated")
	}
	if _, e = s.db.Exec(`UPDATE saved_recordings SET metadata='{}' WHERE id=?`, sid); e == nil {
		t.Fatal("immutable header changed")
	}
	s.Close()
	reopened, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	_ = reopened.db.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=?`, sid).Scan(&after)
	if before != after {
		t.Fatal("restart mutated snapshot")
	}
}
func TestPresentationOwnershipOrderingAndCommitFailure(t *testing.T) {
	s, now, _ := open(t)
	var wg sync.WaitGroup
	wins := make(chan presentationRequest, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			v, e := s.presentationOperation("claim", presentationRequest{})
			if e == nil {
				l := v.(presentationLease)
				wins <- presentationRequest{Token: l.Token, Epoch: l.Epoch}
			}
		}()
	}
	wg.Wait()
	close(wins)
	if len(wins) != 1 {
		t.Fatal("exclusive claim", len(wins))
	}
	r := <-wins
	action(t, s, "start")
	r = confirmPresentation(t, s, r, 1, *now)
	if _, e := s.presentationOperation("confirm", r); e != nil {
		t.Fatal("duplicate failed", e)
	}
	r.State.Common = json.RawMessage(`{"conflict":true}`)
	if _, e := s.presentationOperation("confirm", r); e == nil {
		t.Fatal("sequence conflict accepted")
	}
	r = confirmPresentation(t, s, r, 3, *now)
	if _, e := s.db.Exec(`CREATE TRIGGER fail_presentation BEFORE INSERT ON presentation_journal BEGIN SELECT RAISE(ABORT,'injected disk failure'); END`); e != nil {
		t.Fatal(e)
	}
	r.Revision = 4
	if _, e := s.presentationOperation("confirm", r); e == nil {
		t.Fatal("failed durable write claimed success")
	}
	var count int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM presentation_journal WHERE kind='display'`).Scan(&count)
	if count != 2 {
		t.Fatal(count)
	}
	_, _ = s.db.Exec(`DROP TRIGGER fail_presentation`)
	*now = now.Add(16 * time.Second)
	newOwner := claimPresentation(t, s)
	if _, e := s.presentationOperation("heartbeat", r); e == nil {
		t.Fatal("stale owner remained valid")
	}
	if _, e := s.presentationOperation("heartbeat", newOwner); e != nil {
		t.Fatal(e)
	}
}
func TestPresentationRecoveryPreservesIntention(t *testing.T) {
	for _, state := range []string{"active", "paused", "stopped"} {
		t.Run(state, func(t *testing.T) {
			s, now, path := open(t)
			r := claimPresentation(t, s)
			action(t, s, "start")
			confirmPresentation(t, s, r, 1, *now)
			if state == "paused" {
				action(t, s, "pause")
			}
			if state == "stopped" {
				action(t, s, "stop")
			}
			s.Close()
			next, e := Open(path)
			if e != nil {
				t.Fatal(e)
			}
			defer next.Close()
			st := snapshot(t, next)
			if st.Recording.Status != state || st.Recording.Active != (state == "active") {
				t.Fatal(st.Recording)
			}
			claimPresentation(t, next)
			if _, e = next.presentationOperation("confirm", r); e == nil {
				t.Fatal("restart retained unfenced owner")
			}
		})
	}
}
func TestPresentationPinnedMapSurvivesDelete(t *testing.T) {
	s, now, _ := open(t)
	r := claimPresentation(t, s)
	action(t, s, "start")
	_, e := s.db.Exec(`INSERT INTO offline_maps VALUES('map','fixture','{"type":"FeatureCollection","features":[]}','now')`)
	if e != nil {
		t.Fatal(e)
	}
	r = confirmPresentation(t, s, r, 1, *now)
	r.Revision = 2
	r.State.MapID = "map"
	h, err := s.pinServedAsset([]byte(`{"type":"FeatureCollection","features":[]}`))
	if err != nil {
		t.Fatal(err)
	}
	r.State.Assets = []string{h}
	if _, e = s.presentationOperation("confirm", r); e != nil {
		t.Fatal(e)
	}
	_, _ = s.db.Exec(`DELETE FROM offline_maps WHERE id='map'`)
	var n int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM presentation_assets`).Scan(&n)
	if n != 1 {
		t.Fatal("asset not retained", n)
	}
}

func TestPresentationSavePublicationFailureAndRetry(t *testing.T) {
	s, now, _ := open(t)
	r := claimPresentation(t, s)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	action(t, s, "start")
	r = confirmPresentation(t, s, r, 1, *now)
	r.Name = "Retry"
	r.RequestID = "stable-request"
	r.Included = []string{device}
	_, e := s.db.Exec(`CREATE TRIGGER fail_seal BEFORE INSERT ON saved_recordings BEGIN SELECT RAISE(ABORT,'injected seal failure'); END`)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.presentationOperation("save", r); e == nil {
		t.Fatal("failed publication reported success")
	}
	var n int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM saved_recordings`).Scan(&n)
	if n != 0 {
		t.Fatal("partial snapshot published")
	}
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM saved_originals`).Scan(&n)
	if n != 0 {
		t.Fatal("failed staging not reclaimed")
	}
	_, _ = s.db.Exec(`DROP TRIGGER fail_seal`)
	first, e := s.presentationOperation("save", r)
	if e != nil {
		t.Fatal(e)
	}
	second, e := s.presentationOperation("save", r)
	if e != nil {
		t.Fatal(e)
	}
	if first.(map[string]any)["id"] != second.(map[string]any)["id"] {
		t.Fatal("lost response duplicated Save")
	}
}
func TestPresentationVariableFleetAndLateJoin(t *testing.T) {
	for _, fleet := range []int{1, 5, 21} {
		t.Run(fmt.Sprint(fleet), func(t *testing.T) {
			s, now, _ := open(t)
			r := claimPresentation(t, s)
			action(t, s, "start")
			r.Included = nil
			for d := 0; d < fleet; d++ {
				m := message(t, 1, float64(-d*86400), 0, 0, 1)
				m.Device = fmt.Sprintf("%08d-1111-4111-8111-111111111111", d+1)
				ingest(t, s, m)
				r.Included = append(r.Included, m.Device)
			}
			r = confirmPresentation(t, s, r, 1, *now)
			r.Name = "Variable fleet"
			v, e := s.presentationOperation("save", r)
			if e != nil {
				t.Fatal(e)
			}
			if v.(map[string]any)["total_parties"] != fleet {
				t.Fatal(v)
			}
			action(t, s, "stop")
			m := message(t, 1, 0, 0, 0, 1)
			m.Device = "99999999-1111-4111-8111-111111111111"
			ingest(t, s, m)
			v, e = s.presentationOperation("save", r)
			if e != nil {
				t.Fatal(e)
			}
			if v.(map[string]any)["total_parties"] != fleet {
				t.Fatal("party arriving after Stop changed participation", v)
			}
		})
	}
}
func TestPresentationSaveAllowsConcurrentReceiptAndFreezesReadBoundary(t *testing.T) {
	s, now, _ := open(t)
	r := claimPresentation(t, s)
	action(t, s, "start")
	for n := 1; n <= 600; n++ {
		ingest(t, s, message(t, n, float64(n), float64(n), 0, 1))
	}
	r = confirmPresentation(t, s, r, 1, *now)
	r.Name = "Concurrent"
	r.Included = []string{device}
	done := make(chan error, 1)
	go func() { _, e := s.presentationOperation("save", r); done <- e }()
	deadline := time.Now().Add(15 * time.Second)
	for {
		var n int
		_ = s.db.QueryRow(`SELECT COUNT(*) FROM saved_originals`).Scan(&n)
		if n > 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("Save did not begin staging")
		}
		time.Sleep(time.Millisecond)
	}
	started := time.Now()
	ingest(t, s, message(t, 601, 601, 601, 0, 1))
	latency := time.Since(started)
	if e := <-done; e != nil {
		t.Fatal(e)
	}
	var n int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM saved_originals`).Scan(&n)
	if n != 600 {
		t.Fatal("late arrival expanded sealed read boundary", n)
	}
	t.Logf("receipt while Save staged 600 originals: %s", latency)
}

func TestPresentationExactStartAndResumeBoundaryProof(t *testing.T) {
	s, now, _ := open(t)
	r := claimPresentation(t, s)
	ingest(t, s, message(t, 1, -10, 0, 0, 1))
	proof := presentationRequest{Revision: 1, Presented: now.Add(-time.Second).Format(time.RFC3339Nano), Opportunity: now.Add(-500 * time.Millisecond).Format(time.RFC3339Nano), State: displayState{Version: 1, Common: json.RawMessage(`{"receipt_version":1}`), Parties: map[string]json.RawMessage{device: json.RawMessage(`{"visible":true}`)}}}
	if e := s.actionModeOwned("start", "from_now", r.Token, r.Epoch, now.Format(time.RFC3339Nano), &proof); e != nil {
		t.Fatal(e)
	}
	var data string
	if e := s.db.QueryRow(`SELECT data FROM presentation_journal WHERE kind='display'`).Scan(&data); e != nil {
		t.Fatal(e)
	}
	var event map[string]any
	_ = json.Unmarshal([]byte(data), &event)
	if event["boundary_state"] != true || event["presented_at"] != proof.Presented || event["effective_at"] != now.Format(time.RFC3339Nano) {
		t.Fatal(event)
	}
	*now = now.Add(2 * time.Second)
	action(t, s, "pause")
	*now = now.Add(time.Second)
	proof.Revision = 2
	proof.Presented = now.Add(-500 * time.Millisecond).Format(time.RFC3339Nano)
	proof.Opportunity = now.Add(-100 * time.Millisecond).Format(time.RFC3339Nano)
	if e := s.actionModeOwned("resume", "from_now", r.Token, r.Epoch, now.Format(time.RFC3339Nano), &proof); e != nil {
		t.Fatal(e)
	}
	var count int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM presentation_journal WHERE kind='display'`).Scan(&count)
	if count != 2 {
		t.Fatal("boundary states not durable", count)
	}
}

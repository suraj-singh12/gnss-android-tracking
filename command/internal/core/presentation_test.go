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

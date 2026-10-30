package core

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestInterruptionConnectedOverlapAndPriority(t *testing.T) {
	base := time.Date(2026, 10, 27, 0, 0, 0, 0, time.UTC)
	interval := func(from, until int, kind string) playbackInterval {
		return playbackInterval{From: base.Add(time.Duration(from) * time.Second), Until: base.Add(time.Duration(until) * time.Second), Kind: kind, Facts: []playbackEvent{{Kind: kind}}}
	}
	groups := interruptionGroups([]playbackInterval{interval(30, 40, "PAUSED"), interval(0, 10, "PAUSED"), interval(9, 29, "BROWSER NOT ACTIVE"), interval(8, 28, "COMMAND NOT RUNNING"), interval(39, 41, "BROWSER NOT ACTIVE")})
	if len(groups) != 2 || groups[0].Kind != "COMMAND NOT RUNNING" || groups[0].Duration != 20 || len(groups[0].Facts) != 3 || groups[0].Until.Sub(groups[0].From) != 29*time.Second || groups[1].Kind != "PAUSED" {
		t.Fatalf("%+v", groups)
	}
}
func TestPlaybackFrozenClockVisibilityReadOnlyAndRestart(t *testing.T) {
	s, now, path := open(t)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	action(t, s, "start")
	start := *now
	r := claimPresentation(t, s)
	publish := func(revision int64, visible bool, caption string) {
		t.Helper()
		*now = start.Add(time.Duration(revision) * time.Second)
		r.Revision = revision
		r.Presented = now.Format(time.RFC3339Nano)
		r.Opportunity = r.Presented
		r.State = displayState{Version: 1, Common: json.RawMessage(`{"width":1280,"height":720,"policy":{"revision":12},"selected_party":"` + device + `","map_svg":"<path d='M0,0L2,2'/>"}`), Parties: map[string]json.RawMessage{}}
		for n := 0; n < 21; n++ {
			id := device
			if n > 0 {
				id = fmt.Sprintf("party-%d", n)
			}
			r.State.Parties[id] = json.RawMessage(marshal(map[string]any{"visible": visible || n != 0, "svg": "<circle cx='20' cy='20'/>", "panel": caption, "device": map[string]any{"snapshot": map[string]any{"party": map[string]any{"id": id}}, "original_fix": "09:00"}}))
		}
		if _, e := s.presentationOperation("confirm", r); e != nil {
			t.Fatal(e)
		}
	}
	publish(1, true, "first rendered")
	publish(2, false, "SECRET hidden detail")
	publish(3, true, "delayed redraw at presentation time")
	publish(4, false, "hidden again")
	publish(5, true, "revealed again")
	r.Name = "Playback"
	r.Included = []string{device}
	v, e := s.presentationOperation("save", r)
	if e != nil {
		t.Fatal(e)
	}
	sid := v.(map[string]any)["id"].(string)
	read := func(at time.Time) map[string]any {
		t.Helper()
		req := httptest.NewRequest("GET", fmt.Sprintf("http://localhost/local/presentation/playback?id=%s&at=%d", sid, at.UnixMilli()), nil)
		w := httptest.NewRecorder()
		s.presentationHTTP(w, req)
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
		if strings.Contains(w.Body.String(), "party-1\"") {
			t.Fatal("excluded details leaked")
		}
		var data map[string]any
		if e := json.Unmarshal(w.Body.Bytes(), &data); e != nil {
			t.Fatal(e)
		}
		return data
	}
	var before, after string
	_ = s.db.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=?`, sid).Scan(&before)
	hidden := read(start.Add(2 * time.Second))
	if strings.Contains(marshal(hidden), "SECRET") || strings.Contains(marshal(hidden), "original_fix") {
		t.Fatal("hidden data leaked", hidden)
	}
	first := read(start.Add(time.Second))
	last := read(start.Add(3 * time.Second))
	if !strings.Contains(marshal(last), "delayed redraw") || strings.Contains(marshal(first), "delayed redraw") {
		t.Fatal("wrong presentation chronology")
	}
	if first["visibility_epochs"].(map[string]any)[device] == last["visibility_epochs"].(map[string]any)[device] {
		t.Fatal("reveal boundary lost")
	}
	if marshal(first) != marshal(read(start.Add(time.Second))) {
		t.Fatal("rewind not deterministic")
	}
	var journalBefore, journalAfter int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM presentation_journal`).Scan(&journalBefore)
	w := httptest.NewRecorder()
	s.presentationHTTP(w, httptest.NewRequest("POST", "http://localhost/local/presentation/playback?id="+sid, nil))
	if w.Code != 405 {
		t.Fatal(w.Code)
	}
	for i := 0; i < 10; i++ {
		read(start.Add(time.Second))
	}
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM presentation_journal`).Scan(&journalAfter)
	_ = s.db.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=?`, sid).Scan(&after)
	if journalBefore != journalAfter || before != after {
		t.Fatal("historical read wrote evidence")
	}
	s.Close()
	s, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if marshal(last) != marshal(read(start.Add(3*time.Second))) {
		t.Fatal("restart changed playback")
	}
}

func TestLibraryReclamationPreservesSharedPinsAndOperationalData(t *testing.T) {
	s, now, _ := open(t)
	defer s.Close()
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	r := claimPresentation(t, s)
	action(t, s, "start")
	r = confirmPresentation(t, s, r, 1, *now)
	hash, e := s.pinServedAsset([]byte(`{"immutable":"map terrain fixture"}`))
	if e != nil {
		t.Fatal(e)
	}
	r.Revision = 2
	r.State.Assets = []string{hash}
	r.State.MapID = "fixture-map"
	if _, e = s.presentationOperation("confirm", r); e != nil {
		t.Fatal(e)
	}
	r.Included = []string{device}
	r.Name = "First"
	a, e := s.presentationOperation("save", r)
	if e != nil {
		t.Fatal(e)
	}
	r.Name = "Second"
	b, e := s.presentationOperation("save", r)
	if e != nil {
		t.Fatal(e)
	}
	action(t, s, "discard")
	// Neither an unrelated response pin nor an unrelated unstaged blob belongs to
	// either saved snapshot, even if no recording currently references it.
	unrelatedAsset, e := s.pinServedAsset([]byte("unrelated served response"))
	if e != nil {
		t.Fatal(e)
	}
	unrelatedBlob, e := putPresentationBlob(s.db, []byte(`{"unrelated":true}`), "", "unrelated-test")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.presentationOperation("close", r); e != nil {
		t.Fatal(e)
	}
	remove := func(id string) {
		t.Helper()
		req := httptest.NewRequest("POST", "http://localhost/local/presentation/library", strings.NewReader(marshal(map[string]any{"id": id, "action": "delete", "confirmed": true})))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Origin", "http://localhost")
		w := httptest.NewRecorder()
		s.presentationHTTP(w, req)
		if w.Code != 200 {
			t.Fatal(w.Body.String())
		}
	}
	remove(a.(map[string]any)["id"].(string))
	var count int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM presentation_assets WHERE hash=?`, hash).Scan(&count)
	if count != 1 {
		t.Fatal("shared asset deleted")
	}
	var original string
	sid := b.(map[string]any)["id"].(string)
	_ = s.db.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=?`, sid).Scan(&original)
	remove(sid)
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM presentation_assets WHERE hash=?`, hash).Scan(&count)
	if count != 0 {
		t.Fatal("unreferenced pinned asset retained")
	}
	for _, check := range []struct{ table, hash string }{{"presentation_assets", unrelatedAsset}, {"presentation_blobs", unrelatedBlob}} {
		if e = s.db.QueryRow("SELECT COUNT(*) FROM "+check.table+" WHERE hash=?", check.hash).Scan(&count); e != nil || count != 1 {
			t.Fatalf("unrelated %s reclaimed: count=%d error=%v", check.table, count, e)
		}
	}
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM raw`).Scan(&count)
	if count != 1 {
		t.Fatal("operational raw modified")
	}
	var after string
	_ = s.db.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=?`, sid).Scan(&after)
	if original != after {
		t.Fatal("sealed receipt modified")
	}
}

func TestPlaybackLargeIndexedSeek(t *testing.T) {
	if testing.Short() {
		t.Skip("large persisted presentation fixture")
	}
	s, now, _ := open(t)
	defer s.Close()
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	action(t, s, "start")
	start := *now
	r := claimPresentation(t, s)
	for n := int64(1); n <= 1200; n++ {
		*now = start.Add(time.Duration(n) * time.Millisecond)
		r = confirmPresentation(t, s, r, n, *now)
	}
	r.Name = "1200 browser states"
	r.Included = []string{device}
	v, e := s.presentationOperation("save", r)
	if e != nil {
		t.Fatal(e)
	}
	sid := v.(map[string]any)["id"].(string)
	runtime.GC()
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)
	baseline, peak := mem.Alloc, mem.Alloc
	began := time.Now()
	var maxBytes int
	for n := int64(0); n < 40; n++ {
		req := httptest.NewRequest("GET", fmt.Sprintf("http://localhost/local/presentation/playback?id=%s&at=%d", sid, start.Add(time.Duration(1+(n*29)%1200)*time.Millisecond).UnixMilli()), nil)
		w := httptest.NewRecorder()
		s.presentationHTTP(w, req)
		if w.Code != 200 {
			t.Fatal(w.Body.String())
		}
		runtime.ReadMemStats(&mem)
		if mem.Alloc > peak {
			peak = mem.Alloc
		}
		if w.Body.Len() > maxBytes {
			maxBytes = w.Body.Len()
		}
	}
	t.Logf("1200 real confirmed states; 40 random indexed seeks: %s; largest response %d bytes; peak heap growth %d bytes", time.Since(began), maxBytes, peak-baseline)
	if maxBytes > presentationLimit+65536 {
		t.Fatal("unbounded response", maxBytes)
	}
}

func TestPlaybackInterruptionResumesAtFirstConfirmedDisplay(t *testing.T) {
	s, now, _ := open(t)
	defer s.Close()
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	action(t, s, "start")
	start := *now
	r := claimPresentation(t, s)
	*now = start.Add(time.Second)
	r = confirmPresentation(t, s, r, 1, *now)
	state := r.State
	*now = start.Add(1500 * time.Millisecond)
	if _, e := s.presentationOperation("close", r); e != nil {
		t.Fatal(e)
	}
	*now = start.Add(1600 * time.Millisecond)
	r = claimPresentation(t, s)
	r.State = state
	r.Revision = 1
	r.Presented = start.Add(2 * time.Second).Format(time.RFC3339Nano)
	r.Opportunity = start.Add(2100 * time.Millisecond).Format(time.RFC3339Nano)
	*now = start.Add(2200 * time.Millisecond)
	if _, e := s.presentationOperation("confirm", r); e != nil {
		t.Fatal(e)
	}
	*now = start.Add(3 * time.Second)
	r = confirmPresentation(t, s, r, 2, *now)
	r.Name = "Availability boundary"
	r.Included = []string{device}
	v, e := s.presentationOperation("save", r)
	if e != nil {
		t.Fatal(e)
	}
	sid := v.(map[string]any)["id"].(string)
	w := httptest.NewRecorder()
	s.presentationHTTP(w, httptest.NewRequest("GET", "http://localhost/local/presentation/playback?id="+sid, nil))
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var manifest struct{ Groups []playbackGroup }
	if e = json.Unmarshal(w.Body.Bytes(), &manifest); e != nil {
		t.Fatal(e)
	}
	if len(manifest.Groups) != 1 || !manifest.Groups[0].SkipUntil.Equal(start.Add(2*time.Second)) {
		t.Fatalf("first resumed rendered state skipped: %+v", manifest.Groups)
	}
}

// A response may be on the live browser's screen (or in flight to it) before
// its next #27 confirmation. Deleting a different saved use cannot erase it.
func TestLibraryReclamationDefersLivePinsAcrossRestart(t *testing.T) {
	s, now, path := open(t)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	r := claimPresentation(t, s)
	action(t, s, "start")
	r = confirmPresentation(t, s, r, 1, *now)
	hash, e := s.pinServedAsset([]byte(`{"map":"live response"}`))
	if e != nil {
		t.Fatal(e)
	}
	r.Revision = 2
	r.State.Assets = []string{hash}
	r.State.MapID = "live-map"
	if _, e = s.presentationOperation("confirm", r); e != nil {
		t.Fatal(e)
	}
	r.Included, r.Name = []string{device}, "Snapshot sharing live response"
	value, e := s.presentationOperation("save", r)
	if e != nil {
		t.Fatal(e)
	}
	sid := value.(map[string]any)["id"].(string)
	action(t, s, "discard")
	if _, e = s.db.Exec(`INSERT INTO saved_library VALUES(?, '{}', 1)`, sid); e != nil {
		t.Fatal(e)
	}
	s.saveMu.Lock()
	e = s.reclaimSnapshot(sid)
	s.saveMu.Unlock()
	if !errors.Is(e, errSnapshotAssetsInUse) {
		t.Fatalf("live response must postpone reclamation: %v", e)
	}
	var count int
	if e = s.db.QueryRow(`SELECT COUNT(*) FROM presentation_assets WHERE hash=?`, hash).Scan(&count); e != nil || count != 1 {
		t.Fatalf("live pin lost: %v", e)
	}
	// Even accidental direct deletes are blocked while the live page owns it.
	if _, e = s.db.Exec(`DELETE FROM presentation_assets WHERE hash=?`, hash); e == nil {
		t.Fatal("live asset deletion was permitted")
	}
	s.Close()
	s, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if e = s.db.QueryRow(`SELECT COUNT(*) FROM presentation_assets WHERE hash=?`, hash).Scan(&count); e != nil || count != 1 {
		t.Fatalf("restart lost live response: %v", e)
	}
	r = claimPresentation(t, s)
	if _, e = s.presentationOperation("close", r); e != nil {
		t.Fatal(e)
	}
	// The existing liveness worker finishes authorized pending cleanup without
	// another Delete request, including after Command process restart.
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if e = s.db.QueryRow(`SELECT COUNT(*) FROM saved_gc_done WHERE snapshot=?`, sid).Scan(&count); e != nil {
			t.Fatal(e)
		}
		if count == 1 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if count != 1 {
		t.Fatal("pending reclamation never resumed")
	}
	if e = s.db.QueryRow(`SELECT COUNT(*) FROM presentation_assets WHERE hash=?`, hash).Scan(&count); e != nil || count != 0 {
		t.Fatalf("released response retained: %v", e)
	}
}

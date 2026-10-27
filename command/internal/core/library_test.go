package core

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func TestLibraryMetadataRestartAndEvidenceIsolation(t *testing.T) {
	s, now, path := open(t)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	action(t, s, "start")
	lease := claimPresentation(t, s)
	lease = confirmPresentation(t, s, lease, 1, *now)
	lease.Name = "Original"
	lease.Included = []string{device}
	value, err := s.presentationOperation("save", lease)
	if err != nil {
		t.Fatal(err)
	}
	id := value.(map[string]any)["id"].(string)
	var original string
	if err = s.db.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=?`, id).Scan(&original); err != nil {
		t.Fatal(err)
	}
	call := func(method, body string, origin bool) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, "http://localhost/local/presentation/library", bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		if origin {
			req.Header.Set("Origin", "http://localhost")
		}
		w := httptest.NewRecorder()
		s.presentationHTTP(w, req)
		return w
	}
	body := marshal(map[string]any{"id": id, "action": "edit", "name": "Renamed", "description": "Test", "display_date": "2026-10-27 UTC"})
	if w := call("POST", body, false); w.Code != 403 {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := call("POST", body, true); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var after string
	_ = s.db.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=?`, id).Scan(&after)
	if original != after {
		t.Fatal("sealed receipt changed")
	}
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	w := call("GET", "", false)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var entries []map[string]any
	if err = json.Unmarshal(w.Body.Bytes(), &entries); err != nil || len(entries) != 1 || entries[0]["name"] != "Renamed" {
		t.Fatal(err, w.Body.String())
	}
	for _, confirmed := range []bool{false, true} {
		w = call("POST", marshal(map[string]any{"id": id, "action": "delete", "confirmed": confirmed}), true)
		want := 400
		if confirmed {
			want = 200
		}
		if w.Code != want {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	w = call("GET", "", false)
	if w.Body.String() != "[]\n" {
		t.Fatal(w.Body.String())
	}
	_ = s.db.QueryRow(`SELECT metadata FROM saved_recordings WHERE id=?`, id).Scan(&after)
	if original != after {
		t.Fatal("delete altered sealed evidence")
	}
	var raw int
	if err = s.db.QueryRow(`SELECT COUNT(*) FROM saved_originals WHERE snapshot=?`, id).Scan(&raw); err != nil || raw != 1 {
		t.Fatal("saved originals lost", raw, err)
	}
}

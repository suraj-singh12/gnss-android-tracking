package core

import (
	"bytes"
	"fmt"
	"net/http/httptest"
	"testing"
)

func TestHistoryDiskFullHasNoACKAndRecovers(t *testing.T) {
	s, _, _ := open(t)
	var pages int
	if err := s.db.QueryRow("PRAGMA page_count").Scan(&pages); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec(fmt.Sprintf("PRAGMA max_page_count=%d", pages)); err != nil {
		t.Fatal(err)
	}
	messages := []Message{}
	session := id()
	for n := 1; n <= 40; n++ {
		messages = append(messages, observationMessage(t, n, session))
	}
	body := batchBytes(t, messages)
	deliver := func() *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "/api/v1/history", bytes.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.IngestHandler().ServeHTTP(w, r)
		return w
	}
	failed := deliver()
	if failed.Code != 503 {
		t.Fatalf("disk-full must not ACK: %d %s", failed.Code, failed.Body.String())
	}
	var count int
	if err := s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&count); err != nil || count != 0 {
		t.Fatalf("partial durable receipt: %d %v", count, err)
	}
	if _, err := s.db.Exec("PRAGMA max_page_count=1073741823"); err != nil {
		t.Fatal(err)
	}
	if recovered := deliver(); recovered.Code != 200 {
		t.Fatalf("recovery: %d %s", recovered.Code, recovered.Body.String())
	}
	if err := s.db.QueryRow("SELECT COUNT(*) FROM raw").Scan(&count); err != nil || count != 40 {
		t.Fatalf("lost original identities: %d %v", count, err)
	}
}

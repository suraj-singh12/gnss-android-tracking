package core

import (
	"bytes"
	"context"
	"fmt"
	"net/http/httptest"
	"reflect"
	"sync"
	"testing"
	"time"

	"github.com/ncruces/go-sqlite3"
	"github.com/ncruces/go-sqlite3/driver"
)

func TestColdDashboardReaderDoesNotBlockDurableSOS(t *testing.T) {
	s, _, _ := open(t)
	ingest(t, s, message(t, 1, 0, 0, 0, 1))
	entered, release := make(chan struct{}), make(chan struct{})
	var enterOnce, releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	defer unblock()
	conn, err := s.viewDB.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	err = conn.Raw(func(c any) error {
		return c.(driver.Conn).Raw().SetAuthorizer(func(action sqlite3.AuthorizerActionCode, table, column, schema, inner string) sqlite3.AuthorizerReturnCode {
			if action == sqlite3.AUTH_READ && table == "raw" {
				enterOnce.Do(func() { close(entered); <-release })
			}
			return sqlite3.AUTH_OK
		})
	})
	conn.Close()
	if err != nil {
		t.Fatal(err)
	}
	viewDone := make(chan error, 1)
	go func() { _, err := s.snapshot(30, false, false); viewDone <- err }()
	select {
	case <-entered:
	case err := <-viewDone:
		t.Fatalf("dashboard never reached blocked read: %v", err)
	case <-time.After(5 * time.Second):
		t.Fatal("dashboard read did not start")
	}
	sos := emergency(t, 2, 1)
	body := encode(t, sos)
	ackDone := make(chan error, 1)
	go func() { _, err := s.Ingest(body); ackDone <- err }()
	select {
	case err := <-ackDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cold dashboard read blocked SOS durable receipt")
	}
	var retained int
	if err := s.db.QueryRow("SELECT COUNT(*) FROM raw WHERE message=?", sos.ID).Scan(&retained); err != nil || retained != 1 {
		t.Fatalf("SOS ACK was not durable: count=%d error=%v", retained, err)
	}
	unblock()
	if err := <-viewDone; err != nil {
		t.Fatal(err)
	}
	if _, err := s.viewDB.Exec("DELETE FROM raw"); err == nil {
		t.Fatal("dashboard connection must remain read only")
	}
}

func TestProjectionChunksPreserveOrderRestartAndRollback(t *testing.T) {
	s, _, path := open(t)
	tx, err := s.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	st, err := loadState(tx)
	if err != nil {
		t.Fatal(err)
	}
	for n := 0; n < projectionChunkRecords*3+7; n++ {
		st.Points = append(st.Points, Point{Message: fmt.Sprint(n), Sequence: int64(n), Distance: float64(n) / 7})
		if n%2 == 0 {
			st.Decisions = append(st.Decisions, Decision{Message: fmt.Sprint(n), Reason: "accuracy"})
		}
	}
	if err = saveState(tx, st); err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	tx, err = s.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	got, err := loadState(tx)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(st.Points, got.Points) || !reflect.DeepEqual(st.Decisions, got.Decisions) {
		t.Fatal("chunked projection changed order, identity or distance across restart")
	}
	// Replacing the projection and receipt metadata must roll back together.
	if err = saveState(tx, State{Devices: map[string]*Device{}}); err != nil {
		t.Fatal(err)
	}
	if err = tx.Rollback(); err != nil {
		t.Fatal(err)
	}
	tx, err = s.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	got, err = loadState(tx)
	if err != nil || !reflect.DeepEqual(st.Points, got.Points) || !reflect.DeepEqual(st.Decisions, got.Decisions) {
		t.Fatalf("rollback lost original projection: %v", err)
	}
}

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

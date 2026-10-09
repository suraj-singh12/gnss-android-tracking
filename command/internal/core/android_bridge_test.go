package core

// Test-only process adapter for Android's Robolectric integration suite. All
// ingestion/control requests use production handlers. Inspection and fault setup
// are stdin/stdout only, never added to the LAN listener or the product binary.
import (
	"bufio"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/suraj-singh12/gnss-android-tracking/command/web"
)

func TestAndroidBridge(t *testing.T) {
	path := os.Getenv("GNSS_BRIDGE_DB")
	if path == "" {
		t.Skip("launched by test-tools/integration/run.sh only")
	}
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	now := instant("2026-10-06T12:00:00.000Z")
	s.Now = func() time.Time { return now }
	var gate sync.Mutex
	drop := false
	phone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gate.Lock()
		defer gate.Unlock()
		if !drop {
			s.IngestHandler().ServeHTTP(w, r)
			return
		}
		drop = false
		// The real handler completes its durable transaction before the response
		// is lost at the physical adapter boundary.
		recorder := httptest.NewRecorder()
		s.IngestHandler().ServeHTTP(recorder, r)
		if recorder.Code != http.StatusOK {
			t.Errorf("drop-after-store expected successful commit: %s", recorder.Body)
		}
		conn, _, e := w.(http.Hijacker).Hijack()
		if e != nil {
			t.Error(e)
			return
		}
		_ = conn.Close()
	}))
	defer phone.Close()
	local := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gate.Lock()
		defer gate.Unlock()
		s.LocalHandler(web.Handler()).ServeHTTP(w, r)
	}))
	defer local.Close()
	out := json.NewEncoder(os.Stdout)
	if err = out.Encode(map[string]string{"phone": phone.URL, "local": local.URL}); err != nil {
		t.Fatal(err)
	}
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		var input struct {
			Action string `json:"action"`
			Now    int64  `json:"now"`
		}
		if err = json.Unmarshal(scanner.Bytes(), &input); err != nil {
			t.Fatal(err)
		}
		if input.Action == "close" {
			return
		}
		gate.Lock()
		result := map[string]any{}
		switch input.Action {
		case "clock":
			now = time.UnixMilli(input.Now).UTC()
		case "drop":
			drop = true
		case "fail_storage":
			_, err = s.db.Exec(`CREATE TRIGGER integration_fail BEFORE UPDATE ON state BEGIN SELECT RAISE(ABORT,'integration disk failure'); END`)
		case "restore_storage":
			_, err = s.db.Exec("DROP TRIGGER integration_fail")
		case "inspect":
			s.projectionMu.Lock()
			if e := s.processProjectionLocked(); e != nil {
				s.recordProjectionFailure(e)
			}
			s.projectionMu.Unlock()
			var st State
			st, err = s.Snapshot(10)
			result["state"] = st
			if err == nil {
				// Raw bytes/first receipts are read from real SQLite, not a mock
				// count maintained by the harness.
				raw := []map[string]any{}
				q, e := s.db.Query("SELECT wire,received FROM raw ORDER BY device,sequence")
				err = e
				if err == nil {
					for q.Next() {
						var b []byte
						var received string
						if err = q.Scan(&b, &received); err != nil {
							break
						}
						raw = append(raw, map[string]any{"message": json.RawMessage(b), "received_at": received})
					}
					if err == nil {
						err = q.Err()
					}
					q.Close()
				}
				result["raw"] = raw
			}
		default:
			err = io.ErrUnexpectedEOF
		}
		gate.Unlock()
		if err != nil {
			t.Fatal(err)
		}
		if err = out.Encode(result); err != nil {
			t.Fatal(err)
		}
	}
	if err = scanner.Err(); err != nil {
		t.Fatal(err)
	}
}

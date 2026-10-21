package core

// A logical job's allowance survives retries and restarts. Reserve before reads:
// a process interruption may overcharge one 32 KiB read, never grant extra bytes.
import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
)

const mapConsentBytes int64 = 5_000_000
const mapTransferLimit int64 = 1_000_000_000

var errMapConsent = errors.New("additional map transfer approval required")

type mapTransfer struct {
	Allowance int64  `json:"approved_bytes"`
	Charged   int64  `json:"reserved_bytes"`
	Received  int64  `json:"received_bytes"`
	Required  int64  `json:"approval_required_bytes,omitempty"`
	Source    string `json:"current_source,omitempty"`
}
type mapBudgetKey struct{}
type mapSourceAttempt struct {
	Source    string `json:"source"`
	Operation string `json:"operation"`
	Error     string `json:"error,omitempty"`
	At        string `json:"at"`
}
type mapBudget struct {
	attempts []mapSourceAttempt
	s        *Store
	id       string
	emit     demObserver
}

// Called under the Store mutex. Child metadata preserves byte-identical legacy
// GeoJSON and avoids rewriting a potentially large geographic plan on every read.
func (s *Store) readTransferLocked(id string) (mapTransfer, error) {
	var t mapTransfer
	var b []byte
	err := s.db.QueryRow(`SELECT data FROM offline_map_transfers WHERE map_id=?`, id).Scan(&b)
	if err == sql.ErrNoRows {
		var raw map[string]json.RawMessage
		err = s.db.QueryRow(`SELECT data FROM offline_maps WHERE id=?`, id).Scan(&b)
		if err != nil {
			return t, err
		}
		if err = json.Unmarshal(b, &raw); err != nil {
			return t, err
		}
		if v := raw["transfer"]; len(v) > 0 {
			if err = json.Unmarshal(v, &t); err != nil {
				return t, err
			}
		}
		// Compatibility with an acquisition started before the child-ledger migration.
		if t.Allowance == 0 {
			var received int64
			_ = json.Unmarshal(raw["source_bytes"], &received)
			if received > 0 && received <= mapConsentBytes {
				t.Received = received
				t.Charged = received
			}
		}
	} else if err == nil {
		err = json.Unmarshal(b, &t)
	}
	if t.Allowance == 0 {
		t.Allowance = mapConsentBytes
	}
	return t, err
}
func (s *Store) transferState(id string) (mapTransfer, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.readTransferLocked(id)
}
func (s *Store) changeTransfer(id string, f func(*mapTransfer) error) (mapTransfer, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, err := s.readTransferLocked(id)
	if err != nil {
		return t, err
	}
	decision := f(&t)
	_, err = s.db.Exec(`INSERT INTO offline_map_transfers(map_id,data) VALUES(?,?) ON CONFLICT(map_id) DO UPDATE SET data=excluded.data`, id, string(mustJSON(t)))
	if err != nil {
		return t, err
	}
	return t, decision
}
func (s *Store) approveMapTransfer(id string, allowance int64) error {
	_, err := s.changeTransfer(id, func(t *mapTransfer) error {
		if allowance < t.Allowance || allowance > mapTransferLimit || allowance < t.Required {
			return errors.New("approval must cover the displayed cumulative bytes within the 1 GB source limit")
		}
		t.Allowance = allowance
		t.Required = 0
		return nil
	})
	return err
}

type mapBudgetTransport struct {
	base   http.RoundTripper
	budget *mapBudget
}

func (t mapBudgetTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	state, err := t.budget.s.transferState(t.budget.id)
	if err != nil {
		return nil, err
	}
	if state.Charged >= state.Allowance {
		_, _ = t.budget.s.changeTransfer(t.budget.id, func(s *mapTransfer) error { s.Required = max(s.Required, s.Allowance+mapConsentBytes); return nil })
		return nil, errMapConsent
	}
	resp, err := t.base.RoundTrip(r)
	if err != nil {
		return nil, err
	}
	// No body is read when the announced transfer exceeds consent. Unknown sizes
	// are limited by every subsequent read. Redirect bodies are not drained.
	_, err = t.budget.s.changeTransfer(t.budget.id, func(s *mapTransfer) error {
		s.Source = r.URL.String()
		if resp.ContentLength > mapTransferLimit {
			return errors.New("source exceeds 1 GB supported transfer limit")
		}
		if resp.ContentLength > s.Allowance-s.Charged {
			s.Required = max(s.Required, s.Charged+resp.ContentLength)
			return fmt.Errorf("%w: %s requires cumulative %d bytes", errMapConsent, r.URL.Host, s.Required)
		}
		return nil
	})
	if err != nil {
		resp.Body.Close()
		return nil, err
	}
	resp.Body = &mapBudgetBody{ReadCloser: resp.Body, budget: t.budget, remaining: resp.ContentLength}
	return resp, nil
}

type mapBudgetBody struct {
	io.ReadCloser
	budget    *mapBudget
	remaining int64
}

func (b *mapBudgetBody) Read(dst []byte) (int, error) {
	if b.remaining == 0 {
		return 0, io.EOF
	}
	n := min(len(dst), 32768)
	if b.remaining > 0 {
		n = min(n, int(b.remaining))
	}
	_, err := b.budget.s.changeTransfer(b.budget.id, func(s *mapTransfer) error {
		n = min(n, int(s.Allowance-s.Charged))
		if n <= 0 {
			s.Required = max(s.Required, s.Allowance+mapConsentBytes)
			return errMapConsent
		}
		s.Charged += int64(n)
		if s.Required > 0 {
			s.Required += int64(n)
		}
		return nil
	})
	if err != nil {
		return 0, err
	}
	got, readErr := b.ReadCloser.Read(dst[:n])
	if b.remaining > 0 {
		b.remaining -= int64(got)
	}
	state, err := b.budget.s.changeTransfer(b.budget.id, func(s *mapTransfer) error {
		s.Received += int64(got)
		s.Charged -= int64(n - got)
		if s.Required > 0 {
			s.Required -= int64(n - got)
		}
		return nil
	})
	emitDEM(b.budget.emit, DEMProgress{Stage: "receiving — cumulative logical job", Bytes: state.Received})
	if err != nil {
		return got, err
	}
	return got, readErr
}
func mapHTTPClient(ctx context.Context, c *http.Client) *http.Client {
	budget, _ := ctx.Value(mapBudgetKey{}).(*mapBudget)
	if budget == nil {
		return c
	}
	copy := *c
	base := copy.Transport
	if base == nil {
		base = http.DefaultTransport
	}
	copy.Transport = mapBudgetTransport{base, budget}
	copy.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &copy
}

// Package mockreceiver implements only a test-memory Protocol v1 receiver.
package mockreceiver

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"reflect"
	"sync"
	"time"
)

type Config struct {
	Authority string `json:"authority_id"`
	Version   int64  `json:"version"`
	Override  *int   `json:"reporting_interval_override_s"`
}
type Step struct {
	Status         int     `json:"status"`
	DropAfterStore bool    `json:"drop_after_store"`
	Config         *Config `json:"config"`
	RetryAfter     string  `json:"retry_after"`
}
type Capture struct {
	Body       json.RawMessage
	Result     string
	ReceivedAt string
	Status     int
}
type stored struct {
	content  map[string]any
	received string
	message  string
	sequence string
}
type Receiver struct {
	mu            sync.Mutex
	authority     string
	clock         func() time.Time
	config        map[string]Config
	steps         []Step
	messages      map[string]stored
	sequences     map[string]string
	captures      []Capture
	captureWriter io.Writer
}

func New(authority string, clock func() time.Time) (*Receiver, error) {
	if !uuid.MatchString(authority) {
		return nil, fmt.Errorf("invalid authority UUID")
	}
	if clock == nil {
		clock = time.Now
	}
	return &Receiver{authority: authority, clock: clock, config: map[string]Config{}, messages: map[string]stored{}, sequences: map[string]string{}}, nil
}
func validateConfig(c Config) error {
	b, _ := json.Marshal(c)
	m, err := decode(b)
	if err != nil {
		return err
	}
	return checkConfig(m, false)
}

// Local Go harness controls only: never a second phone HTTP control channel.
func (r *Receiver) SetConfig(device string, c Config) error {
	if !uuid.MatchString(device) {
		return fmt.Errorf("invalid device UUID")
	}
	if err := validateConfig(c); err != nil {
		return err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.config[device] = cloneConfig(c)
	return nil
}
func cloneConfig(c Config) Config {
	if c.Override != nil {
		v := *c.Override
		c.Override = &v
	}
	return c
}
func (r *Receiver) Queue(steps ...Step) error {
	for _, s := range steps {
		if s.Config != nil {
			if err := validateConfig(*s.Config); err != nil {
				return err
			}
		}
		if s.Status != 0 && (s.Status < 400 || s.Status > 599) {
			return fmt.Errorf("fault status must be 400..599")
		}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, s := range steps {
		if s.Config != nil {
			c := cloneConfig(*s.Config)
			s.Config = &c
		}
		r.steps = append(r.steps, s)
	}
	return nil
}

// SetCaptureWriter streams JSON lines for local inspection only. Write errors are
// visible diagnostics; capture logging does not pretend to provide durable storage.
func (r *Receiver) SetCaptureWriter(w io.Writer) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.captureWriter = w
}
func (r *Receiver) record(c Capture) {
	r.captures = append(r.captures, c)
	if r.captureWriter != nil {
		if err := json.NewEncoder(r.captureWriter).Encode(c); err != nil {
			log.Printf("mock capture write failed: %v", err)
		}
	}
}
func (r *Receiver) Captures() []Capture {
	r.mu.Lock()
	defer r.mu.Unlock()
	result := make([]Capture, len(r.captures))
	copy(result, r.captures)
	for i := range result {
		result[i].Body = append(json.RawMessage(nil), result[i].Body...)
	}
	return result
}
func (r *Receiver) Count() int { r.mu.Lock(); defer r.mu.Unlock(); return len(r.messages) }
func respond(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
func failure(w http.ResponseWriter, status int, code string) {
	respond(w, status, map[string]any{"protocol_version": 1, "error": code, "message": code})
}
func known(m map[string]any) map[string]any {
	fields := map[string][]string{
		"":      {"protocol_version", "type", "device_id", "party", "message_id", "sequence", "captured_at", "config_state", "health", "fix", "sos"},
		"party": {"id", "name"}, "config_state": {"authority_id", "version", "reporting_interval_override_s", "local_reporting_interval_s", "effective_reporting_interval_s"},
		"health": {"battery_percent", "charging", "wifi_connected", "wifi_rssi_dbm", "gnss_status", "satellites_used"},
		"fix":    {"observed_at", "fix_age_ms", "latitude", "longitude", "horizontal_accuracy_m", "altitude_m", "altitude_accuracy_m", "speed_mps", "bearing_deg"},
		"sos":    {"event_id", "triggered_at"},
	}
	out := map[string]any{}
	for _, k := range fields[""] {
		v, ok := m[k]
		if !ok {
			continue
		}
		if nested, ok := v.(map[string]any); ok {
			n := map[string]any{}
			for _, key := range fields[k] {
				n[key] = normalize(nested[key])
			}
			out[k] = n
		} else {
			out[k] = normalize(v)
		}
	}
	return out
}

// Numeric spellings/key order and ignored additive fields do not change identity.
func normalize(v any) any {
	if n, ok := v.(json.Number); ok {
		f, _ := n.Float64()
		return f
	}
	return v
}
func (r *Receiver) ServeHTTP(w http.ResponseWriter, req *http.Request) {
	if req.URL.Path != "/api/v1/messages" {
		failure(w, 404, "unsupported_endpoint")
		return
	}
	if req.Method != "POST" {
		w.Header().Set("Allow", "POST")
		failure(w, 405, "method_not_allowed")
		return
	}
	if media := req.Header.Get("Content-Type"); media != "application/json" && media != "application/json; charset=utf-8" {
		failure(w, 415, "unsupported_media_type")
		return
	}
	b, err := io.ReadAll(io.LimitReader(req.Body, 65537))
	if err != nil {
		failure(w, 400, "invalid_message")
		return
	}
	if len(b) > 65536 {
		failure(w, 413, "message_too_large")
		return
	}
	m, err := decode(b)
	if err != nil {
		failure(w, 400, "invalid_message")
		return
	}
	if version, ok := m["protocol_version"]; ok && num(version, 0, maxInteger, true) && version != json.Number("1") {
		failure(w, 426, "unsupported_protocol")
		return
	}
	if err := checkRequest(m); err != nil {
		failure(w, 400, "invalid_message")
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	step := Step{}
	if len(r.steps) > 0 {
		step = r.steps[0]
		r.steps = r.steps[1:]
	}
	if step.Status != 0 {
		r.record(Capture{Body: append([]byte(nil), b...), Status: step.Status})
		if step.RetryAfter != "" {
			w.Header().Set("Retry-After", step.RetryAfter)
		}
		code := "storage_unavailable"
		if step.Status == 409 {
			code = "identity_conflict"
		}
		if step.Status == 429 {
			code = "busy"
		}
		failure(w, step.Status, code)
		return
	}
	device, message, sequence := str(m["device_id"]), str(m["message_id"]), string(m["sequence"].(json.Number))
	key, seqkey := device+"/"+message, device+"/"+sequence
	content := known(m)
	result := "stored"
	received := r.clock().UTC().Format("2006-01-02T15:04:05.000Z")
	if old, exists := r.messages[key]; exists {
		if old.sequence != sequence || !reflect.DeepEqual(old.content, content) {
			r.record(Capture{Body: append([]byte(nil), b...), Status: 409})
			failure(w, 409, "identity_conflict")
			return
		}
		result = "duplicate"
		received = old.received
	} else if previous, exists := r.sequences[seqkey]; exists && previous != key {
		r.record(Capture{Body: append([]byte(nil), b...), Status: 409})
		failure(w, 409, "identity_conflict")
		return
	} else {
		r.messages[key] = stored{content, received, message, sequence}
		r.sequences[seqkey] = key
	}
	config, ok := r.config[device]
	if !ok {
		config = Config{Authority: r.authority}
	}
	if step.Config != nil {
		config = *step.Config
	}
	r.record(Capture{append([]byte(nil), b...), result, received, 200})
	if step.DropAfterStore {
		if hijacker, ok := w.(http.Hijacker); ok {
			conn, _, err := hijacker.Hijack()
			if err == nil {
				_ = conn.Close()
				return
			}
		}
		panic(http.ErrAbortHandler)
	}
	respond(w, 200, map[string]any{"protocol_version": 1, "device_id": device, "message_id": message, "sequence": m["sequence"], "result": result, "received_at": received, "config": config})
}

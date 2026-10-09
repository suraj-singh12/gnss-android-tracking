// Package contract checks shared examples, not either application's implementation.
package contract

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"
	"unicode/utf8"
)

const maxInteger = 9007199254740991

var uuid = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var integer = regexp.MustCompile(`^(0|[1-9][0-9]*|-[1-9][0-9]*)$`)
var fixtures = filepath.Join("..", "..", "protocol", "fixtures")

// Decode recursively so duplicate keys are rejected rather than silently replaced.
func value(d *json.Decoder) (any, error) {
	tok, err := d.Token()
	if err != nil {
		return nil, err
	}
	switch tok {
	case json.Delim('{'):
		m := map[string]any{}
		for d.More() {
			k, err := d.Token()
			if err != nil {
				return nil, err
			}
			key, ok := k.(string)
			if !ok {
				return nil, fmt.Errorf("non-string key")
			}
			if _, exists := m[key]; exists {
				return nil, fmt.Errorf("duplicate key %s", key)
			}
			v, err := value(d)
			if err != nil {
				return nil, err
			}
			m[key] = v
		}
		_, err = d.Token()
		return m, err
	case json.Delim('['):
		a := []any{}
		for d.More() {
			v, err := value(d)
			if err != nil {
				return nil, err
			}
			a = append(a, v)
		}
		_, err = d.Token()
		return a, err
	default:
		return tok, nil
	}
}
func decode(b []byte) (map[string]any, error) {
	d := json.NewDecoder(strings.NewReader(string(b)))
	d.UseNumber()
	v, err := value(d)
	if err != nil {
		return nil, err
	}
	if _, err = d.Token(); err != io.EOF {
		return nil, fmt.Errorf("trailing JSON: %v", err)
	}
	m, ok := v.(map[string]any)
	if !ok {
		return nil, fmt.Errorf("expected object")
	}
	return m, nil
}
func load(t *testing.T, name string) map[string]any {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(fixtures, name))
	if err != nil {
		t.Fatal(err)
	}
	m, err := decode(b)
	if err != nil {
		t.Fatal(err)
	}
	return m
}
func object(v any) map[string]any { m, _ := v.(map[string]any); return m }
func str(v any) string            { s, _ := v.(string); return s }
func num(v any, min, max float64, whole bool) bool {
	n, ok := v.(json.Number)
	if !ok {
		return false
	}
	f, err := strconv.ParseFloat(string(n), 64)
	return err == nil && f >= min && f <= max && (!whole || integer.MatchString(string(n)))
}
func interval(v any) bool {
	if !num(v, 5, 86400, true) {
		return false
	}
	n, _ := strconv.Atoi(string(v.(json.Number)))
	return n%5 == 0
}
func stamp(v any) bool {
	s := str(v)
	tm, err := time.Parse("2006-01-02T15:04:05.000Z", s)
	return err == nil && tm.Format("2006-01-02T15:04:05.000Z") == s
}
func required(m map[string]any, keys ...string) error {
	if m == nil {
		return fmt.Errorf("expected object")
	}
	for _, k := range keys {
		if _, ok := m[k]; !ok {
			return fmt.Errorf("missing %s", k)
		}
	}
	return nil
}
func checkConfig(c map[string]any, state bool) error {
	if err := required(c, "authority_id", "version", "reporting_interval_override_s"); err != nil {
		return err
	}
	if !num(c["version"], 0, maxInteger, true) {
		return fmt.Errorf("config version")
	}
	initial := state && c["authority_id"] == nil
	if !initial && !uuid.MatchString(str(c["authority_id"])) {
		return fmt.Errorf("authority")
	}
	zero := c["version"] == json.Number("0")
	if (initial && !zero) || (zero && c["reporting_interval_override_s"] != nil) {
		return fmt.Errorf("initial config")
	}
	override := c["reporting_interval_override_s"]
	if override != nil && !interval(override) {
		return fmt.Errorf("override")
	}
	if state {
		if err := required(c, "local_reporting_interval_s", "effective_reporting_interval_s"); err != nil {
			return err
		}
		if !interval(c["local_reporting_interval_s"]) || !interval(c["effective_reporting_interval_s"]) {
			return fmt.Errorf("interval")
		}
		expected := override
		if expected == nil {
			expected = c["local_reporting_interval_s"]
		}
		if c["effective_reporting_interval_s"] != expected {
			return fmt.Errorf("precedence")
		}
	}
	return nil
}
func checkFix(f map[string]any) error {
	if err := required(f, "observed_at", "fix_age_ms", "latitude", "longitude", "horizontal_accuracy_m", "altitude_m", "altitude_accuracy_m", "speed_mps", "bearing_deg"); err != nil {
		return err
	}
	if !stamp(f["observed_at"]) || !num(f["fix_age_ms"], 0, maxInteger, true) || !num(f["latitude"], -90, 90, false) || !num(f["longitude"], -180, 180, false) {
		return fmt.Errorf("fix coordinates/time")
	}
	for _, k := range []string{"horizontal_accuracy_m", "altitude_accuracy_m", "speed_mps"} {
		if f[k] != nil && !num(f[k], 0, 1.7976931348623157e308, false) {
			return fmt.Errorf("%s", k)
		}
	}
	if f["altitude_m"] != nil && !num(f["altitude_m"], -1.7976931348623157e308, 1.7976931348623157e308, false) {
		return fmt.Errorf("altitude")
	}
	if f["altitude_m"] == nil && f["altitude_accuracy_m"] != nil {
		return fmt.Errorf("accuracy without altitude")
	}
	if f["bearing_deg"] != nil {
		if !num(f["bearing_deg"], 0, 360, false) {
			return fmt.Errorf("bearing")
		}
		n, _ := strconv.ParseFloat(string(f["bearing_deg"].(json.Number)), 64)
		if n >= 360 {
			return fmt.Errorf("bearing")
		}
	}
	return nil
}
func checkIdentity(m map[string]any) error {
	if err := required(m, "protocol_version", "device_id", "message_id", "sequence"); err != nil {
		return err
	}
	if m["protocol_version"] != json.Number("1") || !uuid.MatchString(str(m["device_id"])) || !uuid.MatchString(str(m["message_id"])) || !num(m["sequence"], 1, maxInteger, true) {
		return fmt.Errorf("identity/version")
	}
	return nil
}
func checkRequest(m map[string]any) error {
	if o := m["observation"]; o != nil {
		identity := object(o)
		if m["type"] != "location" || identity["observation_id"] != m["message_id"] || !uuid.MatchString(str(identity["tracking_session_id"])) || !stamp(identity["session_started_at"]) || !num(identity["observation_sequence"], 1, maxInteger, true) || !num(identity["measurement_elapsed_ms"], 1, maxInteger, true) {
			return fmt.Errorf("invalid native observation identity")
		}
	}
	if q := m["history_progress"]; q != nil {
		progress := object(q)
		if !uuid.MatchString(str(progress["tracking_session_id"])) || !stamp(progress["session_started_at"]) || !stamp(progress["measured_at"]) || !num(progress["latest_committed_sequence"], 0, maxInteger, true) || !num(progress["pending_observations"], 0, maxInteger, true) || !num(progress["known_collection_loss"], 0, maxInteger, true) {
			return fmt.Errorf("invalid history progress")
		}
		n, _ := strconv.ParseFloat(strNumber(progress["latest_committed_sequence"]), 64)
		if v := progress["oldest_pending_sequence"]; v != nil && !num(v, 1, n, true) {
			return fmt.Errorf("invalid oldest pending")
		}
		values, ok := progress["unresolved_sequences"].([]any)
		if !ok {
			return fmt.Errorf("unresolved sequences required")
		}
		for _, v := range values {
			if !num(v, 1, n, true) {
				return fmt.Errorf("invalid unresolved sequence")
			}
		}
	}
	if err := checkIdentity(m); err != nil {
		return err
	}
	if err := required(m, "type", "party", "captured_at", "config_state", "health", "fix"); err != nil {
		return err
	}
	if !stamp(m["captured_at"]) {
		return fmt.Errorf("captured_at")
	}
	p := object(m["party"])
	if err := required(p, "id", "name"); err != nil {
		return err
	}
	for _, k := range []string{"id", "name"} {
		s := str(p[k])
		if s == "" || utf8.RuneCountInString(s) > 80 {
			return fmt.Errorf("party label")
		}
	}
	if err := checkConfig(object(m["config_state"]), true); err != nil {
		return err
	}
	h := object(m["health"])
	if err := required(h, "battery_percent", "charging", "wifi_connected", "wifi_rssi_dbm", "gnss_status", "satellites_used"); err != nil {
		return err
	}
	for _, k := range []string{"charging", "wifi_connected"} {
		if h[k] != nil {
			if _, ok := h[k].(bool); !ok {
				return fmt.Errorf("%s", k)
			}
		}
	}
	for _, r := range []struct {
		k        string
		min, max float64
	}{{"battery_percent", 0, 100}, {"wifi_rssi_dbm", -127, 0}, {"satellites_used", 0, maxInteger}} {
		if h[r.k] != nil && !num(h[r.k], r.min, r.max, true) {
			return fmt.Errorf("%s", r.k)
		}
	}
	switch str(h["gnss_status"]) {
	case "fix", "no_fix", "disabled", "unknown":
	default:
		return fmt.Errorf("gnss status")
	}
	switch str(m["type"]) {
	case "location":
		if m["fix"] == nil || h["gnss_status"] != "fix" {
			return fmt.Errorf("location fix")
		}
	case "status":
		if m["fix"] != nil {
			return fmt.Errorf("status fix")
		}
	case "sos":
		s := object(m["sos"])
		if err := required(s, "event_id", "triggered_at"); err != nil {
			return err
		}
		if s["event_id"] != m["message_id"] || !stamp(s["triggered_at"]) {
			return fmt.Errorf("sos identity/time")
		}
	default:
		return fmt.Errorf("message type")
	}
	if m["type"] != "sos" {
		if _, ok := m["sos"]; ok {
			return fmt.Errorf("unexpected sos")
		}
	}
	if m["fix"] != nil {
		return checkFix(object(m["fix"]))
	}
	return nil
}
func checkAck(m map[string]any) error {
	if err := checkIdentity(m); err != nil {
		return err
	}
	if err := required(m, "result", "received_at", "config"); err != nil {
		return err
	}
	if (m["result"] != "stored" && m["result"] != "duplicate") || !stamp(m["received_at"]) {
		return fmt.Errorf("ack receipt")
	}
	return checkConfig(object(m["config"]), false)
}
func TestFixtures(t *testing.T) {
	files, err := filepath.Glob(filepath.Join(fixtures, "*.json"))
	if err != nil || len(files) == 0 {
		t.Fatal("missing fixtures", err)
	}
	for _, path := range files {
		name := filepath.Base(path)
		if strings.HasPrefix(name, "scenario-") {
			continue
		}
		t.Run(name, func(t *testing.T) {
			m := load(t, name)
			var err error
			if strings.HasPrefix(name, "ack-") {
				err = checkAck(m)
			} else {
				err = checkRequest(m)
			}
			if err != nil {
				t.Fatal(err)
			}
		})
	}
}
func TestScenarios(t *testing.T) {
	paths, _ := filepath.Glob(filepath.Join(fixtures, "scenario-*.json"))
	if len(paths) == 0 {
		t.Fatal("missing scenarios")
	}
	for _, path := range paths {
		t.Run(filepath.Base(path), func(t *testing.T) {
			scenario := load(t, filepath.Base(path))
			if str(scenario["name"]) == "" {
				t.Fatal("name")
			}
			steps, ok := scenario["steps"].([]any)
			if !ok || len(steps) == 0 {
				t.Fatal("steps")
			}
			seen := map[string]map[string]any{}
			for _, v := range steps {
				step := object(v)
				if !num(step["after_ms"], 0, maxInteger, true) {
					t.Fatal("delay")
				}
				for _, k := range []string{"request", "ack"} {
					name := str(step[k])
					if filepath.Base(name) != name || !strings.HasSuffix(name, ".json") {
						t.Fatal("fixture path")
					}
				}
				req, ack := load(t, str(step["request"])), load(t, str(step["ack"]))
				if err := checkRequest(req); err != nil {
					t.Fatal(err)
				}
				if err := checkAck(ack); err != nil {
					t.Fatal(err)
				}
				for _, k := range []string{"device_id", "message_id", "sequence"} {
					if req[k] != ack[k] {
						t.Fatalf("ACK mismatch: %s", k)
					}
				}
				key := str(req["device_id"]) + str(req["message_id"])
				if previous, ok := seen[key]; ok {
					if ack["result"] != "duplicate" || ack["received_at"] != previous["received_at"] {
						t.Fatal("duplicate semantics")
					}
				} else if ack["result"] != "stored" {
					t.Fatal("first receipt")
				}
				seen[key] = ack
			}
		})
	}
}
func TestRejectContractDrift(t *testing.T) {
	cases := map[string]func(map[string]any){
		"unknown version":        func(m map[string]any) { m["protocol_version"] = json.Number("2") },
		"fractional sequence":    func(m map[string]any) { m["sequence"] = json.Number("1.0") },
		"missing nullable field": func(m map[string]any) { delete(object(m["fix"]), "speed_mps") },
		"out of range latitude":  func(m map[string]any) { object(m["fix"])["latitude"] = json.Number("91") },
		"bearing upper bound":    func(m map[string]any) { object(m["fix"])["bearing_deg"] = json.Number("360.0") },
		"wrong precedence": func(m map[string]any) {
			object(m["config_state"])["effective_reporting_interval_s"] = json.Number("20")
		},
		"non multiple interval": func(m map[string]any) { object(m["config_state"])["local_reporting_interval_s"] = json.Number("6") },
		"timezone offset":       func(m map[string]any) { m["captured_at"] = "2026-10-06T12:00:00.000+00:00" },
		"unexpected SOS":        func(m map[string]any) { m["sos"] = map[string]any{} },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			m := load(t, "location-normal.json")
			mutate(m)
			if checkRequest(m) == nil {
				t.Fatal("accepted drift")
			}
		})
	}
	if _, err := decode([]byte(`{"type":"location","type":"status"}`)); err == nil {
		t.Fatal("accepted duplicate keys")
	}
	m := load(t, "location-normal.json")
	m["future_optional_field"] = true
	if err := checkRequest(m); err != nil {
		t.Fatal("additive compatibility", err)
	}
}
func TestFrozenExampleSemantics(t *testing.T) {
	current, backlog := load(t, "location-current.json"), load(t, "location-backlog.json")
	if str(object(current["fix"])["observed_at"]) <= str(object(backlog["fix"])["observed_at"]) {
		t.Fatal("backlog not older")
	}
	normal, renamed, second := load(t, "location-normal.json"), load(t, "location-renamed-party.json"), load(t, "location-second-device.json")
	if normal["device_id"] != renamed["device_id"] || object(normal["party"])["id"] == object(renamed["party"])["id"] {
		t.Fatal("rename ownership")
	}
	if normal["device_id"] == second["device_id"] || normal["message_id"] != second["message_id"] {
		t.Fatal("per-device identity example")
	}
	for _, name := range []string{"location-config-applied.json", "location-config-cleared.json"} {
		if err := checkConfig(object(load(t, name)["config_state"]), true); err != nil {
			t.Fatal(err)
		}
	}
}
func TestMarkdownLinksAndLayout(t *testing.T) {
	root := filepath.Join("..", "..")
	for _, dir := range []string{"android", "command", "protocol", "test-tools", "docs"} {
		if info, err := os.Stat(filepath.Join(root, dir)); err != nil || !info.IsDir() {
			t.Fatal("missing directory", dir)
		}
	}
	links := regexp.MustCompile(`\[[^\]]*\]\(([^)]+)\)`)
	err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() && d.Name() == ".git" {
			return filepath.SkipDir
		}
		if !strings.HasSuffix(path, ".md") {
			return nil
		}
		b, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		for _, match := range links.FindAllStringSubmatch(string(b), -1) {
			target := match[1]
			if strings.Contains(target, "://") {
				continue
			}
			if strings.Contains(target, "#") {
				return fmt.Errorf("anchor validation required: %s", target)
			}
			if _, err := os.Stat(filepath.Join(filepath.Dir(path), filepath.FromSlash(target))); err != nil {
				return fmt.Errorf("%s: %s: %w", path, target, err)
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

func strNumber(v any) string {
	if n, ok := v.(json.Number); ok {
		return string(n)
	}
	return ""
}
func TestNativeBatchContract(t *testing.T) {
	request := load(t, "history-batch-v1/request.json")
	if request["batch_version"] != json.Number("1") {
		t.Fatal("batch version")
	}
	messages, ok := request["messages"].([]any)
	if !ok || len(messages) == 0 {
		t.Fatal("batch messages")
	}
	ack := load(t, "history-batch-v1/ack.json")
	acks, ok := ack["acks"].([]any)
	if !ok || len(acks) != len(messages) {
		t.Fatal("batch ACK count")
	}
	for i, v := range messages {
		m := object(v)
		if e := checkRequest(m); e != nil {
			t.Fatal(e)
		}
		a := object(acks[i])
		if e := checkAck(a); e != nil {
			t.Fatal(e)
		}
		for _, key := range []string{"device_id", "message_id", "sequence"} {
			if m[key] != a[key] {
				t.Fatal("batch ACK mismatch", key)
			}
		}
	}
	for _, value := range []any{json.Number("0"), json.Number("1.0"), json.Number("9007199254740992")} {
		m := load(t, "location-native-session.json")
		object(m["observation"])["observation_sequence"] = value
		if checkRequest(m) == nil {
			t.Fatal("invalid native sequence accepted", value)
		}
	}
}

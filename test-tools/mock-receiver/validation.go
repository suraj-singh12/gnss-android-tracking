// Protocol validation for the isolated Android mock, frozen to v1.
package mockreceiver

import (
	"encoding/json"
	"fmt"
	"io"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const maxInteger = 9007199254740991

var uuid = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var integer = regexp.MustCompile(`^(0|[1-9][0-9]*|-[1-9][0-9]*)$`)

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

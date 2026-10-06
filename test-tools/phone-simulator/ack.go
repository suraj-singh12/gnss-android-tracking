package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"regexp"
	"strconv"
	"time"
	"unicode/utf8"
)

var uuid = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var integer = regexp.MustCompile(`^(0|[1-9][0-9]*)$`)

func ackValue(d *json.Decoder) (any, error) {
	v, err := d.Token()
	if err != nil {
		return nil, err
	}
	switch v {
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
				return nil, fmt.Errorf("duplicate ACK key %s", key)
			}
			value, err := ackValue(d)
			if err != nil {
				return nil, err
			}
			m[key] = value
		}
		_, err = d.Token()
		return m, err
	case json.Delim('['):
		for d.More() {
			if _, err := ackValue(d); err != nil {
				return nil, err
			}
		}
		_, err = d.Token()
		return []any{}, err
	default:
		return v, nil
	}
}
func ackInteger(v any, min int64) (int64, bool) {
	s, ok := v.(json.Number)
	if !ok || !integer.MatchString(string(s)) {
		return 0, false
	}
	n, err := strconv.ParseInt(string(s), 10, 64)
	return n, err == nil && n >= min && n <= 9007199254740991
}
func validateAck(b []byte) error {
	if !utf8.Valid(b) {
		return fmt.Errorf("ACK is not UTF-8")
	}
	d := json.NewDecoder(bytes.NewReader(b))
	d.UseNumber()
	v, err := ackValue(d)
	if err != nil {
		return err
	}
	if _, err = d.Token(); err != io.EOF {
		return fmt.Errorf("trailing ACK data")
	}
	m, ok := v.(map[string]any)
	if !ok {
		return fmt.Errorf("ACK must be an object")
	}
	if m["protocol_version"] != json.Number("1") {
		return fmt.Errorf("invalid ACK protocol version")
	}
	for _, k := range []string{"device_id", "message_id"} {
		s, _ := m[k].(string)
		if !uuid.MatchString(s) {
			return fmt.Errorf("invalid ACK %s", k)
		}
	}
	if _, ok := ackInteger(m["sequence"], 1); !ok {
		return fmt.Errorf("invalid ACK sequence")
	}
	if m["result"] != "stored" && m["result"] != "duplicate" {
		return fmt.Errorf("invalid ACK result")
	}
	received, _ := m["received_at"].(string)
	if t, err := time.Parse(stamp, received); err != nil || t.Format(stamp) != received {
		return fmt.Errorf("invalid ACK receipt timestamp")
	}
	c, ok := m["config"].(map[string]any)
	if !ok {
		return fmt.Errorf("ACK missing config")
	}
	authority, _ := c["authority_id"].(string)
	if !uuid.MatchString(authority) {
		return fmt.Errorf("invalid ACK authority")
	}
	version, ok := ackInteger(c["version"], 0)
	if !ok {
		return fmt.Errorf("invalid ACK config version")
	}
	override, ok := c["reporting_interval_override_s"]
	if !ok {
		return fmt.Errorf("ACK missing override")
	}
	if override != nil {
		n, ok := ackInteger(override, 10)
		if !ok || n > 86400 || n%10 != 0 || version == 0 {
			return fmt.Errorf("invalid ACK override")
		}
	}
	return nil
}

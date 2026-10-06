package core

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"time"
)

const wireTime = "2006-01-02T15:04:05.000Z"

func id() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = b[6]&15 | 64
	b[8] = b[8]&63 | 128
	return formatID(b[:])
}
func instant(s string) time.Time { t, _ := time.Parse(wireTime, s); return t }
func number(v any) float64       { n, _ := v.(json.Number); f, _ := n.Float64(); return f }

type Party struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}
type Config struct {
	Authority *string `json:"authority_id"`
	Version   int64   `json:"version"`
	Override  *int    `json:"reporting_interval_override_s"`
}
type ConfigState struct {
	Config
	Local     int `json:"local_reporting_interval_s"`
	Effective int `json:"effective_reporting_interval_s"`
}
type Health struct {
	Battery    *int   `json:"battery_percent"`
	Charging   *bool  `json:"charging"`
	Wifi       *bool  `json:"wifi_connected"`
	RSSI       *int   `json:"wifi_rssi_dbm"`
	GNSS       string `json:"gnss_status"`
	Satellites *int64 `json:"satellites_used"`
}
type Fix struct {
	Observed         string   `json:"observed_at"`
	Age              int64    `json:"fix_age_ms"`
	Lat              float64  `json:"latitude"`
	Lon              float64  `json:"longitude"`
	Accuracy         *float64 `json:"horizontal_accuracy_m"`
	Altitude         *float64 `json:"altitude_m"`
	AltitudeAccuracy *float64 `json:"altitude_accuracy_m"`
	Speed            *float64 `json:"speed_mps"`
	Bearing          *float64 `json:"bearing_deg"`
}
type SOS struct {
	EventID   string `json:"event_id"`
	Triggered string `json:"triggered_at"`
}
type Message struct {
	Protocol int         `json:"protocol_version"`
	Type     string      `json:"type"`
	Device   string      `json:"device_id"`
	Party    Party       `json:"party"`
	ID       string      `json:"message_id"`
	Sequence int64       `json:"sequence"`
	Captured string      `json:"captured_at"`
	Config   ConfigState `json:"config_state"`
	Health   Health      `json:"health"`
	Fix      *Fix        `json:"fix"`
	SOS      *SOS        `json:"sos,omitempty"`
}
type Ack struct {
	Protocol int    `json:"protocol_version"`
	Device   string `json:"device_id"`
	ID       string `json:"message_id"`
	Sequence int64  `json:"sequence"`
	Result   string `json:"result"`
	Received string `json:"received_at"`
	Config   Config `json:"config"`
}

// Exact-name selection ignores additive fields before typed decoding. encoding/json
// otherwise matches struct fields case-insensitively, which is unsafe for wire keys.
func Parse(b []byte) (Message, error) {
	var m Message
	v, err := decode(b)
	if err != nil {
		return m, err
	}
	if err = checkRequest(v); err != nil {
		return m, err
	}
	known := selectFields(v, "protocol_version", "type", "device_id", "party", "message_id", "sequence", "captured_at", "config_state", "health", "fix")
	known["party"] = selectFields(object(v["party"]), "id", "name")
	known["config_state"] = selectFields(object(v["config_state"]), "authority_id", "version", "reporting_interval_override_s", "local_reporting_interval_s", "effective_reporting_interval_s")
	known["health"] = selectFields(object(v["health"]), "battery_percent", "charging", "wifi_connected", "wifi_rssi_dbm", "gnss_status", "satellites_used")
	if v["fix"] != nil {
		known["fix"] = selectFields(object(v["fix"]), "observed_at", "fix_age_ms", "latitude", "longitude", "horizontal_accuracy_m", "altitude_m", "altitude_accuracy_m", "speed_mps", "bearing_deg")
	}
	if v["type"] == "sos" {
		known["sos"] = selectFields(object(v["sos"]), "event_id", "triggered_at")
	}
	canonical, err := json.Marshal(known)
	if err != nil {
		return m, err
	}
	err = json.Unmarshal(canonical, &m)
	if m.Fix != nil {
		f := m.Fix
		if f.Lat == 0 {
			f.Lat = 0
		}
		if f.Lon == 0 {
			f.Lon = 0
		}
		for _, p := range []*float64{f.Accuracy, f.Altitude, f.AltitudeAccuracy, f.Speed, f.Bearing} {
			if p != nil && *p == 0 {
				*p = 0
			}
		}
	}
	return m, err
}

func formatID(b []byte) string {
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func selectFields(m map[string]any, keys ...string) map[string]any {
	result := map[string]any{}
	for _, k := range keys {
		result[k] = m[k]
	}
	return result
}

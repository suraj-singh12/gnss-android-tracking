// Phone simulator replays immutable Protocol v1 fixture messages, never geography.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const stamp = "2006-01-02T15:04:05.000Z"

type step struct {
	After   int64  `json:"after_ms"`
	Request string `json:"request"`
	Ack     string `json:"ack"`
	Drop    bool   `json:"drop_response"`
}
type scenario struct {
	Name  string `json:"name"`
	Steps []step `json:"steps"`
}
type options struct {
	URL, Path, Device, Controls string
	Shift                       time.Duration
	Retries                     int
	RetryDelay                  time.Duration
}

func main() {
	var o options
	flag.StringVar(&o.URL, "url", "http://127.0.0.1:8080", "Command phone base URL")
	flag.StringVar(&o.Path, "scenario", "", "scenario JSON path")
	flag.StringVar(&o.Device, "device-id", "", "optional whole-scenario device UUID replacement")
	flag.StringVar(&o.Controls, "config-controls", "", "optional local Command URL to apply fixture config changes")
	flag.DurationVar(&o.Shift, "shift", 0, "consistent scenario timestamp shift, e.g. 6h")
	flag.IntVar(&o.Retries, "retries", 3, "transient retry count per step")
	flag.DurationVar(&o.RetryDelay, "retry-delay", time.Second, "retry delay")
	flag.Parse()
	if err := run(o); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func read(path string) (map[string]any, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var v map[string]any
	err = json.Unmarshal(b, &v)
	return v, err
}
func shift(v any, d time.Duration) {
	switch x := v.(type) {
	case map[string]any:
		for k, v := range x {
			if s, ok := v.(string); ok {
				if t, err := time.Parse(stamp, s); err == nil {
					x[k] = t.Add(d).UTC().Format(stamp)
				}
			} else {
				shift(v, d)
			}
		}
	case []any:
		for _, v := range x {
			shift(v, d)
		}
	}
}
func send(c *http.Client, url string, b []byte) (map[string]any, int, error) {
	req, err := http.NewRequest("POST", url, bytes.NewReader(b))
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	r, err := c.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer r.Body.Close()
	data, err := io.ReadAll(io.LimitReader(r.Body, 65537))
	if err != nil {
		return nil, r.StatusCode, err
	}
	if r.StatusCode == 200 && strings.HasSuffix(url, "/api/v1/messages") {
		if err := validateAck(data); err != nil {
			return nil, r.StatusCode, err
		}
	}
	var ack map[string]any
	err = json.Unmarshal(data, &ack)
	return ack, r.StatusCode, err
}
func run(o options) error {
	if o.Path == "" || o.Retries < 0 || o.RetryDelay < 0 {
		return errors.New("provide -scenario and nonnegative retry settings")
	}
	b, err := os.ReadFile(o.Path)
	if err != nil {
		return err
	}
	var script scenario
	if err = json.Unmarshal(b, &script); err != nil {
		return err
	}
	if script.Name == "" || len(script.Steps) == 0 {
		return errors.New("scenario needs a name and steps")
	}
	client := &http.Client{Timeout: 10 * time.Second}
	cached := map[string][]byte{}
	first := map[string]string{}
	versions := map[string]float64{}
	authority := ""
	for i, s := range script.Steps {
		if s.After < 0 || s.After > 86400000 {
			return fmt.Errorf("step %d: invalid delay", i+1)
		}
		time.Sleep(time.Duration(s.After) * time.Millisecond)
		request, err := read(filepath.Join(filepath.Dir(o.Path), s.Request))
		if err != nil {
			return fmt.Errorf("step %d request: %w", i+1, err)
		}
		expected, err := read(filepath.Join(filepath.Dir(o.Path), s.Ack))
		if err != nil {
			return err
		}
		shift(request, o.Shift)
		if o.Device != "" {
			request["device_id"] = o.Device
		}
		key := fmt.Sprint(request["device_id"]) + "/" + fmt.Sprint(request["message_id"])
		if o.Controls != "" {
			config, ok := expected["config"].(map[string]any)
			if !ok {
				return errors.New("fixture config missing")
			}
			desired, ok := config["version"].(float64)
			if !ok {
				return errors.New("fixture config version missing")
			}
			device := fmt.Sprint(request["device_id"])
			for versions[device] < desired {
				control, _ := json.Marshal(map[string]any{"device_id": device, "reporting_interval_override_s": config["reporting_interval_override_s"]})
				response, status, e := send(client, o.Controls+"/local/override", control)
				if e != nil || status != 200 {
					return fmt.Errorf("config control: HTTP %d, %v, %v", status, response, e)
				}
				versions[device]++
			}
			if cs, ok := request["config_state"].(map[string]any); ok && cs["authority_id"] != nil && authority != "" {
				cs["authority_id"] = authority
			}
		}
		payload := cached[key]
		if payload == nil {
			payload, err = json.Marshal(request)
			if err != nil {
				return err
			}
			cached[key] = payload
		} else {
			if err = json.Unmarshal(payload, &request); err != nil {
				return err
			}
		}
		var ack map[string]any
		var status int
		dropped := false
		for attempt := 0; attempt <= o.Retries; attempt++ {
			ack, status, err = send(client, o.URL+"/api/v1/messages", payload)
			if err == nil && status == 200 {
				if s.Drop && !dropped {
					dropped = true
					err = errors.New("scripted response loss")
				} else {
					break
				}
			} else if status != 0 && status != 429 && status < 500 {
				return fmt.Errorf("step %d: HTTP %d: %v", i+1, status, ack)
			}
			if attempt < o.Retries {
				time.Sleep(o.RetryDelay)
			}
		}
		if err != nil || status != 200 {
			return fmt.Errorf("step %d: receipt failed: HTTP %d: %v", i+1, status, err)
		}
		for _, k := range []string{"protocol_version", "device_id", "message_id", "sequence"} {
			if ack[k] != request[k] {
				return fmt.Errorf("step %d: ACK %s mismatch: got %v want %v", i+1, k, ack[k], request[k])
			}
		}
		want := expected["result"]
		if dropped {
			want = "duplicate"
		}
		if ack["result"] != want {
			return fmt.Errorf("step %d: ACK result got %v want %v", i+1, ack["result"], want)
		}
		received, ok := ack["received_at"].(string)
		if !ok {
			return errors.New("ACK missing receipt")
		}
		if t, e := time.Parse(stamp, received); e != nil || t.Format(stamp) != received {
			return errors.New("ACK invalid receipt timestamp")
		}
		if previous, ok := first[key]; ok && received != previous {
			return fmt.Errorf("step %d: duplicate changed first received_at", i+1)
		}
		first[key] = received
		config, ok := ack["config"].(map[string]any)
		if !ok {
			return errors.New("ACK missing config")
		}
		a, ok := config["authority_id"].(string)
		if !ok || len(a) != 36 {
			return errors.New("ACK invalid authority")
		}
		authority = a
		v, ok := config["version"].(float64)
		if !ok || v < 0 || v != float64(int64(v)) {
			return errors.New("ACK invalid version")
		}
		override, present := config["reporting_interval_override_s"]
		if !present {
			return errors.New("ACK missing override")
		}
		if override != nil {
			n, ok := override.(float64)
			if !ok || n < 10 || n > 86400 || n != float64(int(n)) || int(n)%10 != 0 {
				return errors.New("ACK invalid override")
			}
		}
		if o.Controls != "" {
			ec := expected["config"].(map[string]any)
			if config["version"] != ec["version"] || override != ec["reporting_interval_override_s"] {
				return fmt.Errorf("step %d: desired config mismatch", i+1)
			}
		}
		fmt.Printf("step %d: %s %s · %s\n", i+1, request["device_id"], ack["result"], received)
	}
	fmt.Printf("PASS: %s (%d steps)\n", script.Name, len(script.Steps))
	return nil
}

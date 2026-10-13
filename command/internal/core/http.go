package core

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"
)

func respond(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func failure(w http.ResponseWriter, status int, code string, err error) {
	respond(w, status, map[string]any{"protocol_version": 1, "error": code, "message": err.Error()})
}
func (s *Store) IngestHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ingress := s.Now().UTC()
		if r.URL.Path == "/api/v1/history" || r.URL.Path == "/api/v1/capabilities" {
			s.historyHandler(w, r)
			return
		}
		if r.URL.Path != "/api/v1/messages" {
			failure(w, 404, "unsupported_endpoint", errors.New("unknown endpoint"))
			return
		}
		if r.Method != "POST" {
			w.Header().Set("Allow", "POST")
			failure(w, 405, "method_not_allowed", errors.New("use POST"))
			return
		}
		media, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if err != nil || media != "application/json" {
			failure(w, 415, "unsupported_media_type", errors.New("use application/json"))
			return
		}
		b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 65536))
		if err != nil {
			var size *http.MaxBytesError
			if errors.As(err, &size) {
				failure(w, 413, "message_too_large", err)
			} else {
				failure(w, 400, "invalid_message", err)
			}
			return
		}
		v, err := decode(b)
		if err != nil {
			failure(w, 400, "invalid_message", err)
			return
		}
		if num(v["protocol_version"], 0, maxInteger, true) && number(v["protocol_version"]) != 1 {
			failure(w, 426, "unsupported_protocol", errors.New("supported body version is 1"))
			return
		}
		if _, err = Parse(b); err != nil {
			failure(w, 400, "invalid_message", err)
			return
		}
		role := "live"
		if r.Header.Get("X-GNSS-Delivery-Role") == "history" {
			role = "history"
		}
		ack, err := s.ingestRole(b, ingress, role)
		if err != nil {
			if errors.Is(err, ErrConflict) {
				failure(w, 409, "identity_conflict", err)
			} else {
				failure(w, 503, "storage_unavailable", err)
			}
			return
		}
		respond(w, 200, ack)
	})
}

// Only the separately bound local listener serves controls. Browser mutations must
// be JSON and same-origin; no CORS is granted to a remote webpage.
func (s *Store) LocalHandler(assets http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; object-src 'none'; frame-ancestors 'none'")
		if !strings.HasPrefix(r.URL.Path, "/local/") {
			assets.ServeHTTP(w, r)
			return
		}
		if r.URL.Path == "/local/field-report" && r.Method == "GET" {
			var b bytes.Buffer
			if err := s.ExportFieldReport(&b); err != nil {
				failure(w, 503, "storage_unavailable", err)
				return
			}
			w.Header().Set("Content-Type", "application/zip")
			w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=gnss-field-report-%s.zip", s.Now().UTC().Format("20060102T150405Z")))
			w.Header().Set("Cache-Control", "no-store")
			_, _ = w.Write(b.Bytes())
			return
		}
		if r.URL.Path == "/local/state" && r.Method == "GET" {
			dot := 30
			if v := r.URL.Query().Get("dot_interval_s"); v != "" {
				n, err := strconv.Atoi(v)
				if err != nil || n < 10 || n > 86400 || n%10 != 0 {
					failure(w, 400, "invalid_setting", errors.New("invalid dot interval"))
					return
				}
				dot = n
			}
			st, err := s.Snapshot(dot)
			if err != nil {
				failure(w, 503, "storage_unavailable", err)
				return
			}
			respond(w, 200, dashboardView(st, s.Now()))
			return
		}
		if r.Method != "POST" {
			w.Header().Set("Allow", "POST")
			failure(w, 405, "method_not_allowed", errors.New("use POST"))
			return
		}
		if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
			failure(w, 403, "forbidden", errors.New("same-origin controls only"))
			return
		}
		media, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if err != nil || media != "application/json" {
			failure(w, 415, "unsupported_media_type", errors.New("use application/json"))
			return
		}
		b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 65536))
		if err != nil {
			failure(w, 400, "invalid_setting", err)
			return
		}
		v, err := decode(b)
		if err != nil {
			failure(w, 400, "invalid_setting", err)
			return
		}
		switch r.URL.Path {
		case "/local/sos/acknowledge":
			err = s.AcknowledgeSOS(str(v["device_id"]), str(v["event_id"]))
		case "/local/recording":
			if str(v["action"]) == "clear" && v["confirmed"] != true {
				err = errors.New("clear requires explicit confirmation")
			} else {
				mode := str(v["mode"])
				if mode == "" {
					mode = "from_now"
				}
				err = s.ActionMode(str(v["action"]), mode)
			}
		case "/local/quality":
			var p Policy
			err = json.Unmarshal(b, &p)
			if err == nil && v["enabled"] != nil {
				switches, ok := v["enabled"].(map[string]any)
				if !ok {
					err = errors.New("quality enabled must be an object")
				} else {
					for _, enabled := range switches {
						if _, ok := enabled.(bool); !ok {
							err = errors.New("quality switches must be booleans")
							break
						}
					}
				}
			}
			if err == nil {
				err = s.SetPolicy(p)
			}
		case "/local/override":
			if _, ok := v["reporting_interval_override_s"]; !ok {
				err = errors.New("override field required")
			} else if v["reporting_interval_override_s"] == nil {
				err = s.SetOverride(str(v["device_id"]), nil)
			} else if !interval(v["reporting_interval_override_s"]) {
				err = errors.New("invalid reporting interval")
			} else {
				n := int(number(v["reporting_interval_override_s"]))
				err = s.SetOverride(str(v["device_id"]), &n)
			}
		default:
			failure(w, 404, "unsupported_endpoint", errors.New("unknown control"))
			return
		}
		if err != nil {
			failure(w, 400, "invalid_setting", err)
			return
		}
		respond(w, 200, map[string]bool{"ok": true})
	})
}

// Dashboard views deliberately omit raw envelopes, decisions and window internals.
func dashboardView(st State, now time.Time) map[string]any {
	devices := map[string]any{}
	for id, d := range st.Devices {
		var location any
		if d.Location != nil {
			location = map[string]any{"fix": d.Location.Fix}
		}
		devices[id] = map[string]any{"device_id": id, "track_color": d.Color, "track_dash": d.Dash, "snapshot": map[string]any{"party": d.Snapshot.Party, "health": d.Snapshot.Health, "config_state": d.Snapshot.Config}, "location": location, "current_position": d.CurrentPosition, "live_x_m": d.LiveX, "live_y_m": d.LiveY, "location_reason": d.LocationReason, "last_contact": d.Contact, "desired_config": d.Desired, "config_converged": d.Converged, "contact_condition": d.ContactCondition, "gnss_condition": d.GNSSCondition, "location_age_s": d.LocationAge, "total_m": d.Total, "field_evidence": d.Evidence, "history": d.History}
	}
	var recording any
	if st.Recording != nil {
		// Duration is derived here from authoritative recording windows, never by the browser.
		seconds := 0.0
		for _, window := range st.Recording.Windows {
			end := now
			if window.Stop != nil {
				end = *window.Stop
			}
			if end.After(window.Start) {
				seconds += end.Sub(window.Start).Seconds()
			}
		}
		recording = map[string]any{"recording_id": st.Recording.ID, "active": st.Recording.Active, "mode": st.Recording.Mode, "duration_s": seconds}
	}
	return map[string]any{"sos_alerts": st.Alerts, "devices": devices, "recording": recording, "policy": st.Policy, "points": st.Points, "raw_points": st.RawPoints, "provisional_points": st.Provisional, "projection_pending": st.ProjectionPending, "projection_error": st.ProjectionError}
}

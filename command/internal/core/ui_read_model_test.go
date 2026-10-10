package core

import (
	"testing"
	"time"
)

func TestDashboardDurationUsesRecordingWindows(t *testing.T) {
	start := time.Date(2026, 10, 10, 1, 0, 0, 0, time.UTC)
	stop := start.Add(20 * time.Second)
	st := State{Recording: &Recording{ID: "test", Active: true, Mode: "from_now", Windows: []Window{{Start: start, Stop: &stop}, {Start: start.Add(40 * time.Second)}}}}
	view := dashboardView(st, start.Add(50*time.Second))["recording"].(map[string]any)
	if view["duration_s"] != 30.0 || view["mode"] != "from_now" {
		t.Fatalf("duration/mode: %v", view)
	}
	stopped := start.Add(45 * time.Second)
	st.Recording.Windows[1].Stop = &stopped
	st.Recording.Active = false
	view = dashboardView(st, start.Add(time.Hour))["recording"].(map[string]any)
	if view["duration_s"] != 25.0 {
		t.Fatalf("stopped duration changed: %v", view)
	}
	st.Recording = nil
	if dashboardView(st, start)["recording"] != nil {
		t.Fatal("absent recording must remain absent")
	}
}
func TestDashboardSessionAvailabilityUsesExistingMetadata(t *testing.T) {
	st := State{Devices: map[string]*Device{"one": {}}}
	if dashboardView(st, time.Now())["recording_session_available"] != false {
		t.Fatal("invented session")
	}
	st.Devices["one"].Session = &ReportedSession{ID: "existing", Start: "2026-10-10T01:00:00.000Z"}
	if dashboardView(st, time.Now())["recording_session_available"] != true {
		t.Fatal("reported session hidden")
	}
}

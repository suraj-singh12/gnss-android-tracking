package core

import (
	"crypto/sha256"
	"fmt"
	"math"
	"sort"
	"time"
)

const Radius = 6371008.8
const radians = math.Pi / 180

type Policy struct {
	Revision    int64   `json:"revision"`
	Forward     float64 `json:"minimum_forward_m"`
	Backward    float64 `json:"minimum_backward_m"`
	Accuracy    float64 `json:"maximum_accuracy_m"`
	Age         float64 `json:"maximum_fix_age_s"`
	Clock       float64 `json:"clock_tolerance_s"`
	Speed       float64 `json:"maximum_speed_mps"`
	Gap         float64 `json:"maximum_gap_s"`
	Uncertainty float64 `json:"uncertainty_multiplier"`
}

func DefaultPolicy() Policy { return Policy{1, 2, 2, 25, 30, 5, 12, 120, 1} }
func (p Policy) Validate() error {
	if p.Uncertainty < 1 {
		return fmt.Errorf("uncertainty multiplier must be at least 1")
	}
	for _, v := range []float64{p.Forward, p.Backward, p.Accuracy, p.Age, p.Clock, p.Speed, p.Gap, p.Uncertainty} {
		if math.IsNaN(v) || math.IsInf(v, 0) || v <= 0 || v > 86400 {
			return fmt.Errorf("quality values must be finite, positive and at most 86400")
		}
	}
	return nil
}
func wrap(v float64) float64 { return math.Mod(v+540, 360) - 180 }
func Distance(a, b Fix) float64 {
	dlat := (b.Lat - a.Lat) * radians
	dlon := wrap(b.Lon-a.Lon) * radians
	h := math.Sin(dlat/2)*math.Sin(dlat/2) + math.Cos(a.Lat*radians)*math.Cos(b.Lat*radians)*math.Sin(dlon/2)*math.Sin(dlon/2)
	return 2 * Radius * math.Asin(math.Sqrt(math.Min(1, math.Max(0, h))))
}

// Geographic headings derive solely from accepted coordinates, never from the
// display projection or the platform's noisy instantaneous GNSS bearing.
func heading(a, b Fix) float64 {
	delta := wrap(b.Lon-a.Lon) * radians
	return math.Atan2(math.Sin(delta)*math.Cos(b.Lat*radians), math.Cos(a.Lat*radians)*math.Sin(b.Lat*radians)-math.Sin(a.Lat*radians)*math.Cos(b.Lat*radians)*math.Cos(delta))
}
func Project(f, origin Fix) (float64, float64) {
	return Radius * wrap(f.Lon-origin.Lon) * radians * math.Cos(origin.Lat*radians), Radius * (f.Lat - origin.Lat) * radians
}
func Quality(m Message, p Policy) string {
	if m.Fix == nil {
		return "no_fix"
	}
	f := m.Fix
	if f.Accuracy == nil {
		return "unknown_accuracy"
	}
	if *f.Accuracy > p.Accuracy {
		return "poor_accuracy"
	}
	if float64(f.Age)/1000 > p.Age {
		return "stale_at_capture"
	}
	elapsed := instant(m.Captured).Sub(instant(f.Observed)).Seconds()
	if math.Abs(elapsed-float64(f.Age)/1000) > p.Clock {
		return "clock_anomaly"
	}
	return "valid"
}

type Candidate struct {
	Message
	Received string
}
type Point struct {
	Device   string  `json:"device_id"`
	Message  string  `json:"message_id"`
	Sequence int64   `json:"sequence"`
	Fix      Fix     `json:"fix"`
	Segment  string  `json:"segment_id"`
	Reason   string  `json:"segment_reason"`
	Revision int64   `json:"policy_revision"`
	Distance float64 `json:"cumulative_m"`
	X        float64 `json:"x_m"`
	Y        float64 `json:"y_m"`
	Dot      bool    `json:"dot"`
}
type Decision struct {
	Device   string `json:"device_id"`
	Message  string `json:"message_id"`
	Reason   string `json:"reason"`
	Revision int64  `json:"policy_revision"`
	Segment  string `json:"segment_id"`
}

func less(a, b Message, observation bool) bool {
	ta, tb := a.Captured, b.Captured
	if observation {
		ta, tb = a.Fix.Observed, b.Fix.Observed
	}
	if ta != tb {
		return ta < tb
	}
	if a.Sequence != b.Sequence {
		return a.Sequence < b.Sequence
	}
	return a.ID < b.ID
}

// Derive operates on one device/window. It never consults packet arrival order.
func Derive(c []Candidate, p Policy, window string, total float64) ([]Point, []Decision) {
	sort.Slice(c, func(i, j int) bool { return less(c[i].Message, c[j].Message, true) })
	points := []Point{}
	decisions := []Decision{}
	var anchor *Point
	recent := []Point{}
	seen := ""
	pending := "window_start"
	for _, raw := range c {
		m := raw.Message
		f := *m.Fix
		reason := Quality(m, p)
		if f.Observed == seen {
			reason = "same_observation_time"
		} else {
			seen = f.Observed
		}
		// Arrival is only used to reject future clock anomalies, never backlog age.
		if reason == "valid" && instant(m.Captured).After(instant(raw.Received).Add(time.Duration(p.Clock*float64(time.Second)))) {
			reason = "future_capture"
		}
		if reason != "valid" {
			if reason != "same_observation_time" {
				pending = reason
			}
			decisions = append(decisions, Decision{m.Device, m.ID, reason, p.Revision, window})
			continue
		}
		d := 0.0
		dt := 0.0
		if anchor != nil {
			d = Distance(anchor.Fix, f)
			dt = instant(f.Observed).Sub(instant(anchor.Fix.Observed)).Seconds()
			if dt <= 0 {
				reason = "same_observation_time"
			} else if math.Max(0, d-(*anchor.Fix.Accuracy+*f.Accuracy))/dt > p.Speed {
				reason = "implausible_speed"
				pending = reason
			} else if pending == "" && dt > p.Gap {
				pending = "time_gap"
			}
		}
		if reason != "valid" {
			decisions = append(decisions, Decision{m.Device, m.ID, reason, p.Revision, window})
			continue
		}
		// Keep the last credible anchor across bad fixes for speed checks. Open a new
		// distance subsegment on recovery; a bad fix is never a reset-to-anywhere.
		segmentReason := ""
		if pending != "" {
			segmentReason = pending
			anchor = nil
			recent = nil
			pending = ""
			d = 0
		}
		floor := math.Max(p.Forward, p.Backward)
		if anchor != nil {
			if len(recent) >= 3 {
				first := recent[0]
				last := recent[len(recent)-1]
				net := Distance(first.Fix, last.Fix)
				if net >= math.Max(p.Forward, p.Backward) && d > 0 {
					cos := math.Cos(heading(first.Fix, last.Fix) - heading(anchor.Fix, f))
					if cos > 0.5 {
						floor = p.Forward
					} else if cos < -0.5 {
						floor = p.Backward
					}
				}
			}
			threshold := math.Max(floor, p.Uncertainty*math.Hypot(*anchor.Fix.Accuracy, *f.Accuracy))
			if d < threshold {
				decisions = append(decisions, Decision{m.Device, m.ID, "below_movement_threshold", p.Revision, window})
				continue
			}
			total += d
		}
		seg := segmentID(window, m.Device, m.ID)
		if anchor != nil {
			seg = anchor.Segment
		}
		point := Point{Device: m.Device, Message: m.ID, Sequence: m.Sequence, Fix: f, Segment: seg, Reason: segmentReason, Revision: p.Revision, Distance: total}
		points = append(points, point)
		anchor = &points[len(points)-1]
		recent = append(recent, point)
		if len(recent) > 4 {
			recent = recent[1:]
		}
		decisions = append(decisions, Decision{m.Device, m.ID, "accepted", p.Revision, seg})
	}
	return points, decisions
}

// Presentation copies are projected and annotated; authoritative points stay geographic.
func Present(points []Point, interval int) []Point {
	out := append([]Point{}, points...)
	if len(out) == 0 {
		return out
	}
	origin := out[0]
	for _, p := range out {
		if originLess(p, origin) {
			origin = p
		}
	}
	first := map[string]time.Time{}
	next := map[string]int64{}
	for i := range out {
		p := &out[i]
		p.X, p.Y = Project(p.Fix, origin.Fix)
		t := instant(p.Fix.Observed)
		start, ok := first[p.Segment]
		if !ok {
			first[p.Segment] = t
			p.Dot = true
			next[p.Segment] = 1
			continue
		}
		elapsed := int64(t.Sub(start) / time.Second)
		bucket := elapsed / int64(interval)
		if bucket >= next[p.Segment] {
			p.Dot = true
			next[p.Segment] = bucket + 1
		}
	}
	return out
}
func originLess(a, b Point) bool {
	if a.Fix.Observed != b.Fix.Observed {
		return a.Fix.Observed < b.Fix.Observed
	}
	if a.Device != b.Device {
		return a.Device < b.Device
	}
	if a.Sequence != b.Sequence {
		return a.Sequence < b.Sequence
	}
	return a.Message < b.Message
}

// Subsegment UUIDs are deterministically owned by Command, never phone fields.
func segmentID(window, device, first string) string {
	b := sha256.Sum256([]byte(window + "/" + device + "/" + first))
	b[6] = b[6]&15 | 80
	b[8] = b[8]&63 | 128
	return formatID(b[:16])
}

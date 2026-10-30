package core

import (
	"context"
	"fmt"
	"reflect"
	"testing"
	"time"
)

func TestTravelOriginalHistoryIndependentOfRecordingAndRefresh(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Hour)
	session := id()
	for _, n := range []int{1, 3, 4} {
		ingest(t, s, observationMessage(t, n, session))
	}
	read := func() TravelView {
		t.Helper()
		v, e := s.travelView(context.Background(), base.Format(time.RFC3339Nano), base.Add(time.Minute).Format(time.RFC3339Nano))
		if e != nil {
			t.Fatal(e)
		}
		return v
	}
	first := read()
	if len(first.Points) != 3 || first.Points[0].Segment == first.Points[1].Segment {
		t.Fatalf("missing-sequence gap lost: %+v", first.Points)
	}
	frozen := append([]Point{}, first.Points...)
	ingest(t, s, observationMessage(t, 2, session))
	if !reflect.DeepEqual(frozen, first.Points) {
		t.Fatal("snapshot changed on arrival")
	}
	next := read()
	if len(next.Points) != 4 || next.Ceiling <= first.Ceiling || next.RetainedCount <= first.RetainedCount {
		t.Fatal("manual refresh omitted late original")
	}
	action(t, s, "start")
	action(t, s, "pause")
	if len(read().Points) != 4 {
		t.Fatal("paused recording restricted original history")
	}
}
func TestTravelRangeQualityAndFleet(t *testing.T) {
	for _, count := range []int{1, 5, 21} {
		t.Run(fmt.Sprintf("%d_phones", count), func(t *testing.T) {
			s, now, _ := open(t)
			*now = base.Add(time.Hour)
			for i := 0; i < count; i++ {
				d := id()
				session := id()
				for n := 1; n <= 3; n++ {
					m := observationMessage(t, n, session)
					m.Device = d
					m.ID = id()
					m.Observation.ID = m.ID
					if n == 2 {
						a := 1000.
						m.Fix.Accuracy = &a
					}
					ingest(t, s, m)
				}
			}
			v, err := s.travelView(context.Background(), base.Format(time.RFC3339Nano), base.Add(time.Minute).Format(time.RFC3339Nano))
			if err != nil {
				t.Fatal(err)
			}
			if len(v.Originals) != count*3 || len(v.Points) != count*2 {
				t.Fatalf("fleet/quality lost: originals=%d points=%d", len(v.Originals), len(v.Points))
			}
			if len(v.Decisions) < count {
				t.Fatal("rejection evidence omitted")
			}
			if _, err = s.travelView(context.Background(), "invalid", base.Format(time.RFC3339Nano)); err == nil {
				t.Fatal("invalid boundary accepted")
			}
		})
	}
}

func TestSOSLocateUsesLastCredibleOriginalOutsideRecording(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Hour)
	good := message(t, 1, 1, 0, 0, 3)
	ingest(t, s, good)
	ingest(t, s, message(t, 2, 2, 10, 0, 1000))
	ingest(t, s, message(t, 3, 7200, 20, 0, 3))
	fix, err := s.lastCredibleLocation(context.Background(), device)
	if err != nil {
		t.Fatal(err)
	}
	if fix == nil || fix.Observed != good.Fix.Observed {
		t.Fatal("rejected latest report replaced credible original", fix)
	}
	if s.Now().Sub(instant(fix.Observed)) < time.Minute {
		t.Fatal("historical original became fresh")
	}
	if _, err = s.lastCredibleLocation(context.Background(), "invalid"); err == nil {
		t.Fatal("invalid identity accepted")
	}
}

func TestSOSLocateBypassesHistoricalReadConnection(t *testing.T) {
	s, now, _ := open(t)
	*now = base.Add(time.Minute)
	ingest(t, s, message(t, 1, 1, 0, 0, 3))
	s.viewDB.SetMaxOpenConns(1)
	held, err := s.viewDB.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer held.Rollback()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	fix, err := s.lastCredibleLocation(ctx, device)
	if err != nil || fix == nil {
		t.Fatal("SOS Locate waited behind historical inspection", fix, err)
	}
}

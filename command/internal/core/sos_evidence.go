package core

// Each verdict contains actual persisted evidence. No inference from an empty report.
func analyzeSOS(r *FieldReport, st State) {
	for _, a := range st.Alerts {
		ref := sosRef(a.Event)
		events := []Evidence{}
		stored, duplicate, restored, ackRestored := false, false, false, false
		for _, e := range r.Events {
			if e.SOSRef != ref || e.Device != a.Device {
				continue
			}
			events = append(events, e)
			if e.Kind == "stored" {
				stored = true
			}
			if e.Kind == "duplicate" && e.FirstReceived == a.Received {
				duplicate = true
			}
			if e.Kind == "sos_restored" && e.FirstReceived == a.Received {
				restored = true
			}
			if e.Kind == "sos_restored" && a.Acknowledged != nil && e.SOSAcknowledged != nil && *e.SOSAcknowledged == *a.Acknowledged {
				ackRestored = true
			}
		}
		add := func(name string, pass bool, reason string) {
			result := "INCONCLUSIVE"
			if pass {
				result = "PASS"
			}
			r.Verdicts = append(r.Verdicts, Verdict{Scenario: name, Result: result, Reason: reason,
				Evidence: map[string]any{"event_ref": ref, "events": events}})
		}
		add("SOS saved locally", false, "Requires Android export; Command receipt cannot prove trigger-to-Room chronology")
		add("SOS preempted backlog", false, "Requires Android selection evidence; arrival order alone cannot prove queue placement")
		add("SOS delivered after reconnect", false, "Requires Android offline evidence and matching ACK; Command cannot prove Wi-Fi state")
		add("SOS retries deduplicated", stored && duplicate, "Requires original stored and identical duplicate evidence with unchanged first receipt and one persistent event")
		add("Operator ACK persisted", ackRestored, "Requires acknowledgement timestamp restored after a persisted database-open boundary")
		add("SOS survived restart", stored && restored, "Requires original stored evidence and later database-open restoration with unchanged receipt")
	}
}

// Redact SOS identities from the exported view only. Internal analysis still uses the
// established raw dedupe keys; tracking evidence keeps its existing correlation contract.
func redactSOSEvidence(e Evidence) Evidence {
	if e.SOSRef != "" {
		e.Device = ""
		e.Message = ""
		e.ConflictingMessage = ""
	}
	return e
}
func redactSOSReport(r *FieldReport) {
	// A device with only SOS traffic has no pre-existing tracking correlation
	// contract. Do not leak its installation UUID through aggregate keys or controls.
	trackingDevices := map[string]bool{}
	for _, receipt := range r.Receipts {
		if receipt.Type != "sos" {
			trackingDevices[receipt.Device] = true
		}
	}
	sosDevices := map[string]string{}
	for _, e := range r.Events {
		if e.SOSRef != "" && e.Device != "" && !trackingDevices[e.Device] {
			sosDevices[e.Device] = "sos-device-" + sosRef(e.Device)
		}
	}
	for device, opaque := range sosDevices {
		if h, ok := r.History[device]; ok {
			delete(r.History, device)
			r.History[opaque] = h
		}
		if h, ok := r.HistorySessions[device]; ok {
			delete(r.HistorySessions, device)
			r.HistorySessions[opaque] = h
		}
		if counters, ok := r.Devices[device]; ok {
			delete(r.Devices, device)
			r.Devices[opaque] = counters
		}
	}
	redactRecording := func(recording *RecordingEvidence) {
		if recording == nil {
			return
		}
		for device, opaque := range sosDevices {
			if recording.Recording != nil {
				for i := range recording.Recording.Windows {
					sessions := recording.Recording.Windows[i].Sessions
					if session, ok := sessions[device]; ok {
						delete(sessions, device)
						sessions[opaque] = session
					}
				}
			}
			if distance, ok := recording.Distances[device]; ok {
				delete(recording.Distances, device)
				recording.Distances[opaque] = distance
			}
		}
	}
	redactRecording(r.Recording)
	for i := range r.Events {
		redactRecording(r.Events[i].Before)
		redactRecording(r.Events[i].After)
		if opaque, ok := sosDevices[r.Events[i].Device]; ok {
			r.Events[i].Device = opaque
		}
		r.Events[i] = redactSOSEvidence(r.Events[i])
	}
	for i := range r.Receipts {
		r.Receipts[i].Evidence = redactSOSEvidence(r.Receipts[i].Evidence)
	}
	for i := range r.Verdicts {
		if opaque, ok := sosDevices[r.Verdicts[i].Device]; ok {
			r.Verdicts[i].Device = opaque
		}
		if v, ok := r.Verdicts[i].Evidence.(map[string]any); ok {
			if events, ok := v["events"].([]Evidence); ok {
				for j := range events {
					events[j] = redactSOSEvidence(events[j])
				}
				v["events"] = events
			}
		}
	}
}

package core

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
)

var errHistoryBusy = errors.New("one historical request per device is already in flight")

type BatchError struct {
	Index                     int
	Observation, Code, Detail string
}

func (e *BatchError) Error() string { return e.Detail }

type BatchAck struct {
	Version int   `json:"batch_version"`
	Acks    []Ack `json:"acks"`
}

func (s *Store) IngestBatch(b []byte) (out BatchAck, resultError error) {
	at := s.Now().UTC().Format(wireTime)
	defer func() {
		var bad *BatchError
		if errors.As(resultError, &bad) {
			s.recordBatchRejection(b, bad, at)
		}
	}()
	out = BatchAck{Version: 1, Acks: []Ack{}}
	if len(b) > s.historyBatchBytes {
		return out, &BatchError{-1, "", "invalid_batch", "batch exceeds negotiated receiver limit"}
	}
	v, e := decode(b)
	if e != nil {
		return out, e
	}
	if !num(v["batch_version"], 1, 1, true) {
		return out, &BatchError{-1, "", "unsupported_batch", "unsupported batch version"}
	}
	a, ok := v["messages"].([]any)
	if !ok || len(a) == 0 || len(a) > s.historyBatchRecords {
		return out, &BatchError{-1, "", "invalid_batch", "batch exceeds negotiated record limit"}
	}
	messages := []Message{}
	wires := [][]byte{}
	device, session := "", ""
	for i, x := range a {
		wire, _ := json.Marshal(x)
		// Negotiation enlarges the batch, not the immutable individual-v1 envelope.
		// Keep paged reconstruction RAM bounded even for non-Android peers.
		if len(wire) > 65536 {
			return out, &BatchError{i, str(object(object(x)["observation"])["observation_id"]), "invalid_observation", "individual envelope exceeds 65536 bytes"}
		}
		m, err := parseObject(object(x))
		if err != nil {
			return out, &BatchError{i, str(object(object(x)["observation"])["observation_id"]), "invalid_observation", err.Error()}
		}
		if m.Type != "location" || m.Observation == nil {
			return out, &BatchError{i, m.ID, "invalid_observation", "observation identity required"}
		}
		if i == 0 {
			device, session = m.Device, m.Observation.Session
		}
		if m.Device != device || m.Observation.Session != session {
			return out, &BatchError{i, m.ID, "invalid_observation", "mixed device/session"}
		}
		messages = append(messages, m)
		wires = append(wires, wire)
	}
	// At most one history waiter reaches the SQLite writer. Fresh LIVE/SOS do
	// not queue behind the entire fleet. The gate is variable-fleet admission,
	// not a device-count limit or a second delivery queue.
	s.historyAdmission.Lock()
	if s.historyDevices[device] {
		s.historyAdmission.Unlock()
		return out, errHistoryBusy
	}
	s.historyDevices[device] = true
	s.historyAdmission.Unlock()
	defer func() { s.historyAdmission.Lock(); delete(s.historyDevices, device); s.historyAdmission.Unlock() }()
	s.historyGate.Lock()
	defer s.historyGate.Unlock()
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, e := s.db.Begin()
	if e != nil {
		return out, e
	}
	defer tx.Rollback()
	st, e := loadReceiptState(tx)
	if e != nil {
		return out, e
	}
	for i, m := range messages {
		if _, e = tx.Exec("INSERT OR IGNORE INTO receipt_roles VALUES(?,?,?)", m.Device, m.ID, "history"); e != nil {
			return out, e
		}
		ack, err := s.ingestMessage(tx, &st, m, wires[i], at, "history")
		if err != nil {
			if errors.Is(err, ErrConflict) {
				return out, &BatchError{i, m.ID, "identity_conflict", err.Error()}
			}
			return out, err
		}
		out.Acks = append(out.Acks, ack)
	}
	if e = saveReceiptState(tx, st); e != nil {
		return out, e
	}
	if e = tx.Commit(); e != nil {
		return out, e
	}
	s.notifyProjection()
	return out, nil
}
func (s *Store) historyHandler(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/api/v1/capabilities" {
		if r.Method != "GET" {
			failure(w, 405, "method_not_allowed", fmt.Errorf("use GET"))
			return
		}
		respond(w, 200, map[string]any{"protocol_version": 1, "historical_batch_versions": []int{1}, "maximum_batch_bytes": s.historyBatchBytes, "maximum_batch_records": s.historyBatchRecords, "maximum_inflight_history_per_device": 1, "observation_identity_version": 1})
		return
	}
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed", fmt.Errorf("use POST"))
		return
	}
	media, _, e := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if e != nil || media != "application/json" {
		failure(w, 415, "unsupported_media_type", fmt.Errorf("use JSON"))
		return
	}
	if s.historyStoragePressure(r.Context()) {
		w.Header().Set("Retry-After", "2")
		w.Header().Set("X-GNSS-Throttle-Scope", "history")
		failure(w, 429, "history_storage_pressure", fmt.Errorf("WAL reader pressure; urgent traffic remains eligible"))
		return
	}
	// Bound decoded request RAM independently of the number of phones. Queued
	// sockets exert backpressure; urgent individual envelopes bypass these slots.
	select {
	case s.historySlots <- struct{}{}:
		defer func() { <-s.historySlots }()
	case <-r.Context().Done():
		return
	}
	b, e := io.ReadAll(http.MaxBytesReader(w, r.Body, int64(s.historyBatchBytes)))
	if e != nil {
		failure(w, 413, "message_too_large", e)
		return
	}
	ack, e := s.IngestBatch(b)
	if e != nil {
		if errors.Is(e, errHistoryBusy) {
			w.Header().Set("Retry-After", "1")
			w.Header().Set("X-GNSS-Throttle-Scope", "history")
			failure(w, 429, "history_busy", e)
			return
		}
		var bad *BatchError
		if errors.As(e, &bad) {
			respond(w, 422, map[string]any{"batch_version": 1, "error": bad.Code, "entry_index": bad.Index, "observation_id": bad.Observation, "message": bad.Detail})
			return
		}
		if _, err := decode(b); err != nil {
			failure(w, 400, "invalid_batch", e)
			return
		}
		failure(w, 503, "storage_unavailable", e)
		return
	}
	respond(w, 200, ack)
}

func (s *Store) recordBatchRejection(b []byte, bad *BatchError, at string) {
	event := Evidence{At: at, Kind: "batch_rejected", FailureCode: bad.Code, BatchEntryIndex: &bad.Index}
	if uuid.MatchString(bad.Observation) {
		event.Message = bad.Observation
	}
	if len(b) <= s.historyBatchBytes {
		if v, e := decode(b); e == nil {
			if messages, ok := v["messages"].([]any); ok && len(messages) > 0 {
				device := str(object(messages[0])["device_id"])
				if uuid.MatchString(device) {
					event.Device = device
				}
			}
		}
	}
	data, e := json.Marshal(event)
	if e != nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	_, _ = s.db.Exec("INSERT INTO field_evidence(data) VALUES(?)", string(data))
}

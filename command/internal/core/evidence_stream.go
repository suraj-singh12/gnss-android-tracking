package core

import (
	"archive/zip"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"time"
)

// Version 3 preserves the complete receipt/event/decision evidence as streaming
// NDJSON. Automatic whole-history verdict generation is explicitly inconclusive;
// it is never falsely substituted with a verdict over a truncated sample.
func (s *Store) exportStreamingFieldReport(ctx context.Context, w io.Writer) error {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Minute)
	defer cancel()
	db := s.viewDB
	if db == nil {
		db = s.db
	}
	tx, err := db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return err
	}
	st, err := loadReceiptState(tx)
	if err != nil {
		tx.Rollback()
		return err
	}
	header, err := readProjectionHeader(tx)
	if err != nil {
		tx.Rollback()
		return err
	}
	var rawCeiling, eventCeiling int64
	if err = tx.QueryRowContext(ctx, "SELECT COALESCE(MAX(rowid),0) FROM raw").Scan(&rawCeiling); err == nil {
		err = tx.QueryRowContext(ctx, "SELECT COALESCE(MAX(ordinal),0) FROM field_evidence").Scan(&eventCeiling)
	}
	tx.Rollback()
	if err != nil {
		return err
	}
	summary := FieldReport{Schema: 3, Generated: time.Now().UTC().Format(wireTime), Revision: BuildRevision(), Policy: st.Policy, Recording: recordingEvidence(st), ProjectionPending: st.ProjectionPending, ProjectionError: st.ProjectionError, Coverage: "Complete original receipts/events/qualified decisions are streamed in separate NDJSON files up to captured row ceilings. No whole-corpus cadence/SOS verdict is claimed. Derived generations must remain available; an invalidated export fails and must be retried. Raw data remains authoritative.", Verdicts: []Verdict{{"large_history_analysis", "", "INCONCLUSIVE", "Complete NDJSON evidence requires offline whole-corpus analysis; no sampled PASS", nil}}}
	aliases := map[string]string{}
	for device, d := range st.Devices {
		if d.NonSOSReports == 0 {
			aliases[device] = "sos-device-" + sosRef(device)
		}
	}
	redactSOSRecording(summary.Recording, aliases)
	redactEvent := func(event Evidence) Evidence {
		redactSOSRecording(event.Before, aliases)
		redactSOSRecording(event.After, aliases)
		if alias, ok := aliases[event.Device]; ok {
			event.Device = alias
		}
		return redactSOSEvidence(event)
	}
	archive := zip.NewWriter(w)
	writeJSON := func(name string, v any) error {
		entry, err := archive.Create(name)
		if err != nil {
			return err
		}
		return json.NewEncoder(entry).Encode(v)
	}
	if err = writeJSON("manifest.json", map[string]any{"schema_version": 3, "raw_row_ceiling": rawCeiling, "event_ordinal_ceiling": eventCeiling, "privacy": "No coordinates, Party metadata, addresses, raw envelopes or secrets; SOS identifiers use existing redaction", "files": []string{"field-report.json", "receipts.ndjson", "events.ndjson", "projection-decisions.ndjson"}}); err != nil {
		return err
	}
	if err = writeJSON("field-report.json", summary); err != nil {
		return err
	}
	entry, err := archive.Create("receipts.ndjson")
	if err != nil {
		return err
	}
	encoder := json.NewEncoder(entry)
	cursor := int64(0)
	for cursor < rawCeiling {
		records := []Receipt{}
		pageCtx, pageCancel := context.WithTimeout(ctx, 5*time.Second)
		rows, e := db.QueryContext(pageCtx, "SELECT rowid,known,received FROM raw WHERE rowid>? AND rowid<=? ORDER BY rowid LIMIT 128", cursor, rawCeiling)
		if e != nil {
			pageCancel()
			return e
		}
		for rows.Next() {
			var b, received string
			if e = rows.Scan(&cursor, &b, &received); e != nil {
				break
			}
			var m Message
			if e = json.Unmarshal([]byte(b), &m); e != nil {
				break
			}
			v := redactEvent(receiptEvidence(m, received, received, "stored", nil))
			class, age := receiptClass(m, received, st.Policy)
			records = append(records, Receipt{Evidence: v, Class: class, CaptureAge: age})
		}
		if e == nil {
			e = rows.Err()
		}
		rows.Close()
		pageCancel()
		if e != nil {
			return e
		}
		if len(records) == 0 {
			break
		}
		for _, record := range records {
			if e = encoder.Encode(record); e != nil {
				return e
			}
		}
	}
	entry, err = archive.Create("events.ndjson")
	if err != nil {
		return err
	}
	encoder = json.NewEncoder(entry)
	cursor = 0
	for cursor < eventCeiling {
		records := []Evidence{}
		pageCtx, pageCancel := context.WithTimeout(ctx, 5*time.Second)
		rows, e := db.QueryContext(pageCtx, "SELECT ordinal,data FROM field_evidence WHERE ordinal>? AND ordinal<=? ORDER BY ordinal LIMIT 1", cursor, eventCeiling)
		if e != nil {
			pageCancel()
			return e
		}
		for rows.Next() {
			var b string
			if e = rows.Scan(&cursor, &b); e != nil {
				break
			}
			var event Evidence
			if e = json.Unmarshal([]byte(b), &event); e != nil {
				break
			}
			records = append(records, redactEvent(event))
		}
		if e == nil {
			e = rows.Err()
		}
		rows.Close()
		pageCancel()
		if e != nil {
			return e
		}
		if len(records) == 0 {
			break
		}
		for _, record := range records {
			if e = encoder.Encode(record); e != nil {
				return e
			}
		}
	}
	entry, err = archive.Create("projection-decisions.ndjson")
	if err != nil {
		return err
	}
	encoder = json.NewEncoder(entry)
	devices := []string{}
	for device := range header.Generations {
		devices = append(devices, device)
	}
	sort.Strings(devices)
	for _, device := range devices {
		generation := header.Generations[device]
		for ordinal := 0; ordinal < header.Chunks[device]; ordinal++ {
			pageCtx, pageCancel := context.WithTimeout(ctx, 5*time.Second)
			var b string
			err = db.QueryRowContext(pageCtx, "SELECT data FROM projection_device_chunks WHERE device=? AND generation=? AND ordinal=?", device, generation, ordinal).Scan(&b)
			pageCancel()
			if err != nil {
				return fmt.Errorf("projection generation changed during export; retry: %w", err)
			}
			var part savedProjection
			if err = json.Unmarshal([]byte(b), &part); err != nil {
				return err
			}
			for _, decision := range part.Decisions {
				if err = encoder.Encode(decision); err != nil {
					return err
				}
			}
		}
	}
	return archive.Close()
}

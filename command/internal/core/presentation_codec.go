package core

// Lossless byte deltas retain the exact browser payload, including string/number
// spelling. Copy spans can reference only one earlier immutable checkpoint. A full
// checkpoint at most every 32 links bounds reconstruction, memory and corruption.
import (
	"bytes"
	"compress/zlib"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
)

const presentationDeltaDepth = 32
const deltaBlock = 64

type presentationSpan struct {
	Offset  int    `json:"offset,omitempty"`
	Length  int    `json:"length,omitempty"`
	Literal []byte `json:"literal,omitempty"`
}
type presentationEnvelope struct {
	Encoding string             `json:"encoding"`
	Scope    string             `json:"scope,omitempty"`
	Base     string             `json:"base,omitempty"`
	Depth    int                `json:"depth,omitempty"`
	State    json.RawMessage    `json:"state,omitempty"`
	Spans    []presentationSpan `json:"spans,omitempty"`
}

func presentationHash(scope string, b []byte) string {
	if scope == "" {
		return digest(b)
	}
	return digest(append(append([]byte(scope), 0), b...))
}
func deltaWindow(b []byte) uint64 {
	var h uint64
	for _, v := range b {
		h = h*257 + uint64(v)
	}
	return h
}
func presentationDiff(base, current []byte) []presentationSpan {
	index := make(map[uint64]int, len(base)/deltaBlock)
	for i := 0; i+deltaBlock <= len(base); i += deltaBlock {
		h := deltaWindow(base[i : i+deltaBlock])
		if _, ok := index[h]; !ok {
			index[h] = i
		}
	}
	var power uint64 = 1
	for i := 1; i < deltaBlock; i++ {
		power *= 257
	}
	spans := []presentationSpan{}
	literalStart := 0
	i := 0
	var rolling uint64
	valid := false
	for i+deltaBlock <= len(current) {
		if !valid {
			rolling = deltaWindow(current[i : i+deltaBlock])
			valid = true
		}
		at, ok := index[rolling]
		if ok && bytes.Equal(base[at:at+deltaBlock], current[i:i+deltaBlock]) {
			length := deltaBlock
			for at+length < len(base) && i+length < len(current) && base[at+length] == current[i+length] {
				length++
			}
			if literalStart < i {
				spans = append(spans, presentationSpan{Literal: append([]byte(nil), current[literalStart:i]...)})
			}
			spans = append(spans, presentationSpan{Offset: at, Length: length})
			i += length
			literalStart = i
			valid = false
		} else {
			if i+deltaBlock < len(current) {
				rolling = (rolling-uint64(current[i])*power)*257 + uint64(current[i+deltaBlock])
			}
			i++
		}
	}
	if literalStart < len(current) {
		spans = append(spans, presentationSpan{Literal: append([]byte(nil), current[literalStart:]...)})
	}
	return spans
}
func applyPresentationDiff(base []byte, spans []presentationSpan) ([]byte, error) {
	var out bytes.Buffer
	for _, s := range spans {
		if s.Length > 0 {
			if len(s.Literal) > 0 || s.Offset < 0 || s.Length > len(base) || s.Offset > len(base)-s.Length {
				return nil, errors.New("invalid presentation copy span")
			}
			if out.Len()+s.Length > presentationLimit {
				return nil, errors.New("presentation expansion limit")
			}
			out.Write(base[s.Offset : s.Offset+s.Length])
		} else {
			if s.Length < 0 || out.Len()+len(s.Literal) > presentationLimit {
				return nil, errors.New("presentation literal limit")
			}
			out.Write(s.Literal)
		}
	}
	return out.Bytes(), nil
}
func readPresentationBlob(db interface{ QueryRow(string, ...any) *sql.Row }, hash string) ([]byte, int, string, error) {
	return readPresentationDepth(db, hash, 0)
}
func readPresentationDepth(db interface{ QueryRow(string, ...any) *sql.Row }, hash string, depth int) ([]byte, int, string, error) {
	if depth > presentationDeltaDepth {
		return nil, 0, "", errors.New("presentation chain limit")
	}
	var packed []byte
	if e := db.QueryRow(`SELECT data FROM presentation_blobs WHERE hash=?`, hash).Scan(&packed); e != nil {
		return nil, 0, "", e
	}
	rd, e := zlib.NewReader(bytes.NewReader(packed))
	if e != nil {
		return nil, 0, "", e
	}
	b, e := io.ReadAll(io.LimitReader(rd, presentationLimit*3+1))
	rd.Close()
	if e != nil || len(b) > presentationLimit*3 {
		return nil, 0, "", errors.New("invalid presentation envelope")
	}
	var envelope presentationEnvelope
	if e = json.Unmarshal(b, &envelope); e != nil {
		return nil, 0, "", e
	}
	if envelope.Encoding == "" {
		if len(b) > presentationLimit || digest(b) != hash {
			return nil, 0, "", errors.New("legacy checkpoint integrity failure")
		}
		return b, 0, "", nil
	}
	var state []byte
	switch envelope.Encoding {
	case "checkpoint-v1":
		if envelope.Depth != 0 || envelope.Base != "" {
			return nil, 0, "", errors.New("invalid checkpoint ancestry")
		}
		state = envelope.State
	case "delta-v1":
		base, baseDepth, scope, e := readPresentationDepth(db, envelope.Base, depth+1)
		if e != nil {
			return nil, 0, "", e
		}
		if scope != envelope.Scope || envelope.Depth != baseDepth+1 || envelope.Depth > presentationDeltaDepth {
			return nil, 0, "", errors.New("invalid delta ancestry")
		}
		state, e = applyPresentationDiff(base, envelope.Spans)
		if e != nil {
			return nil, 0, "", e
		}
	default:
		return nil, 0, "", errors.New("unsupported presentation encoding")
	}
	if len(state) > presentationLimit || presentationHash(envelope.Scope, state) != hash {
		return nil, 0, "", errors.New("presentation hash mismatch")
	}
	return state, envelope.Depth, envelope.Scope, nil
}
func putPresentationBlob(db snapshotSQL, state []byte, baseHash, scope string) (string, error) {
	if len(state) > presentationLimit {
		return "", errors.New("presentation state limit")
	}
	hash := presentationHash(scope, state)
	var exists int
	if e := db.QueryRow(`SELECT COUNT(*) FROM presentation_blobs WHERE hash=?`, hash).Scan(&exists); e != nil {
		return "", e
	}
	if exists > 0 {
		return hash, nil
	}
	full := compressed([]byte(marshal(presentationEnvelope{Encoding: "checkpoint-v1", Scope: scope, State: state})))
	packed := full
	if baseHash != "" && baseHash != hash {
		base, depth, baseScope, e := readPresentationBlob(db, baseHash)
		if e != nil {
			return "", e
		}
		if depth < presentationDeltaDepth && scope == baseScope {
			delta := compressed([]byte(marshal(presentationEnvelope{Encoding: "delta-v1", Scope: scope, Base: baseHash, Depth: depth + 1, Spans: presentationDiff(base, state)})))
			if len(delta) < len(full)*9/10 {
				packed = delta
			}
		}
	}
	var used int64
	if e := db.QueryRow(`SELECT bytes FROM presentation_usage WHERE id=1`).Scan(&used); e != nil {
		return "", e
	}
	if used+int64(len(packed)) > presentationQuota {
		return "", errors.New("presentation storage quota reached; capture incomplete")
	}
	_, e := db.Exec(`INSERT OR IGNORE INTO presentation_blobs VALUES(?,?,?)`, hash, packed, len(packed))
	return hash, e
}

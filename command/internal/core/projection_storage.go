package core

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
)

// Versioned partitions of the existing derived cache. Raw and derivation remain
// unchanged. Staging chunks is interruptible; only the header pointer publishes.
type preparedDeviceProjection struct {
	generation   string
	chunks       []string
	count        int
	stagedChunks int
	version      int64
	rawVersion   int64
	preview      []Point
}

func newProjectionHeader() savedProjection {
	return savedProjection{Generations: map[string]string{}, Chunks: map[string]int{}, Counts: map[string]int{}, Preview: map[string][]Point{}}
}
func (h *savedProjection) set(device string, p preparedDeviceProjection) {
	if h.Generations == nil {
		h.Generations = map[string]string{}
	}
	if h.Chunks == nil {
		h.Chunks = map[string]int{}
	}
	if h.Counts == nil {
		h.Counts = map[string]int{}
	}
	if h.Versions == nil {
		h.Versions = map[string]int64{}
	}
	h.Versions[device] = p.version
	if h.RawVersions == nil {
		h.RawVersions = map[string]int64{}
	}
	h.RawVersions[device] = p.rawVersion
	if h.Preview == nil {
		h.Preview = map[string][]Point{}
	}
	h.Generations[device], h.Chunks[device], h.Counts[device] = p.generation, max(len(p.chunks), p.stagedChunks), p.count
	delete(h.Preview, device)
}
func prepareDeviceProjection(st State) (preparedDeviceProjection, error) {
	p := preparedDeviceProjection{generation: id(), version: st.ProjectionVersion, rawVersion: st.RawVersion, count: len(st.Points)}
	// Presentation sample only. Every full point/decision is persisted below.
	const maximumPreview = 256
	step := max(1, (len(st.Points)+maximumPreview-1)/maximumPreview)
	for i := 0; i < len(st.Points); i += step {
		p.preview = append(p.preview, st.Points[i])
	}
	if len(st.Points) > 0 && len(p.preview) < maximumPreview && (len(st.Points)-1)%step != 0 {
		p.preview = append(p.preview, st.Points[len(st.Points)-1])
	}
	for len(st.Points) > 0 || len(st.Decisions) > 0 {
		pn, dn := min(len(st.Points), projectionChunkRecords), min(len(st.Decisions), projectionChunkRecords)
		b, err := json.Marshal(savedProjection{Points: st.Points[:pn], Decisions: st.Decisions[:dn]})
		if err != nil {
			return p, err
		}
		p.chunks = append(p.chunks, string(b))
		st.Points, st.Decisions = st.Points[pn:], st.Decisions[dn:]
	}
	return p, nil
}
func insertProjectionChunks(tx *sql.Tx, device string, p preparedDeviceProjection) error {
	statement, err := tx.Prepare("INSERT INTO projection_device_chunks(device,generation,ordinal,data) VALUES(?,?,?,?)")
	if err != nil {
		return err
	}
	defer statement.Close()
	for ordinal, chunk := range p.chunks {
		if _, err = statement.Exec(device, p.generation, ordinal, chunk); err != nil {
			return err
		}
	}
	return insertProjectionPreview(tx, device, p)
}
func insertProjectionPreview(tx *sql.Tx, device string, p preparedDeviceProjection) error {
	b, err := json.Marshal(savedProjection{Points: p.preview})
	if err != nil {
		return err
	}
	_, err = tx.Exec("INSERT INTO projection_device_chunks VALUES(?,?, -1,?)", device, p.generation, string(b))
	return err
}
func (s *Store) stageProjectionPreview(device string, p preparedDeviceProjection) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = insertProjectionPreview(tx, device, p); err != nil {
		return err
	}
	return tx.Commit()
}
func readProjectionHeader(tx *sql.Tx) (savedProjection, error) {
	var b string
	var h savedProjection
	err := tx.QueryRow("SELECT data FROM projection_state WHERE id=1").Scan(&b)
	if err == nil && len(b) > 2*1024*1024 && !strings.Contains(b, `"generations":`) {
		return savedProjection{LegacyLarge: true}, nil
	}
	if err == nil {
		err = json.Unmarshal([]byte(b), &h)
	}
	return h, err
}
func writeProjectionHeader(tx *sql.Tx, h savedProjection) error {
	b, err := json.Marshal(h)
	if err != nil {
		return err
	}
	_, err = tx.Exec("UPDATE projection_state SET data=? WHERE id=1", string(b))
	return err
}
func sortProjection(st *State) {
	order := map[string]int{}
	if st.Recording != nil {
		for i, w := range st.Recording.Windows {
			order[w.ID] = i
		}
	}
	sort.SliceStable(st.Points, func(i, j int) bool {
		a, b := st.Points[i], st.Points[j]
		if order[a.Window] != order[b.Window] {
			return order[a.Window] < order[b.Window]
		}
		return a.Device < b.Device
	})
	sort.SliceStable(st.Decisions, func(i, j int) bool {
		a, b := st.Decisions[i], st.Decisions[j]
		if order[a.Window] != order[b.Window] {
			return order[a.Window] < order[b.Window]
		}
		return a.Device < b.Device
	})
}

// Small write transactions allow queued raw/LIVE/SOS receipts between chunks.
func (s *Store) stageDeviceProjection(device string, p preparedDeviceProjection) error {
	for first := 0; first < len(p.chunks); first += 4 {
		if err := func() error {
			s.mu.Lock()
			defer s.mu.Unlock()
			tx, err := s.db.Begin()
			if err != nil {
				return err
			}
			defer tx.Rollback()
			for ordinal := first; ordinal < min(first+4, len(p.chunks)); ordinal++ {
				if _, err = tx.Exec("INSERT INTO projection_device_chunks VALUES(?,?,?,?)", device, p.generation, ordinal, p.chunks[ordinal]); err != nil {
					return err
				}
			}
			return tx.Commit()
		}(); err != nil {
			return err
		}
		select {
		case <-s.stop:
			return fmt.Errorf("projection stopped before publication")
		default:
		}
	}
	return s.stageProjectionPreview(device, p)
}
func (s *Store) cleanProjectionChunks(device, generation string) {
	if device == "" && generation == "" {
		s.mu.Lock()
		rows, err := s.db.Query("SELECT DISTINCT device FROM projection_device_chunks")
		devices := []string{}
		if err == nil {
			for rows.Next() {
				var device string
				if rows.Scan(&device) == nil {
					devices = append(devices, device)
				}
			}
			rows.Close()
		}
		s.mu.Unlock()
		for _, device := range devices {
			s.cleanProjectionChunks(device, "partition")
		}
		return
	}
	for {
		s.mu.Lock()
		// Read the current pointer in the DELETE statement: an operator action
		// publishing another generation must never have its chunks collected.
		result, err := s.db.Exec(`DELETE FROM projection_device_chunks WHERE rowid IN
        (SELECT rowid FROM projection_device_chunks WHERE device=? AND generation!=COALESCE((SELECT json_extract(data,?) FROM projection_state WHERE id=1),'') LIMIT 4)`, device, `$.generations."`+device+`"`)
		s.mu.Unlock()
		if err != nil {
			return
		}
		n, _ := result.RowsAffected()
		if n == 0 {
			return
		}
		select {
		case <-s.stop:
			return
		default:
		}
	}
}

// Derived cache damage cannot silently certify completion. Forward migration is
// additive; repair only invalid partitions and enqueue the existing worker.
func (s *Store) validateProjectionCache() error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	st, err := loadReceiptState(tx)
	if err != nil {
		return err
	}
	h, readErr := readProjectionHeader(tx)
	if readErr == nil && h.Generations == nil && (h.Chunked || h.LegacyLarge) {
		for device := range st.Devices {
			if _, err = tx.Exec(`INSERT OR IGNORE INTO projection_work(device,earliest) VALUES(?,'')`, device); err != nil {
				return err
			}
		}
		st.ProjectionPending = len(st.Devices) > 0
		if err = saveReceiptState(tx, st); err != nil {
			return err
		}
		return tx.Commit()
	}
	broken := map[string]bool{}
	if readErr != nil {
		h = newProjectionHeader()
		for device := range st.Devices {
			broken[device] = true
		}
	}
	for device, generation := range h.Generations {
		var count int
		var first, last sql.NullInt64
		if err = tx.QueryRow("SELECT COUNT(*),MIN(ordinal),MAX(ordinal) FROM projection_device_chunks WHERE device=? AND generation=? AND ordinal>=0", device, generation).Scan(&count, &first, &last); err != nil {
			return err
		}
		var previews int
		if err = tx.QueryRow("SELECT COUNT(*) FROM projection_device_chunks WHERE device=? AND generation=? AND ordinal=-1 AND json_valid(data)", device, generation).Scan(&previews); err != nil {
			return err
		}
		if previews != 1 || count != h.Chunks[device] || count > 0 && (first.Int64 != 0 || last.Int64 != int64(count-1)) {
			broken[device] = true
		}
	}
	if len(broken) == 0 {
		var queued bool
		if err = tx.QueryRow("SELECT EXISTS(SELECT 1 FROM projection_work LIMIT 1)").Scan(&queued); err != nil {
			return err
		}
		if !st.ProjectionPending || queued {
			return nil
		}
		// Older failure handling could persist pending=true after an idle reader
		// timeout with no work. Recover by reconstructing, never by asserting that
		// ACKs or a structurally valid cache alone prove completion.
		work := 0
		for device, d := range st.Devices {
			if d.DurableObservations == 0 {
				continue
			}
			if _, err = tx.Exec("INSERT INTO projection_work(device,earliest) VALUES(?,'')", device); err != nil {
				return err
			}
			work++
		}
		st.ProjectionPending = work > 0
		st.ProjectionError = ""
		if work > 0 {
			st.ProjectionError = "Pending state had no queued work; rebuilding retained history"
		}
		if err = saveReceiptState(tx, st); err != nil {
			return err
		}
		return tx.Commit()
	}
	for device := range broken {
		delete(h.Generations, device)
		delete(h.Chunks, device)
		delete(h.Counts, device)
		delete(h.Preview, device)
		delete(h.Versions, device)
		delete(h.RawVersions, device)
		if _, err = tx.Exec(`INSERT INTO projection_work(device,earliest) VALUES(?,'') ON CONFLICT(device) DO UPDATE SET earliest='',generation=generation+1`, device); err != nil {
			return err
		}
	}
	st.ProjectionPending = true
	st.ProjectionError = "Derived cache incomplete; rebuilding from retained raw history"
	if err = writeProjectionHeader(tx, h); err != nil {
		return err
	}
	if err = saveReceiptState(tx, st); err != nil {
		return err
	}
	return tx.Commit()
}

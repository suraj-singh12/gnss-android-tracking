package core

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"time"
)

// Transfer only the existing derived cache format. Feed/raw authority and quality
// semantics do not change. Original cache stays published until the final pointer
// transaction, and every staged value/read is bounded independently of history.
func (s *Store) migrateProjectionCache() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	db := s.projectionDB
	if db == nil {
		db = s.db
	}
	var original string
	if err := db.QueryRowContext(ctx, "SELECT data FROM projection_state WHERE id=1").Scan(&original); err != nil {
		return err
	}
	var flag struct {
		Chunked bool `json:"chunked"`
	}
	// Avoid decoding the monolithic arrays just to inspect the small flag.
	if len(original) < 2*1024*1024 {
		if err := json.Unmarshal([]byte(original), &flag); err != nil {
			return err
		}
	}
	type group struct {
		preparedDeviceProjection
		points    []Point
		decisions []Decision
		stride    int
	}
	groups := map[string]*group{}
	get := func(device string) *group {
		if groups[device] == nil {
			groups[device] = &group{preparedDeviceProjection: preparedDeviceProjection{generation: id()}, stride: 1}
		}
		return groups[device]
	}
	flush := func(device string, g *group) error {
		if len(g.points) == 0 && len(g.decisions) == 0 {
			return nil
		}
		b, err := json.Marshal(savedProjection{Points: g.points, Decisions: g.decisions})
		if err != nil {
			return err
		}
		s.mu.Lock()
		_, err = s.db.ExecContext(ctx, "INSERT INTO projection_device_chunks VALUES(?,?,?,?)", device, g.generation, g.stagedChunks, string(b))
		s.mu.Unlock()
		if err != nil {
			return err
		}
		g.stagedChunks++
		g.points, g.decisions = nil, nil
		return nil
	}
	decodeBody := func(body string) error {
		decoder := json.NewDecoder(strings.NewReader(body))
		token, err := decoder.Token()
		if err != nil || token != json.Delim('{') {
			return fmt.Errorf("invalid legacy projection")
		}
		for decoder.More() {
			key, err := decoder.Token()
			if err != nil {
				return err
			}
			switch key {
			case "points", "decisions":
				token, err = decoder.Token()
				if err != nil {
					return err
				}
				if token == nil {
					continue
				}
				if token != json.Delim('[') {
					return fmt.Errorf("invalid legacy projection array")
				}
				for decoder.More() {
					if key == "points" {
						var point Point
						if err = decoder.Decode(&point); err != nil {
							return err
						}
						g := get(point.Device)
						g.points = append(g.points, point)
						if g.count%g.stride == 0 {
							g.preview = append(g.preview, point)
							if len(g.preview) > 256 {
								small := []Point{}
								for i := 0; i < len(g.preview); i += 2 {
									small = append(small, g.preview[i])
								}
								g.preview = small
								g.stride *= 2
							}
						}
						g.count++
						if len(g.points) >= 512 {
							if err = flush(point.Device, g); err != nil {
								return err
							}
						}
					} else {
						var decision Decision
						if err = decoder.Decode(&decision); err != nil {
							return err
						}
						g := get(decision.Device)
						g.decisions = append(g.decisions, decision)
						if len(g.decisions) >= 512 {
							if err = flush(decision.Device, g); err != nil {
								return err
							}
						}
					}
				}
				if _, err = decoder.Token(); err != nil {
					return err
				}
			default:
				var discard json.RawMessage
				if err = decoder.Decode(&discard); err != nil {
					return err
				}
			}
		}
		_, err = decoder.Token()
		return err
	}
	if flag.Chunked {
		ordinal := -1
		for {
			var body string
			pageCtx, pageCancel := context.WithTimeout(ctx, 5*time.Second)
			err := db.QueryRowContext(pageCtx, "SELECT ordinal,data FROM projection_chunks WHERE ordinal>? ORDER BY ordinal LIMIT 1", ordinal).Scan(&ordinal, &body)
			pageCancel()
			if err == sql.ErrNoRows {
				break
			}
			if err != nil {
				return err
			}
			if err = decodeBody(body); err != nil {
				return err
			}
		}
	} else if err := decodeBody(original); err != nil {
		return err
	}
	for device, g := range groups {
		if err := flush(device, g); err != nil {
			return err
		}
		if err := s.stageProjectionPreview(device, g.preparedDeviceProjection); err != nil {
			return err
		}
	}
	s.mu.Lock()
	err := func() error {
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		defer tx.Rollback()
		current, err := readProjectionHeader(tx)
		if err != nil {
			return err
		}
		if current.Generations != nil {
			return nil
		}
		st, err := loadReceiptState(tx)
		if err != nil {
			return err
		}
		header := newProjectionHeader()
		for device, g := range groups {
			g.version = -1 // legacy source generation is unknown until reconstruction
			header.set(device, g.preparedDeviceProjection)
		}
		for device := range st.Devices {
			if _, err = tx.Exec(`INSERT INTO projection_work(device,earliest) VALUES(?,'') ON CONFLICT(device) DO UPDATE SET generation=generation+1`, device); err != nil {
				return err
			}
		}
		st.ProjectionPending = len(st.Devices) > 0
		st.ProjectionError = ""
		if err = writeProjectionHeader(tx, header); err != nil {
			return err
		}
		if err = saveReceiptState(tx, st); err != nil {
			return err
		}
		return tx.Commit()
	}()
	s.mu.Unlock()
	if err != nil {
		return err
	}
	// Incremental cleanup cannot monopolize the writer after a large upgrade.
	for {
		s.mu.Lock()
		result, e := s.db.Exec("DELETE FROM projection_chunks WHERE ordinal IN (SELECT ordinal FROM projection_chunks LIMIT 4)")
		s.mu.Unlock()
		if e != nil {
			return e
		}
		n, _ := result.RowsAffected()
		if n == 0 {
			break
		}
		select {
		case <-s.stop:
			return io.EOF
		default:
		}
	}
	s.notifyProjection()
	return nil
}

package core

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"strconv"
	"sync"
	"time"
)

const demOverallBudget = 6 * time.Minute
const demAttemptBudget = 120 * time.Second
const demProcessingBudget = 10 * time.Second
const demCompressedLimit = 26 * 1024 * 1024
const demRawSize = 3601 * 3601 * 2

type DEMAttempt struct {
	Tile            string   `json:"tile"`
	Number          int      `json:"attempt"`
	ConnectionMS    *float64 `json:"connection_ms"`
	FirstByteMS     *float64 `json:"first_response_byte_ms"`
	HTTPStatus      *int     `json:"http_status"`
	CompressedBytes *int64   `json:"compressed_bytes_received"`
	TransferMS      *float64 `json:"transfer_ms"`
	DecompressionMS *float64 `json:"decompression_ms"`
	ElapsedMS       float64  `json:"elapsed_ms"`
	Stage           string   `json:"stage"`
	Outcome         string   `json:"outcome"`
	Error           string   `json:"error,omitempty"`
}
type DEMReport struct {
	Revision     Revision        `json:"source_revision"`
	Provider     string          `json:"provider"`
	Dataset      string          `json:"dataset"`
	MapID        string          `json:"map_id"`
	Started      string          `json:"started_at"`
	Bounds       [4]float64      `json:"bounds_west_south_east_north"`
	Centre       [2]float64      `json:"centre_longitude_latitude"`
	AreaMetres   [2]float64      `json:"area_width_height_metres"`
	Components   map[string]bool `json:"components"`
	Attempts     []DEMAttempt    `json:"attempts"`
	ProcessingMS *float64        `json:"terrain_processing_ms"`
	SaveMS       *float64        `json:"sqlite_save_ms"`
	ElapsedMS    float64         `json:"total_elapsed_ms"`
	Reused       bool            `json:"reused_completed_acquisition"`
	Outcome      string          `json:"outcome"`
	Stage        string          `json:"stage"`
	Error        string          `json:"error,omitempty"`
	Policy       string          `json:"timeout_and_retry_policy"`
}
type DEMProgress struct {
	Stage     string     `json:"stage"`
	Tile      string     `json:"tile,omitempty"`
	Attempt   int        `json:"attempt,omitempty"`
	Bytes     int64      `json:"received_bytes,omitempty"`
	ElapsedMS float64    `json:"elapsed_ms"`
	Report    *DEMReport `json:"report,omitempty"`
	Error     string     `json:"error,omitempty"`
}
type demObserver func(DEMProgress)

func milliseconds(d time.Duration) *float64 { v := float64(d) / float64(time.Millisecond); return &v }
func emitDEM(emit demObserver, event DEMProgress) {
	if emit != nil {
		emit(event)
	}
}

// Network and decompression are deliberately separate so a transfer timeout
// cannot be confused with corrupt gzip. Only one bounded tile is held at a time.
func (p *mapProvider) downloadDEMTile(ctx context.Context, name, address string, report *DEMReport, emit demObserver) ([]byte, error) {
	client := p.demClient
	if client == nil {
		client = p.client
	}
	budget, backoff := demAttemptBudget, time.Second
	if p.demTestAttemptBudget > 0 {
		budget = p.demTestAttemptBudget
	}
	if p.demTestBackoff > 0 {
		backoff = p.demTestBackoff
	}
	for number := 1; number <= 3; number++ {
		started := time.Now()
		a := DEMAttempt{Tile: name, Number: number, Stage: "connecting"}
		emitDEM(emit, DEMProgress{Stage: a.Stage, Tile: name, Attempt: number})
		attemptCtx, cancel := context.WithTimeout(ctx, budget)
		var traceMu sync.Mutex
		var connectionStart time.Time
		var connectionMS, firstByteMS *float64
		trace := &httptrace.ClientTrace{
			GetConn: func(string) { traceMu.Lock(); connectionStart = time.Now(); traceMu.Unlock() },
			GotConn: func(httptrace.GotConnInfo) {
				traceMu.Lock()
				connectionMS = milliseconds(time.Since(connectionStart))
				traceMu.Unlock()
			},
			GotFirstResponseByte: func() { traceMu.Lock(); firstByteMS = milliseconds(time.Since(started)); traceMu.Unlock() },
		}
		req, err := http.NewRequestWithContext(httptrace.WithClientTrace(attemptCtx, trace), "GET", address, nil)
		if err != nil {
			cancel()
			return nil, err
		}
		req.Header.Set("User-Agent", "GNSS-Command/0.1 (bounded DEM acquisition; https://github.com/suraj-singh12/gnss-android-tracking)")
		resp, err := client.Do(req)
		retry, pause := false, backoff*time.Duration(1<<(number-1))
		var compressed []byte
		if err == nil {
			a.HTTPStatus = &resp.StatusCode
			if resp.StatusCode != http.StatusOK {
				a.Stage = "provider response"
				err = fmt.Errorf("DEM provider returned HTTP %d", resp.StatusCode)
				retry = resp.StatusCode == 429 || resp.StatusCode == 408 || resp.StatusCode == 500 || resp.StatusCode == 502 || resp.StatusCode == 503 || resp.StatusCode == 504
				if value := resp.Header.Get("Retry-After"); value != "" {
					seconds, e := strconv.Atoi(value)
					var wait time.Duration
					if e == nil && seconds >= 0 && seconds <= 30 {
						wait = time.Duration(seconds) * time.Second
					} else if at, e := http.ParseTime(value); e == nil {
						wait = time.Until(at)
					} else {
						retry = false
					}
					if wait > 30*time.Second {
						retry = false
						err = fmt.Errorf("%w; provider cooldown exceeds automatic retry limit; retry later", err)
					} else if wait > pause {
						pause = wait
					}
				}
			} else {
				a.Stage = "downloading"
				transferStart := time.Now()
				var count int64
				last := time.Time{}
				reader := &demCountingReader{Reader: resp.Body, count: &count, update: func() {
					if time.Since(last) >= 100*time.Millisecond {
						last = time.Now()
						emitDEM(emit, DEMProgress{Stage: "downloading", Tile: name, Attempt: number, Bytes: count, ElapsedMS: *milliseconds(time.Since(transferStart))})
					}
				}}
				compressed, err = io.ReadAll(io.LimitReader(reader, demCompressedLimit+1))
				a.CompressedBytes = &count
				a.TransferMS = milliseconds(time.Since(transferStart))
				emitDEM(emit, DEMProgress{Stage: "downloading", Tile: name, Attempt: number, Bytes: count, ElapsedMS: *a.TransferMS})
				if len(compressed) > demCompressedLimit {
					err = errors.New("compressed DEM exceeds 26 MiB limit")
				} else if err != nil {
					retry = true
				}
			}
			resp.Body.Close()
		} else {
			var ne net.Error
			var operation *net.OpError
			retry = errors.As(err, &operation) || (errors.As(err, &ne) && (ne.Timeout() || ne.Temporary()))
		}
		if err != nil {
			var ne net.Error
			if errors.Is(err, context.Canceled) || ctx.Err() != nil {
				retry = false
			}
			if errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &ne) && ne.Timeout()) {
				err = fmt.Errorf("DEM %s timed out: %w", a.Stage, err)
			} else if errors.Is(err, context.Canceled) {
				err = fmt.Errorf("DEM download cancelled: %w", err)
			} else if a.Stage == "downloading" {
				err = fmt.Errorf("DEM transfer interrupted: %w", err)
			} else if a.Stage == "connecting" {
				err = fmt.Errorf("DEM provider connection failed: %w", err)
			}
		}
		cancel()
		// Late trace callbacks may occur after cancellation; copy their independent
		// state under the trace lock instead of sharing the attempt record.
		traceMu.Lock()
		a.ConnectionMS, a.FirstByteMS = connectionMS, firstByteMS
		traceMu.Unlock()
		var raw []byte
		if err == nil {
			a.Stage = "decompressing"
			decodeStart := time.Now()
			decodeCtx, decodeCancel := context.WithTimeout(ctx, demProcessingBudget)
			emitDEM(emit, DEMProgress{Stage: "processing terrain", Tile: name, Attempt: number})
			zr, e := gzip.NewReader(&demContextReader{Reader: bytes.NewReader(compressed), ctx: decodeCtx})
			if e == nil {
				raw, e = io.ReadAll(io.LimitReader(zr, demRawSize+1))
				zr.Close()
			}
			a.DecompressionMS = milliseconds(time.Since(decodeStart))
			if decodeCtx.Err() != nil {
				err = fmt.Errorf("DEM processing interrupted: %w", decodeCtx.Err())
			} else if e != nil {
				err = fmt.Errorf("invalid compressed DEM: %w", e)
			} else if len(raw) != demRawSize {
				err = errors.New("decoded elevation data invalid: expected 3601×3601 HGT tile")
			}
			if ctx.Err() != nil {
				err = fmt.Errorf("DEM processing cancelled: %w", ctx.Err())
			}
			if time.Since(decodeStart) > demProcessingBudget {
				err = errors.New("DEM decompression processing limit exceeded")
			}
			decodeCancel()
		}
		a.ElapsedMS = *milliseconds(time.Since(started))
		a.Outcome = "success"
		if err != nil {
			a.Outcome = "failed"
			a.Error = err.Error()
		}
		if report != nil {
			report.Attempts = append(report.Attempts, a)
		}
		if err == nil {
			return raw, nil
		}
		if !retry || number == 3 || ctx.Err() != nil {
			return nil, err
		}
		emitDEM(emit, DEMProgress{Stage: "retry backoff", Tile: name, Attempt: number, Error: err.Error()})
		timer := time.NewTimer(pause)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
	return nil, errors.New("DEM attempts exhausted")
}

type demCountingReader struct {
	io.Reader
	count  *int64
	update func()
}
type demContextReader struct {
	io.Reader
	ctx context.Context
}

func (r *demContextReader) Read(b []byte) (int, error) {
	if e := r.ctx.Err(); e != nil {
		return 0, e
	}
	return r.Reader.Read(b)
}

func (r *demCountingReader) Read(b []byte) (int, error) {
	n, e := r.Reader.Read(b)
	*r.count += int64(n)
	r.update()
	return n, e
}

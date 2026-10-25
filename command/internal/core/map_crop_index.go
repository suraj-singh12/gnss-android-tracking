package core

// Crop extraction uses a disposable compact source index, not another database.
// Hash partitions allow arbitrary PBF node ordering while bounding sorting and
// lookup memory. Operational ownership remains in the existing SQLite library.
import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	pb "github.com/qedus/osmpbf/OSMPBF"
	"io"
	"math"
	"os"
	"path/filepath"
	"sort"
	"time"
)

const cropIndexBytes int64 = 512 << 20
const cropShardBytes int64 = 8 << 20
const cropRecordBytes = 24

type cropNode struct {
	id       int64
	lon, lat float64
}
type cropNodeIndex struct {
	dir     string
	files   [256]*os.File
	writers [256]*bufio.Writer
	sizes   [256]int64
	bytes   int64
	cache   map[uint64][]byte
	order   []uint64
	handles []byte
}

func newCropNodeIndex(parent string) (*cropNodeIndex, error) {
	if err := os.MkdirAll(parent, 0700); err != nil {
		return nil, err
	}
	dir, err := os.MkdirTemp(parent, "crop-nodes-")
	if err != nil {
		return nil, err
	}
	return &cropNodeIndex{dir: dir, cache: make(map[uint64][]byte)}, nil
}
func (n *cropNodeIndex) close() {
	for _, f := range n.files {
		if f != nil {
			_ = f.Close()
		}
	}
	_ = os.RemoveAll(n.dir)
}
func (n *cropNodeIndex) path(k byte) string { return filepath.Join(n.dir, fmt.Sprintf("%02x", k)) }
func (n *cropNodeIndex) add(id int64, lon, lat float64) error {
	k := byte((uint64(id) / 65536) % 256)
	if n.bytes+cropRecordBytes > cropIndexBytes || n.sizes[k]+cropRecordBytes > cropShardBytes {
		return errors.New("selected-area source node index exceeds 512 MiB disk or 8 MiB partition budget; existing maps retained")
	}
	if n.files[k] == nil {
		if len(n.handles) == 32 {
			oldest := n.handles[0]
			if err := n.writers[oldest].Flush(); err != nil {
				return err
			}
			if err := n.files[oldest].Close(); err != nil {
				return err
			}
			n.files[oldest] = nil
			n.writers[oldest] = nil
			n.handles = n.handles[1:]
		}
		f, err := os.OpenFile(n.path(k), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
		if err != nil {
			return err
		}
		n.handles = append(n.handles, k)
		n.files[k] = f
		n.writers[k] = bufio.NewWriterSize(f, 16384)
	}
	var b [cropRecordBytes]byte
	binary.LittleEndian.PutUint64(b[:8], uint64(id))
	binary.LittleEndian.PutUint64(b[8:16], math.Float64bits(lon))
	binary.LittleEndian.PutUint64(b[16:], math.Float64bits(lat))
	if _, err := n.writers[k].Write(b[:]); err != nil {
		return err
	}
	n.bytes += cropRecordBytes
	n.sizes[k] += cropRecordBytes
	return nil
}
func (n *cropNodeIndex) read(k byte) ([]cropNode, error) {
	f, err := os.Open(n.path(k))
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, cropShardBytes+1))
	if err != nil {
		return nil, err
	}
	if len(b)%cropRecordBytes != 0 || int64(len(b)) > cropShardBytes || int64(len(b)) != n.sizes[k] {
		return nil, errors.New("invalid compact node partition")
	}
	nodes := make([]cropNode, len(b)/cropRecordBytes)
	for i := range nodes {
		p := b[i*cropRecordBytes:]
		nodes[i] = cropNode{int64(binary.LittleEndian.Uint64(p[:8])), math.Float64frombits(binary.LittleEndian.Uint64(p[8:16])), math.Float64frombits(binary.LittleEndian.Uint64(p[16:24]))}
	}
	return nodes, nil
}
func (n *cropNodeIndex) finish(ctx context.Context) error {
	for i, f := range n.files {
		if err := ctx.Err(); err != nil {
			return err
		}
		if n.sizes[i] == 0 {
			continue
		}
		if f != nil {
			if err := n.writers[i].Flush(); err != nil {
				return err
			}
			if err := f.Close(); err != nil {
				return err
			}
			n.files[i] = nil
			n.writers[i] = nil
		}
		nodes, err := n.read(byte(i))
		if err != nil {
			return err
		}
		sort.Slice(nodes, func(a, b int) bool { return nodes[a].id < nodes[b].id })
		for j := 1; j < len(nodes); j++ {
			if nodes[j].id == nodes[j-1].id {
				return errors.New("duplicate PBF node ID")
			}
		}
		f, err = os.OpenFile(n.path(byte(i)), os.O_WRONLY|os.O_TRUNC, 0600)
		if err != nil {
			return err
		}
		w := bufio.NewWriterSize(f, 16384)
		for _, p := range nodes {
			var b [cropRecordBytes]byte
			binary.LittleEndian.PutUint64(b[:8], uint64(p.id))
			binary.LittleEndian.PutUint64(b[8:16], math.Float64bits(p.lon))
			binary.LittleEndian.PutUint64(b[16:], math.Float64bits(p.lat))
			if _, err = w.Write(b[:]); err != nil {
				_ = f.Close()
				return err
			}
		}
		err = w.Flush()
		ce := f.Close()
		if err != nil {
			return err
		}
		if ce != nil {
			return ce
		}
	}
	n.handles = nil
	return nil
}

// Pages contain 128 whole records. The 512-page FIFO cache is 1.5 MiB;
// searches never decode a complete partition merely to resolve one reference.
const cropPageRecords = 128
const cropLookupPages = 512

func (n *cropNodeIndex) record(k byte, index int64) ([]byte, error) {
	page := index / cropPageRecords
	key := uint64(k)<<32 | uint64(page)
	b, ok := n.cache[key]
	if !ok {
		if n.files[k] == nil {
			if len(n.handles) == 32 {
				oldest := n.handles[0]
				if err := n.files[oldest].Close(); err != nil {
					return nil, err
				}
				n.files[oldest] = nil
				n.handles = n.handles[1:]
			}
			f, err := os.Open(n.path(k))
			if err != nil {
				return nil, err
			}
			n.files[k] = f
			n.handles = append(n.handles, k)
		}
		offset := page * cropPageRecords * cropRecordBytes
		size := min(int64(cropPageRecords*cropRecordBytes), n.sizes[k]-offset)
		if size <= 0 {
			return nil, errors.New("compact node page outside partition")
		}
		b = make([]byte, int(size))
		if _, err := n.files[k].ReadAt(b, offset); err != nil {
			return nil, err
		}
		if len(n.order) == cropLookupPages {
			delete(n.cache, n.order[0])
			n.order = n.order[1:]
		}
		n.order = append(n.order, key)
		n.cache[key] = b
	}
	start := (index % cropPageRecords) * cropRecordBytes
	return b[start : start+cropRecordBytes], nil
}
func (n *cropNodeIndex) get(ctx context.Context, id int64) (float64, float64, error) {
	k := byte((uint64(id) / 65536) % 256)
	lo, hi := int64(0), n.sizes[k]/cropRecordBytes
	for lo < hi {
		if err := ctx.Err(); err != nil {
			return 0, 0, err
		}
		mid := lo + (hi-lo)/2
		b, err := n.record(k, mid)
		if err != nil {
			return 0, 0, err
		}
		found := int64(binary.LittleEndian.Uint64(b[:8]))
		if found < id {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	if err := ctx.Err(); err != nil {
		return 0, 0, err
	}
	if lo == n.sizes[k]/cropRecordBytes {
		return 0, 0, errors.New("missing PBF node reference")
	}
	b, err := n.record(k, lo)
	if err != nil {
		return 0, 0, err
	}
	if int64(binary.LittleEndian.Uint64(b[:8])) != id {
		return 0, 0, errors.New("missing PBF node reference")
	}
	return math.Float64frombits(binary.LittleEndian.Uint64(b[8:16])), math.Float64frombits(binary.LittleEndian.Uint64(b[16:24])), nil
}

func visitPBFNodes(ctx context.Context, p *pb.PrimitiveBlock, add func(int64, int64, int64) error) error {
	for _, g := range p.Primitivegroup {
		for _, n := range g.Nodes {
			if err := ctx.Err(); err != nil {
				return err
			}
			if err := add(n.GetId(), n.GetLon(), n.GetLat()); err != nil {
				return err
			}
		}
		if d := g.Dense; d != nil {
			if len(d.Id) != len(d.Lon) || len(d.Id) != len(d.Lat) {
				return errors.New("PBF dense arrays mismatch")
			}
			var id, x, y int64
			for i := range d.Id {
				if err := ctx.Err(); err != nil {
					return err
				}
				var ok bool
				id, ok = mapDelta(id, d.Id[i])
				if !ok {
					return errors.New("PBF node ID delta overflow")
				}
				x, ok = mapDelta(x, d.Lon[i])
				if !ok {
					return errors.New("PBF longitude delta overflow")
				}
				y, ok = mapDelta(y, d.Lat[i])
				if !ok {
					return errors.New("PBF latitude delta overflow")
				}
				if err := add(id, x, y); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

// A map processing request is bounded to ten minutes. Sweep only older, known
// disposable work names; never touch source manifests, HGT or SQLite assets.
// Cleanup failure cannot block LIVE/SOS or erase a validated saved map.
func cleanupMapWork(cache string) {
	for _, pattern := range []string{"crop-nodes-*", "gnss-source-*.osm.pbf", "regional-*.tmp"} {
		paths, _ := filepath.Glob(filepath.Join(cache, pattern))
		for _, path := range paths {
			info, err := os.Lstat(path)
			if err == nil && time.Since(info.ModTime()) > 11*time.Minute {
				_ = os.RemoveAll(path)
			}
		}
	}
}

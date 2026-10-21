package core

// Disposable verified source cache beside the existing database. Saved products
// remain authoritative in SQLite. Cache I/O does not hold the receiver mutex.
import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"sort"
	"time"
)

const mapSourceCacheBudget = 4 * demRawSize

func (p *mapProvider) cachedDEMTile(ctx context.Context, name, address string, report *DEMReport, emit demObserver) ([]byte, error) {
	path := filepath.Join(p.demCache, name)
	if p.demCache != "" {
		var raw []byte
		stat, e := os.Stat(path)
		if e == nil && stat.Size() == demRawSize {
			raw, e = os.ReadFile(path)
		}
		var hash []byte
		file, he := os.Open(path + ".sha256")
		if he == nil {
			hash, he = io.ReadAll(io.LimitReader(file, 4096))
			file.Close()
		}
		sum := sha256.Sum256(raw)
		if e == nil && he == nil && len(raw) == demRawSize && hex.EncodeToString(sum[:])+"\n"+address == string(hash) {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			if report != nil {
				report.CachedTiles = append(report.CachedTiles, name)
			}
			emitDEM(emit, DEMProgress{Stage: "verified cached source", Tile: name, Bytes: int64(len(raw))})
			return raw, nil
		}
		// Discard invalid disposable entries before replacement (also works on Windows).
		_ = os.Remove(path)
		_ = os.Remove(path + ".sha256")
	}
	raw, err := p.downloadDEMTile(ctx, name, address, report, emit)
	if err != nil || p.demCache == "" {
		return raw, err
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	if os.MkdirAll(p.demCache, 0700) != nil {
		return raw, nil
	}
	entries, _ := os.ReadDir(p.demCache)
	type item struct {
		name string
		at   time.Time
		size int64
	}
	var files []item
	var total int64
	for _, entry := range entries {
		if filepath.Ext(entry.Name()) == ".hgt" {
			if stat, e := entry.Info(); e == nil {
				total += stat.Size()
				files = append(files, item{entry.Name(), stat.ModTime(), stat.Size()})
			}
		}
	}
	sort.Slice(files, func(i, j int) bool { return files[i].at.Before(files[j].at) })
	for _, f := range files {
		if total+int64(len(raw)) <= mapSourceCacheBudget {
			break
		}
		if os.Remove(filepath.Join(p.demCache, f.name)) != nil {
			return raw, nil
		}
		_ = os.Remove(filepath.Join(p.demCache, f.name+".sha256"))
		total -= f.size
	}
	sum := sha256.Sum256(raw)
	if os.WriteFile(path+".tmp", raw, 0600) == nil {
		if os.Rename(path+".tmp", path) == nil {
			_ = os.WriteFile(path+".sha256", []byte(hex.EncodeToString(sum[:])+"\n"+address), 0600)
		}
	}
	_ = os.Remove(path + ".tmp")
	return raw, nil
}

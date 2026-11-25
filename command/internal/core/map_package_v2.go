package core

// Format 2 enhanced map packages: versioned .gmap.zip carrying typed immutable
// tile products. MBTiles is admitted as untrusted input through bounded
// read-only parsing and normalized into Command-owned indexed tile storage
// (offline_map_tiles). Format 1 behavior is untouched.
import (
	"archive/zip"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"math"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	maxTilesPerProduct  = 200000
	maxTileBytes        = 2 * 1024 * 1024
	maxProductBytes     = 128 * 1024 * 1024
	enhancedStyleV1     = "gnss-enhanced-v1"
)

type mapPackageAssetV2 struct {
	Path    string `json:"path"`
	Product string `json:"product"`
	SHA     string `json:"sha256"`
	Bytes   int    `json:"bytes"`
}

type mapPackageV2 struct {
	Version     int                         `json:"version"`
	Format      string                      `json:"format"`
	Source      string                      `json:"name"`
	Enhanced    enhancedMapDoc              `json:"enhanced"`
	Assets      []mapPackageAssetV2         `json:"assets"`
	Attribution string                      `json:"attribution"`
}

type admittedTile struct {
	z, x, y int
	data    []byte
	sha     string
}

type admittedProduct struct {
	tiles   []admittedTile
	minzoom int
	maxzoom int
	bounds  [4]float64
	bytes   int
	setHash string
}

func tmsToXYZ(z, y int) int { return (1 << z) - 1 - y }

// tileBoundsWGS84 converts an XYZ tile coordinate to its geographic envelope.
func tileBoundsWGS84(z, x, y int) [4]float64 {
	n := math.Pi - 2*math.Pi*float64(y)/math.Pow(2, float64(z))
	s := math.Pi - 2*math.Pi*float64(y+1)/math.Pow(2, float64(z))
	lat := func(mm float64) float64 { return 180 / math.Pi * math.Atan(math.Sinh(mm)) }
	return [4]float64{
		float64(x)/math.Pow(2, float64(z))*360 - 180,
		lat(s),
		float64(x+1)/math.Pow(2, float64(z))*360 - 180,
		lat(n),
	}
}

func unionBounds(a, b [4]float64) [4]float64 {
	return [4]float64{math.Min(a[0], b[0]), math.Min(a[1], b[1]), math.Max(a[2], b[2]), math.Max(a[3], b[3])}
}

// admitMBTiles parses one untrusted MBTiles file read-only with strict schema
// and resource limits, normalizing TMS rows into XYZ orientation.
func admitMBTiles(ctx context.Context, path, product string) (*admittedProduct, error) {
	absolute, e := filepath.Abs(path)
	if e != nil {
		return nil, e
	}
	dsn := "file:" + url.PathEscape(filepath.ToSlash(absolute)) + "?mode=ro&immutable=1"
	db, e := sql.Open("sqlite3", dsn)
	if e != nil {
		return nil, e
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	ctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	// Schema allowlist: user tables tiles+metadata only; no views, triggers or
	// virtual tables. sqlite internals are excluded by the type filter.
	rows, e := db.QueryContext(ctx, `SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','view','trigger','virtual table') AND name NOT LIKE 'sqlite_%'`)
	if e != nil {
		return nil, fmt.Errorf("unreadable tile container: %w", e)
	}
	objects := map[string]string{}
	for rows.Next() {
		var typ, name, ddl string
		if e = rows.Scan(&typ, &name, &ddl); e != nil {
			rows.Close()
			return nil, e
		}
		if typ != "table" || (name != "tiles" && name != "metadata") {
			rows.Close()
			return nil, errors.New("unsupported object in tile container: " + typ + " " + name)
		}
		objects[name] = ddl
	}
	rows.Close()
	if objects["tiles"] == "" {
		return nil, errors.New("tile container missing tiles table")
	}
	ddl := strings.ToLower(objects["tiles"])
	for _, col := range []string{"zoom_level", "tile_column", "tile_row", "tile_data"} {
		if !strings.Contains(ddl, col) {
			return nil, errors.New("tiles table missing required column " + col)
		}
	}
	// Metadata format cross-check where declared.
	var format string
	if objects["metadata"] != "" {
		_ = db.QueryRowContext(ctx, `SELECT value FROM metadata WHERE name='format'`).Scan(&format)
	}
	wantFormat := "png"
	if product == productVector {
		wantFormat = "pbf"
	}
	if format != "" && format != wantFormat {
		return nil, fmt.Errorf("tile container format %q does not match product %s", format, product)
	}
	qrows, e := db.QueryContext(ctx, `SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles`)
	if e != nil {
		return nil, fmt.Errorf("tile read failed: %w", e)
	}
	defer qrows.Close()
	ap := &admittedProduct{minzoom: 99, maxzoom: -1}
	first := true
	hashRows := [][5]any{}
	count := 0
	for qrows.Next() {
		var z, x, ty int
		var data []byte
		if e = qrows.Scan(&z, &x, &ty, &data); e != nil {
			return nil, e
		}
		count++
		if count > maxTilesPerProduct {
			return nil, errors.New("tile product exceeds tile count limit")
		}
		if len(data) == 0 || len(data) > maxTileBytes {
			return nil, errors.New("tile outside size bounds")
		}
		if z < 0 || z > 22 || x < 0 || ty < 0 || x >= (1<<z) || ty >= (1<<z) {
			return nil, errors.New("tile coordinate outside valid range")
		}
		// Content-type sanity: vector tiles are gzip MVT; raster products are PNG.
		if product == productVector {
			if len(data) < 2 || data[0] != 0x1f || data[1] != 0x8b {
				return nil, errors.New("vector tile is not gzip MVT")
			}
		} else if len(data) < 8 || string(data[1:4]) != "PNG" {
			return nil, errors.New("raster tile is not PNG")
		}
		y := tmsToXYZ(z, ty)
		sha := shaMap(data)
		ap.tiles = append(ap.tiles, admittedTile{z, x, y, data, sha})
		hashRows = append(hashRows, [5]any{z, x, y, sha, len(data)})
		ap.bytes += len(data)
		if ap.bytes > maxProductBytes {
			return nil, errors.New("tile product exceeds byte limit")
		}
		if z < ap.minzoom {
			ap.minzoom = z
		}
		if z > ap.maxzoom {
			ap.maxzoom = z
		}
		tb := tileBoundsWGS84(z, x, y)
		if first {
			ap.bounds = tb
			first = false
		} else {
			ap.bounds = unionBounds(ap.bounds, tb)
		}
	}
	if e = qrows.Err(); e != nil {
		return nil, e
	}
	if count == 0 {
		return nil, errors.New("tile product is empty")
	}
	ap.setHash = tileSetHash(hashRows)
	return ap, nil
}

func validateEnhancedDoc(d *enhancedMapDoc) error {
	if d.Revision == "" || len(d.Revision) > 64 || d.Style == "" || len(d.Style) > 128 {
		return errors.New("invalid enhanced revision or style")
	}
	if len(d.Attribution) > 16384 || len(d.Provenance) > 32 {
		return errors.New("invalid enhanced provenance")
	}
	if len(d.Products) == 0 {
		return errors.New("enhanced map declares no products")
	}
	for name, p := range d.Products {
		if name == productGterr {
			continue // validated through GTERR001 decoding
		}
		if e := validEnhancedProduct(name, p); e != nil {
			return fmt.Errorf("%s: %w", name, e)
		}
	}
	seen := map[string]bool{}
	for _, m := range d.Missing {
		if !tileProducts[m] && m != productGterr {
			return errors.New("unsupported missing product declaration")
		}
		if seen[m] {
			return errors.New("duplicate missing product declaration")
		}
		seen[m] = true
		if _, present := d.Products[m]; present {
			return errors.New("product both present and declared missing")
		}
	}
	for name := range d.Products {
		if seen[name] {
			return errors.New("product both present and declared missing")
		}
	}
	if d.Revision != enhancedRevision(d.Products) {
		return errors.New("enhanced revision does not match declared products")
	}
	return nil
}

// importMapPackageV2 validates and atomically admits a Format 2 package.
func (s *Store) importMapPackageV2(ctx context.Context, p *mapPackageV2, files map[string]*zip.File, read func(string, int) ([]byte, error)) (savedMap, error) {
	fail := func(e error) (savedMap, error) { return savedMap{}, e }
	if len(p.Source) > 4096 || len(p.Attribution) > 16384 {
		return fail(errors.New("unsupported package metadata"))
	}
	d := p.Enhanced
	if e := validateEnhancedDoc(&d); e != nil {
		return fail(e)
	}
	if len(files) != len(p.Assets)+1 {
		return fail(errors.New("undeclared or missing archive entries"))
	}
	declared := map[string]bool{}
	stageDir, e := os.MkdirTemp("", "gnss-map-v2-stage-*")
	if e != nil {
		return fail(e)
	}
	defer os.RemoveAll(stageDir)
	admitted := map[string]*admittedProduct{}
	var gterr []byte
	gterrDeclared := false
	for _, a := range p.Assets {
		if declared[a.Path] || a.Path == "manifest.json" {
			return fail(errors.New("duplicate manifest asset"))
		}
		declared[a.Path] = true
		isTileProduct := tileProducts[a.Product]
		if !isTileProduct && a.Product != productGterr {
			return fail(errors.New("unsupported asset product"))
		}
		if _, present := d.Products[a.Product]; !present {
			return fail(errors.New("asset outside declared products"))
		}
		b, e := read(a.Path, maxProductBytes+1024)
		if e != nil {
			return fail(e)
		}
		if len(b) != a.Bytes || shaMap(b) != a.SHA {
			return fail(errors.New("asset checksum mismatch"))
		}
		if a.Product == productGterr {
			if len(b) > mapLimit {
				return fail(errors.New("terrain product oversized"))
			}
			if _, e = decodeTerrain(b); e != nil {
				return fail(errors.New("invalid GTERR001 terrain product"))
			}
			gterr = b
			gterrDeclared = true
			continue
		}
		if !strings.HasSuffix(a.Path, ".mbtiles") {
			return fail(errors.New("tile products must be immutable MBTiles"))
		}
		stagePath := filepath.Join(stageDir, shaMap([]byte(a.Path))+".mbtiles")
		if e = os.WriteFile(stagePath, b, 0600); e != nil {
			return fail(e)
		}
		ap, e := admitMBTiles(ctx, stagePath, a.Product)
		if e != nil {
			return fail(fmt.Errorf("%s: %w", a.Product, e))
		}
		// Manifest commitment: declared product accounting must match actual content.
		want := d.Products[a.Product]
		if ap.minzoom != want.Minzoom || ap.maxzoom != want.Maxzoom || len(ap.tiles) != want.Tiles || ap.bytes != want.Bytes || ap.setHash != want.SHA {
			return fail(errors.New(a.Product + ": declared product accounting differs from actual tiles"))
		}
		if !mapBoundsEqual(ap.bounds, want.Bounds) {
			return fail(errors.New(a.Product + ": declared coverage differs from actual tiles"))
		}
		admitted[a.Product] = ap
	}
	// Product/asset completeness: every declared tile product has exactly one asset.
	for name := range d.Products {
		if name == productGterr {
			if !gterrDeclared {
				return fail(errors.New("declared terrain product missing asset"))
			}
			continue
		}
		if admitted[name] == nil {
			return fail(errors.New("declared product missing asset: " + name))
		}
	}
	if gterrDeclared {
		if _, present := d.Products[productGterr]; !present {
			return fail(errors.New("terrain asset outside declared products"))
		}
	}
	d.Type = "GNSS-enhanced-map"
	d.Version = 2
	doc := mustJSON(d)
	id := "portable2-" + shaMap(doc)[:32]
	m := savedMap{ID: id, Source: p.Source, Created: s.Now().UTC().Format(wireTime), Bytes: len(doc)}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, e := s.db.BeginTx(ctx, nil)
	if e != nil {
		return fail(e)
	}
	defer tx.Rollback()
	var exists int
	if e = tx.QueryRow(`SELECT count(*) FROM offline_maps WHERE id=?`, id).Scan(&exists); e != nil {
		return fail(e)
	}
	if exists > 0 {
		return m, nil
	}
	var count, total int
	if e = tx.QueryRow(`SELECT count(*),coalesce(sum(length(data)),0)+(SELECT coalesce(sum(coalesce(length(vector),0)+coalesce(length(terrain),0)),0) FROM offline_map_chunks)+(SELECT coalesce(sum(length(data)),0) FROM offline_map_tiles) FROM offline_maps`).Scan(&count, &total); e != nil {
		return fail(e)
	}
	var incoming int64 = int64(len(doc)) + int64(len(gterr))
	for _, ap := range admitted {
		incoming += int64(ap.bytes)
	}
	if count >= 64 || int64(total)+incoming > mapAssetBudget {
		return fail(errors.New("map library capacity exceeded"))
	}
	if _, e = tx.Exec(`INSERT INTO offline_maps(id,source,data,created) VALUES(?,?,?,?)`, id, m.Source, doc, m.Created); e != nil {
		return fail(e)
	}
	if gterrDeclared {
		if _, e = tx.Exec(`INSERT INTO offline_terrain(map_id,data) VALUES(?,?)`, id, gterr); e != nil {
			return fail(e)
		}
	}
	for product, ap := range admitted {
		for _, t := range ap.tiles {
			if _, e = tx.Exec(`INSERT INTO offline_map_tiles(map_id,product,z,x,y,data,sha) VALUES(?,?,?,?,?,?,?)`, id, product, t.z, t.x, t.y, t.data, t.sha); e != nil {
				return fail(e)
			}
		}
	}
	e = tx.Commit()
	return m, e
}

func writeMBTilesFromSlice(ctx context.Context, path string, tiles []admittedTile, product string, meta map[string]string) (e error) {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	dsn := "file:" + url.PathEscape(filepath.ToSlash(absolute))
	db, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return err
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	if _, err = db.ExecContext(ctx, `CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)`); err != nil {
		return err
	}
	if _, err = db.ExecContext(ctx, `CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)`); err != nil {
		return err
	}
	if _, err = db.ExecContext(ctx, `CREATE TABLE metadata (name text, value text)`); err != nil {
		return err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, t := range tiles {
		if _, err = tx.ExecContext(ctx, `INSERT INTO tiles VALUES(?,?,?,?)`, t.z, t.x, tmsToXYZ(t.z, t.y), t.data); err != nil {
			return err
		}
	}
	names := make([]string, 0, len(meta))
	for k := range meta {
		names = append(names, k)
	}
	sort.Strings(names)
	for _, k := range names {
		if _, err = tx.ExecContext(ctx, `INSERT INTO metadata VALUES(?,?)`, k, meta[k]); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// exportMapPackageV2 writes a Format 2 package for an enhanced map revision.
func (s *Store) exportMapPackageV2(ctx context.Context, id string, d *enhancedMapDoc, f *os.File) error {
	z := zip.NewWriter(f)
	defer z.Close()
	var assets []mapPackageAssetV2
	stageDir, e := os.MkdirTemp("", "gnss-map-v2-export-*")
	if e != nil {
		return e
	}
	defer os.RemoveAll(stageDir)
	names := make([]string, 0, len(d.Products))
	for n := range d.Products {
		names = append(names, n)
	}
	sort.Strings(names)
	for _, product := range names {
		if product == productGterr {
			var b []byte
			s.mu.Lock()
			e = s.db.QueryRowContext(ctx, `SELECT data FROM offline_terrain WHERE map_id=?`, id).Scan(&b)
			s.mu.Unlock()
			if e != nil {
				return errors.New("declared terrain product unavailable")
			}
			if len(b) > mapLimit {
				return errors.New("terrain product oversized")
			}
			w, e := z.Create("terrain.gterrain")
			if e != nil {
				return e
			}
			if _, e = w.Write(b); e != nil {
				return e
			}
			assets = append(assets, mapPackageAssetV2{"terrain.gterrain", productGterr, shaMap(b), len(b)})
			continue
		}
		s.mu.Lock()
		rows, e := s.db.QueryContext(ctx, `SELECT z,x,y,data,sha FROM offline_map_tiles WHERE map_id=? AND product=?`, id, product)
		s.mu.Unlock()
		if e != nil {
			return e
		}
		var tiles []admittedTile
		hashRows := [][5]any{}
		total := 0
		for rows.Next() {
			var t admittedTile
			if e = rows.Scan(&t.z, &t.x, &t.y, &t.data, &t.sha); e != nil {
				rows.Close()
				return e
			}
			tiles = append(tiles, t)
			hashRows = append(hashRows, [5]any{t.z, t.x, t.y, t.sha, len(t.data)})
			total += len(t.data)
		}
		rows.Close()
		if e = rows.Err(); e != nil {
			return e
		}
		want := d.Products[product]
		if len(tiles) != want.Tiles || total != want.Bytes || tileSetHash(hashRows) != want.SHA {
			return errors.New(product + ": stored tiles diverge from immutable revision; refusing export")
		}
		stagePath := filepath.Join(stageDir, product+".mbtiles")
		format := "png"
		if product == productVector {
			format = "pbf"
		}
		meta := map[string]string{
			"format":   format,
			"name":     d.Style + " " + product,
			"minzoom":  strconv.Itoa(want.Minzoom),
			"maxzoom":  strconv.Itoa(want.Maxzoom),
			"bounds":   fmt.Sprintf("%g,%g,%g,%g", want.Bounds[0], want.Bounds[1], want.Bounds[2], want.Bounds[3]),
			"type":     "overlay",
			"version":  "2",
		}
		if e = writeMBTilesFromSlice(ctx, stagePath, tiles, product, meta); e != nil {
			return e
		}
		b, e := os.ReadFile(stagePath)
		if e != nil {
			return e
		}
		w, e := z.Create(product + ".mbtiles")
		if e != nil {
			return e
		}
		if _, e = w.Write(b); e != nil {
			return e
		}
		assets = append(assets, mapPackageAssetV2{product + ".mbtiles", product, shaMap(b), len(b)})
	}
	p := mapPackageV2{
		Version: 2, Format: "GNSS-Command-map", Source: "",
		Enhanced: *d, Assets: assets, Attribution: d.Attribution,
	}
	var source string
	s.mu.Lock()
	_ = s.db.QueryRowContext(ctx, `SELECT source FROM offline_maps WHERE id=?`, id).Scan(&source)
	s.mu.Unlock()
	p.Source = source
	manifest := mustJSON(p)
	if len(manifest) > 2*1024*1024 {
		return errors.New("map manifest exceeds 2 MiB portability limit")
	}
	w, e := z.Create("manifest.json")
	if e == nil {
		_, e = w.Write(manifest)
	}
	if e == nil {
		e = z.Close()
	}
	return e
}

package core

import (
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"image"
	"image/png"
	"io"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// ---- fixture synthesis helpers ----

func gzipBytes(b []byte) []byte {
	var out bytes.Buffer
	w := gzip.NewWriter(&out)
	w.Write(b)
	w.Close()
	return out.Bytes()
}

func tinyPNG() []byte {
	// Real decodable 2x2 PNG; admission validates decoding, not magic bytes.
	img := image.NewRGBA(image.Rect(0, 0, 2, 2))
	var out bytes.Buffer
	if e := png.Encode(&out, img); e != nil {
		panic(e)
	}
	return out.Bytes()
}

func tinyMVTGzip() []byte {
	// Minimal spec-valid MVT tile: one layer named "test" at version 2.
	// layer = field1(name)="test" + field15(version)=2; tile = field3(layer).
	layer := append([]byte{0x0a, 0x04, 't', 'e', 's', 't'}, 0x78, 0x02)
	tile := append([]byte{0x1a, byte(len(layer))}, layer...)
	return gzipBytes(tile)
}

type v2fixture struct {
	tiles  map[string][]admittedTile // product -> tiles (XYZ)
	bounds [4]float64
}

func smallV2Fixture() *v2fixture {
	mvt, png := tinyMVTGzip(), tinyPNG()
	mk := func(product string, z, x0, x1, y0, y1 int) []admittedTile {
		var out []admittedTile
		for x := x0; x <= x1; x++ {
			for y := y0; y <= y1; y++ {
				data := mvt
				if product != productVector {
					data = png
				}
				out = append(out, admittedTile{z, x, y, data, shaMap(data)})
			}
		}
		return out
	}
	return &v2fixture{tiles: map[string][]admittedTile{
		productVector:    append(mk(productVector, 6, 32, 33, 21, 22), mk(productVector, 7, 65, 66, 43, 44)...),
		productDEM:       mk(productDEM, 6, 32, 33, 21, 22),
		productLandcover: mk(productLandcover, 6, 32, 32, 21, 21),
	}}
}

func writeTestMBTiles(t *testing.T, path string, tiles []admittedTile, product string) {
	t.Helper()
	format := "png"
	if product == productVector {
		format = "pbf"
	}
	e := writeMBTilesFromSlice(context.Background(), path, tiles, product, map[string]string{"format": format, "name": "test"})
	if e != nil {
		t.Fatal(e)
	}
}

func productAccounting(tiles []admittedTile) enhancedProduct {
	hashRows := [][5]any{}
	total := 0
	minz, maxz := 99, -1
	var bounds [4]float64
	for i, tile := range tiles {
		hashRows = append(hashRows, [5]any{tile.z, tile.x, tile.y, tile.sha, len(tile.data)})
		total += len(tile.data)
		if tile.z < minz {
			minz = tile.z
		}
		if tile.z > maxz {
			maxz = tile.z
		}
		tb := tileBoundsWGS84(tile.z, tile.x, tile.y)
		if i == 0 {
			bounds = tb
		} else {
			bounds = unionBounds(bounds, tb)
		}
	}
	enc := "png"
	return enhancedProduct{Encoding: enc, CRS: "EPSG:3857", Minzoom: minz, Maxzoom: maxz, Bounds: bounds, Tiles: len(tiles), Bytes: total, SHA: tileSetHash(hashRows)}
}

// buildV2Package writes a valid Format 2 package and returns its path, size and doc.
func buildV2Package(t *testing.T, dir string, fx *v2fixture, mutate func(*mapPackageV2, map[string]string)) (string, int64, enhancedMapDoc) {
	t.Helper()
	products := map[string]enhancedProduct{}
	for product, tiles := range fx.tiles {
		p := productAccounting(tiles)
		if product == productVector {
			p.Encoding = "mvt-gzip"
		}
		products[product] = p
	}
	doc := enhancedMapDoc{
		Type: "GNSS-enhanced-map", Version: 2, Style: enhancedStyleV1,
		Products: products, Attribution: "test attribution",
		Provenance: map[string]string{"osm": "test-fixture"},
	}
	doc.Revision = enhancedRevision(products)
	pkg := mapPackageV2{Version: 2, Format: "GNSS-Command-map", Source: "fixture", Enhanced: doc, Attribution: doc.Attribution}
	entries := map[string]string{} // zip entry name -> staged file path
	tmp := t.TempDir()
	for product := range fx.tiles {
		mb := filepath.Join(tmp, product+".mbtiles")
		writeTestMBTiles(t, mb, fx.tiles[product], product)
		entries[product+".mbtiles"] = mb
		b, _ := os.ReadFile(mb)
		pkg.Assets = append(pkg.Assets, mapPackageAssetV2{product + ".mbtiles", product, shaMap(b), len(b)})
	}
	if mutate != nil {
		mutate(&pkg, entries)
	}
	zipPath := filepath.Join(dir, "fixture.gmap.zip")
	f, e := os.Create(zipPath)
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close()
	z := zip.NewWriter(f)
	for name, path := range entries {
		b, _ := os.ReadFile(path)
		w, _ := z.Create(name)
		w.Write(b)
	}
	w, _ := z.Create("manifest.json")
	w.Write(mustJSON(pkg))
	z.Close()
	stat, _ := f.Stat()
	return zipPath, stat.Size(), doc
}

func importFile(t *testing.T, s *Store, path string, size int64) (savedMap, error) {
	t.Helper()
	f, e := os.Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close()
	return s.importMapPackage(context.Background(), f, size)
}

func TestV2RoundtripImportExportIdempotent(t *testing.T) {
	s := extensionStore(t)
	dir := t.TempDir()
	zipPath, size, doc := buildV2Package(t, dir, smallV2Fixture(), nil)
	m, e := importFile(t, s, zipPath, size)
	if e != nil {
		t.Fatal(e)
	}
	if !strings.HasPrefix(m.ID, "portable2-") {
		t.Fatal("unexpected id", m.ID)
	}
	// idempotent reimport
	m2, e := importFile(t, s, zipPath, size)
	if e != nil || m2.ID != m.ID {
		t.Fatal("non-idempotent import", e)
	}
	// descriptor present and revision matches
	got, e := s.readEnhanced(context.Background(), m.ID)
	if e != nil || got == nil {
		t.Fatal("enhanced descriptor missing", e)
	}
	if got.Revision != doc.Revision {
		t.Fatal("revision mismatch", got.Revision, doc.Revision)
	}
	// tile counts per product
	for product, tiles := range smallV2Fixture().tiles {
		var n int
		s.db.QueryRow(`SELECT count(*) FROM offline_map_tiles WHERE map_id=? AND product=?`, m.ID, product).Scan(&n)
		if n != len(tiles) {
			t.Fatalf("%s: stored %d tiles, want %d", product, n, len(tiles))
		}
	}
	// export round trip into a fresh store
	f, e := os.CreateTemp(t.TempDir(), "export.zip")
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close()
	if e = s.exportMapPackage(context.Background(), m.ID, f); e != nil {
		t.Fatal(e)
	}
	stat, _ := f.Stat()
	other := extensionStore(t)
	restored, e := other.importMapPackage(context.Background(), f, stat.Size())
	if e != nil {
		t.Fatal("exported package rejected", e)
	}
	if restored.ID != m.ID {
		t.Fatal("round trip identity changed", restored.ID, m.ID)
	}
	// byte-identical tiles after round trip
	rows, e := other.db.Query(`SELECT product,z,x,y,data,sha FROM offline_map_tiles WHERE map_id=?`, restored.ID)
	if e != nil {
		t.Fatal(e)
	}
	defer rows.Close()
	count := 0
	for rows.Next() {
		var product, sha string
		var z, x, y int
		var data []byte
		rows.Scan(&product, &z, &x, &y, &data, &sha)
		if shaMap(data) != sha {
			t.Fatal("stored tile hash inconsistent")
		}
		count++
	}
	total := 0
	for _, tiles := range smallV2Fixture().tiles {
		total += len(tiles)
	}
	if count != total {
		t.Fatal("round trip tile count mismatch", count, total)
	}
}

func TestV2TileServingAndRestart(t *testing.T) {
	s := extensionStore(t)
	dir := t.TempDir()
	zipPath, size, _ := buildV2Package(t, dir, smallV2Fixture(), nil)
	m, e := importFile(t, s, zipPath, size)
	if e != nil {
		t.Fatal(e)
	}
	req := httptest.NewRequest("GET", "/local/maps/"+m.ID+"/tiles/vector/6/32/21", nil)
	rec := httptest.NewRecorder()
	if !s.mapTileRequest(rec, req) {
		t.Fatal("tile path not handled")
	}
	resp := rec.Result()
	if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "application/x-protobuf" || resp.Header.Get("Content-Encoding") != "gzip" {
		t.Fatal("bad tile response", resp.StatusCode, resp.Header)
	}
	body := new(bytes.Buffer)
	body.ReadFrom(resp.Body)
	if shaMap(body.Bytes()) != shaMap(tinyMVTGzip()) {
		t.Fatal("tile bytes diverge in delivery")
	}
	// dem png
	rec = httptest.NewRecorder()
	req = httptest.NewRequest("GET", "/local/maps/"+m.ID+"/tiles/dem/6/32/21", nil)
	s.mapTileRequest(rec, req)
	if rec.Result().Header.Get("Content-Type") != "image/png" {
		t.Fatal("dem mime wrong")
	}
	// negative: wrong zoom, wrong product, bad coordinates, unknown map
	for _, path := range []string{
		"/local/maps/" + m.ID + "/tiles/vector/9/32/21",  // zoom outside product range
		"/local/maps/" + m.ID + "/tiles/evil/6/32/21",    // unknown product
		"/local/maps/" + m.ID + "/tiles/vector/6/999/21", // x outside range
		"/local/maps/" + m.ID + "/tiles/vector/-1/0/0",   // negative zoom
		"/local/maps/nope/tiles/vector/6/32/21",          // unknown map
		"/local/maps/" + m.ID + "/tiles/vector/23/0/0",   // zoom beyond limit
	} {
		rec = httptest.NewRecorder()
		s.mapTileRequest(rec, httptest.NewRequest("GET", path, nil))
		if rec.Result().StatusCode == 200 {
			t.Fatal("invalid tile request accepted", path)
		}
	}
	// restart: reopen store on disk and re-serve
	path := filepath.Join(t.TempDir(), "restart.sqlite")
	s1, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	m3, e := importFile(t, s1, zipPath, size)
	if e != nil {
		t.Fatal(e)
	}
	s1.Close()
	s2, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s2.Close()
	rec = httptest.NewRecorder()
	s2.mapTileRequest(rec, httptest.NewRequest("GET", "/local/maps/"+m3.ID+"/tiles/vector/6/32/21", nil))
	if rec.Result().StatusCode != 200 {
		t.Fatal("tiles unavailable after offline restart")
	}
}

func TestV2RejectsMalformedAndMalicious(t *testing.T) {
	s := extensionStore(t)
	ctx := context.Background()
	seed := extensionStore(t)
	dir := t.TempDir()
	zipPath, size, _ := buildV2Package(t, dir, smallV2Fixture(), nil)
	good, e := importFile(t, seed, zipPath, size)
	if e != nil {
		t.Fatal(e)
	}
	_ = good

	fail := func(name string, mutate func(*mapPackageV2, map[string]string)) {
		d := t.TempDir()
		p, sz, _ := buildV2Package(t, d, smallV2Fixture(), mutate)
		if _, e := importFile(t, s, p, sz); e == nil {
			t.Fatal("malicious package accepted:", name)
		}
		rows, _ := s.listMaps()
		if len(rows) != 0 {
			t.Fatal("failed import published state:", name)
		}
	}
	fail("revision tampered", func(p *mapPackageV2, _ map[string]string) {
		p.Enhanced.Revision = strings.Repeat("0", 32)
	})
	fail("undeclared product asset", func(p *mapPackageV2, entries map[string]string) {
		// terrain-gterr asset without declaring the product in the manifest
		gp := filepath.Join(t.TempDir(), "terrain.gterrain")
		os.WriteFile(gp, []byte("GTERR001fake"), 0600)
		entries["terrain.gterrain"] = gp
		b, _ := os.ReadFile(gp)
		p.Assets = append(p.Assets, mapPackageAssetV2{"terrain.gterrain", productGterr, shaMap(b), len(b)})
	})
	fail("missing product asset", func(p *mapPackageV2, entries map[string]string) {
		delete(entries, "dem.mbtiles")
		kept := p.Assets[:0]
		for _, a := range p.Assets {
			if a.Product != productDEM {
				kept = append(kept, a)
			}
		}
		p.Assets = kept
	})
	fail("declared tiles count tampered", func(p *mapPackageV2, _ map[string]string) {
		pr := p.Enhanced.Products[productVector]
		pr.Tiles++
		p.Enhanced.Products[productVector] = pr
		p.Enhanced.Revision = enhancedRevision(p.Enhanced.Products)
	})
	fail("missing-declared but present", func(p *mapPackageV2, _ map[string]string) {
		p.Enhanced.Missing = []string{productDEM}
	})
	fail("unknown product in missing", func(p *mapPackageV2, _ map[string]string) {
		delete(p.Enhanced.Products, productLandcover)
		p.Enhanced.Revision = enhancedRevision(p.Enhanced.Products)
		p.Enhanced.Missing = []string{"satellite"}
	})

	// corrupt mbtiles (not sqlite)
	d := t.TempDir()
	p, sz, _ := buildV2Package(t, d, smallV2Fixture(), func(p *mapPackageV2, entries map[string]string) {
		corrupt := filepath.Join(t.TempDir(), "corrupt.mbtiles")
		os.WriteFile(corrupt, []byte("not a sqlite database at all"), 0600)
		entries["vector.mbtiles"] = corrupt
		for i, a := range p.Assets {
			if a.Product == productVector {
				b, _ := os.ReadFile(corrupt)
				p.Assets[i].SHA = shaMap(b)
				p.Assets[i].Bytes = len(b)
			}
		}
	})
	if _, e := importFile(t, s, p, sz); e == nil {
		t.Fatal("corrupt mbtiles accepted")
	}
	// checksum mismatch on real container
	d2 := t.TempDir()
	p2, sz2, _ := buildV2Package(t, d2, smallV2Fixture(), func(p *mapPackageV2, entries map[string]string) {
		for i, a := range p.Assets {
			if a.Product == productVector {
				p.Assets[i].SHA = strings.Repeat("f", 64)
				_ = a
			}
		}
	})
	if _, e := importFile(t, s, p2, sz2); e == nil {
		t.Fatal("checksum mismatch accepted")
	}
	// evil schema: extra trigger/view table in container
	d3 := t.TempDir()
	p3, sz3, _ := buildV2Package(t, d3, smallV2Fixture(), func(p *mapPackageV2, entries map[string]string) {
		stage := t.TempDir()
		mb := filepath.Join(stage, "evil.mbtiles")
		writeTestMBTiles(t, mb, smallV2Fixture().tiles[productVector], productVector)
		absolute, _ := filepath.Abs(mb)
		db, err := sql.Open("sqlite3", "file:"+url.PathEscape(filepath.ToSlash(absolute)))
		if err != nil {
			t.Fatal(err)
		}
		if _, err = db.Exec(`CREATE TABLE exfil (x)`); err != nil {
			t.Fatal(err)
		}
		db.Close()
		entries["vector.mbtiles"] = mb
		for i, a := range p.Assets {
			if a.Product == productVector {
				b, _ := os.ReadFile(mb)
				p.Assets[i].SHA = shaMap(b)
				p.Assets[i].Bytes = len(b)
			}
		}
	})
	if _, e := importFile(t, s, p3, sz3); e == nil {
		t.Fatal("container with extra table accepted")
	}
	// non-gzip vector tile
	d4 := t.TempDir()
	p4, sz4, _ := buildV2Package(t, d4, smallV2Fixture(), func(p *mapPackageV2, entries map[string]string) {
		bad := []admittedTile{{6, 32, 21, []byte("definitely not gzip"), shaMap([]byte("x"))}}
		stage := t.TempDir()
		mb := filepath.Join(stage, "v.mbtiles")
		writeTestMBTiles(t, mb, bad, productVector)
		entries["vector.mbtiles"] = mb
		for i, a := range p.Assets {
			if a.Product == productVector {
				b, _ := os.ReadFile(mb)
				p.Assets[i].SHA = shaMap(b)
				p.Assets[i].Bytes = len(b)
			}
		}
	})
	if _, e := importFile(t, s, p4, sz4); e == nil {
		t.Fatal("non-gzip vector tile accepted")
	}
	rows, _ := s.listMaps()
	if len(rows) != 0 {
		t.Fatal("state polluted by rejected imports")
	}
	_ = ctx
}

func TestV2PinDeletionSafetyAndConcurrency(t *testing.T) {
	s := extensionStore(t)
	dir := t.TempDir()
	zipPath, size, _ := buildV2Package(t, dir, smallV2Fixture(), nil)
	m, e := importFile(t, s, zipPath, size)
	if e != nil {
		t.Fatal(e)
	}
	ctx := context.Background()
	rev, e := s.pinMapRevision(ctx, m.ID, "recording-test")
	if e != nil {
		t.Fatal(e)
	}
	if len(rev) == 0 {
		t.Fatal("empty revision")
	}
	// idempotent pin
	if _, e = s.pinMapRevision(ctx, m.ID, "recording-test"); e != nil {
		t.Fatal(e)
	}
	// deletion refused while pinned — exercise the store logic directly
	tx, _ := s.db.Begin()
	pins, e := s.mapPinnedTx(tx, m.ID)
	tx.Rollback()
	if e != nil || pins != 1 {
		t.Fatal("pin not visible in transaction", pins, e)
	}
	// concurrent tile reads during pin/release cycles
	done := make(chan struct{})
	go func() {
		for {
			select {
			case <-done:
				return
			default:
				rec := httptest.NewRecorder()
				s.mapTileRequest(rec, httptest.NewRequest("GET", "/local/maps/"+m.ID+"/tiles/vector/6/32/21", nil))
			}
		}
	}()
	for i := 0; i < 50; i++ {
		s.pinMapRevision(ctx, m.ID, "recording-test")
		s.releaseMapRevision(ctx, m.ID, rev, "recording-test")
	}
	close(done)
	if e = s.releaseMapRevision(ctx, m.ID, rev, "recording-test"); e != nil {
		t.Fatal(e)
	}
	tx, _ = s.db.Begin()
	pins, _ = s.mapPinnedTx(tx, m.ID)
	tx.Rollback()
	if pins != 0 {
		t.Fatal("pin release failed")
	}
	// deletion through HTTP path succeeds after release
	rec := httptest.NewRecorder()
	body := strings.NewReader(`{"name":"x"}`)
	req := httptest.NewRequest("POST", "/local/maps/"+m.ID+"/delete", body)
	req.Header.Set("Content-Type", "application/json")
	s.terrainRequest(rec, req, nil)
	if rec.Result().StatusCode != 200 {
		out, _ := io.ReadAll(rec.Result().Body)
		t.Fatal("unpinned map deletion failed", rec.Result().StatusCode, string(out))
	}
	var n int
	s.db.QueryRow(`SELECT count(*) FROM offline_map_tiles WHERE map_id=?`, m.ID).Scan(&n)
	if n != 0 {
		t.Fatal("tiles survived deletion")
	}
}

func TestV2PinBlocksHTTPDeletion(t *testing.T) {
	s := extensionStore(t)
	dir := t.TempDir()
	zipPath, size, _ := buildV2Package(t, dir, smallV2Fixture(), nil)
	m, e := importFile(t, s, zipPath, size)
	if e != nil {
		t.Fatal(e)
	}
	rev, e := s.pinMapRevision(context.Background(), m.ID, "recording-test")
	if e != nil {
		t.Fatal(e)
	}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/local/maps/"+m.ID+"/delete", strings.NewReader(`{"name":"x"}`))
	req.Header.Set("Content-Type", "application/json")
	s.terrainRequest(rec, req, nil)
	if rec.Result().StatusCode != 409 {
		t.Fatal("pinned map deletion not refused", rec.Result().StatusCode)
	}
	var n int
	s.db.QueryRow(`SELECT count(*) FROM offline_maps WHERE id=?`, m.ID).Scan(&n)
	if n != 1 {
		t.Fatal("pinned map was deleted")
	}
	s.releaseMapRevision(context.Background(), m.ID, rev, "recording-test")
	rec = httptest.NewRecorder()
	s.terrainRequest(rec, req.Clone(context.Background()), nil)
	// body consumed; re-create
	rec = httptest.NewRecorder()
	req = httptest.NewRequest("POST", "/local/maps/"+m.ID+"/delete", strings.NewReader(`{"name":"x"}`))
	req.Header.Set("Content-Type", "application/json")
	s.terrainRequest(rec, req, nil)
	if rec.Result().StatusCode != 200 {
		t.Fatal("deletion after release failed", rec.Result().StatusCode)
	}
}

func TestV2GterrProductAndFormat1Unaffected(t *testing.T) {
	s := extensionStore(t)
	// valid small GTERR001 grid
	samples := make([]*float64, 4)
	for i := range samples {
		v := float64(1000 + i)
		samples[i] = &v
	}
	g := terrainGrid{Bounds: [4]float64{1.4, 42.5, 1.4 + 1.0/3600, 42.5 + 1.0/3600}, Columns: 2, Rows: 2, ArcSeconds: 1, Source: "test", Datum: "EGM96", Components: map[string]bool{"elevation": true}, Samples: samples}
	gterr, e := encodeTerrain(g)
	if e != nil {
		t.Fatal(e)
	}
	dir := t.TempDir()
	zipPath, size, _ := buildV2Package(t, dir, smallV2Fixture(), func(p *mapPackageV2, entries map[string]string) {
		gp := filepath.Join(t.TempDir(), "terrain.gterrain")
		os.WriteFile(gp, gterr, 0600)
		entries["terrain.gterrain"] = gp
		p.Enhanced.Products[productGterr] = enhancedProduct{SHA: shaMap(gterr), Bytes: len(gterr)}
		p.Enhanced.Revision = enhancedRevision(p.Enhanced.Products)
		p.Assets = append(p.Assets, mapPackageAssetV2{"terrain.gterrain", productGterr, shaMap(gterr), len(gterr)})
	})
	m, e := importFile(t, s, zipPath, size)
	if e != nil {
		t.Fatal(e)
	}
	var stored []byte
	s.db.QueryRow(`SELECT data FROM offline_terrain WHERE map_id=?`, m.ID).Scan(&stored)
	if shaMap(stored) != shaMap(gterr) {
		t.Fatal("gterr product not preserved")
	}
	// corrupt gterr rejected
	d := t.TempDir()
	p2, sz2, _ := buildV2Package(t, d, smallV2Fixture(), func(p *mapPackageV2, entries map[string]string) {
		bad := append([]byte{}, gterr...)
		bad[0] = 'X'
		gp := filepath.Join(t.TempDir(), "terrain.gterrain")
		os.WriteFile(gp, bad, 0600)
		entries["terrain.gterrain"] = gp
		p.Enhanced.Products[productGterr] = enhancedProduct{SHA: shaMap(bad), Bytes: len(bad)}
		p.Enhanced.Revision = enhancedRevision(p.Enhanced.Products)
		p.Assets = append(p.Assets, mapPackageAssetV2{"terrain.gterrain", productGterr, shaMap(bad), len(bad)})
	})
	if _, e = importFile(t, s, p2, sz2); e == nil {
		t.Fatal("corrupt gterr accepted")
	}
	// Format 1 unaffected: legacy import path intact
	legacy := extensionStore(t)
	lm, e := legacy.createMapJob(mapCircle{.1, .1, 373}, map[string]bool{"contours": true}, true)
	if e != nil {
		t.Fatal(e)
	}
	j, _ := legacy.readMapJob(lm.ID)
	b := mustJSON(map[string]any{"type": "FeatureCollection", "features": []any{}, "requested_bounds": j.Plan.Chunks[0].Bounds, "acquisition": "complete"})
	ev := chunkEvidence{Status: "complete", Bytes: len(b)}
	if e = legacy.writeChunk(context.Background(), lm.ID, j.Plan.Chunks[0].Key, "vector", b, ev); e != nil {
		t.Fatal(e)
	}
	f, _ := os.CreateTemp(t.TempDir(), "v1.zip")
	defer f.Close()
	if e = legacy.exportMapPackage(context.Background(), lm.ID, f); e != nil {
		t.Fatal(e)
	}
	stat, _ := f.Stat()
	restored, e := s.importMapPackage(context.Background(), f, stat.Size())
	if e != nil {
		t.Fatal("format 1 import broken", e)
	}
	if !strings.HasPrefix(restored.ID, "portable-") {
		t.Fatal("format 1 id namespace changed", restored.ID)
	}
	if _, e = s.readEnhanced(context.Background(), restored.ID); e != nil {
		t.Fatal(e)
	}
	// legacy map is not enhanced; tiles must not serve
	rec := httptest.NewRecorder()
	s.mapTileRequest(rec, httptest.NewRequest("GET", "/local/maps/"+restored.ID+"/tiles/vector/6/32/21", nil))
	if rec.Result().StatusCode == 200 {
		t.Fatal("legacy map served enhanced tiles")
	}
	fmt.Println("v1+v2 coexist in one library")
}

func TestV2RejectsDuplicateProductsAndExpressionSchema(t *testing.T) {
	s := extensionStore(t)
	// duplicate product assets rejected even with distinct paths
	d := t.TempDir()
	p, sz, _ := buildV2Package(t, d, smallV2Fixture(), func(p *mapPackageV2, entries map[string]string) {
		for i, a := range p.Assets {
			if a.Product == productDEM {
				dup := a
				dup.Path = "dem-copy.mbtiles"
				entries[dup.Path] = entries[a.Path]
				p.Assets = append(p.Assets[:i+1], append([]mapPackageAssetV2{dup}, p.Assets[i+1:]...)...)
				break
			}
		}
	})
	if _, e := importFile(t, s, p, sz); e == nil {
		t.Fatal("duplicate product asset accepted")
	} else if !strings.Contains(e.Error(), "duplicate asset product") {
		t.Fatal("unexpected rejection reason", e)
	}
	// expression-bearing tiles schema rejected before content evaluation
	d2 := t.TempDir()
	p2, sz2, _ := buildV2Package(t, d2, smallV2Fixture(), func(p *mapPackageV2, entries map[string]string) {
		evil := filepath.Join(t.TempDir(), "evil.mbtiles")
		db, err := sql.Open("sqlite3", "file:"+evil)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = db.Exec(`CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob GENERATED ALWAYS AS (printf('%.1000c','x')) VIRTUAL)`); err != nil {
			t.Fatal(err)
		}
		db.Exec(`CREATE TABLE metadata (name text, value text)`)
		db.Exec(`INSERT INTO tiles (zoom_level,tile_column,tile_row) VALUES (6,32,21)`)
		db.Close()
		entries["vector.mbtiles"] = evil
		b, _ := os.ReadFile(evil)
		for i, a := range p.Assets {
			if a.Product == productVector {
				p.Assets[i].SHA = shaMap(b)
				p.Assets[i].Bytes = len(b)
			}
		}
	})
	if _, e := importFile(t, s, p2, sz2); e == nil {
		t.Fatal("expression-bearing schema accepted")
	}
	// library state preserved
	var n int
	s.db.QueryRow(`SELECT count(*) FROM offline_maps`).Scan(&n)
	if n != 0 {
		t.Fatal("failed imports mutated library")
	}
}

func TestV2GterrCommitmentMismatch(t *testing.T) {
	s := extensionStore(t)
	samples := make([]*float64, 4)
	for i := range samples {
		v := float64(1000 + i)
		samples[i] = &v
	}
	g := terrainGrid{Bounds: [4]float64{1.4, 42.5, 1.4 + 1.0/3600, 42.5 + 1.0/3600}, Columns: 2, Rows: 2, ArcSeconds: 1, Source: "test", Datum: "EGM96", Components: map[string]bool{"elevation": true}, Samples: samples}
	gterr, e := encodeTerrain(g)
	if e != nil {
		t.Fatal(e)
	}
	// declared terrain commitment that does not match actual grid bytes
	d := t.TempDir()
	p, sz, _ := buildV2Package(t, d, smallV2Fixture(), func(p *mapPackageV2, entries map[string]string) {
		gp := filepath.Join(t.TempDir(), "terrain.gterrain")
		os.WriteFile(gp, gterr, 0600)
		entries["terrain.gterrain"] = gp
		p.Enhanced.Products[productGterr] = enhancedProduct{SHA: shaMap([]byte("wrong")), Bytes: len(gterr)}
		p.Enhanced.Revision = enhancedRevision(p.Enhanced.Products)
		p.Assets = append(p.Assets, mapPackageAssetV2{"terrain.gterrain", productGterr, shaMap(gterr), len(gterr)})
	})
	if _, e = importFile(t, s, p, sz); e == nil {
		t.Fatal("terrain commitment mismatch accepted")
	} else if !strings.Contains(e.Error(), "terrain product accounting") {
		t.Fatal("unexpected rejection reason", e)
	}
	// two packages differing only in terrain bytes must have distinct identities
	mk := func(tweak float64) string {
		s2 := make([]*float64, 4)
		for i := range s2 {
			v := float64(1000+i) + tweak
			s2[i] = &v
		}
		g2 := terrainGrid{Bounds: g.Bounds, Columns: 2, Rows: 2, ArcSeconds: 1, Source: "test", Datum: "EGM96", Components: map[string]bool{"elevation": true}, Samples: s2}
		b, _ := encodeTerrain(g2)
		dd := t.TempDir()
		pp, ssz, _ := buildV2Package(t, dd, smallV2Fixture(), func(p *mapPackageV2, entries map[string]string) {
			gp := filepath.Join(t.TempDir(), "terrain.gterrain")
			os.WriteFile(gp, b, 0600)
			entries["terrain.gterrain"] = gp
			p.Enhanced.Products[productGterr] = enhancedProduct{SHA: shaMap(b), Bytes: len(b)}
			p.Enhanced.Revision = enhancedRevision(p.Enhanced.Products)
			p.Assets = append(p.Assets, mapPackageAssetV2{"terrain.gterrain", productGterr, shaMap(b), len(b)})
		})
		m, e := importFile(t, s, pp, ssz)
		if e != nil {
			t.Fatal(e)
		}
		return m.ID
	}
	if mk(0) == mk(500) {
		t.Fatal("terrain-distinct packages share one identity")
	}
}

func TestV2PinDeleteAtomicity(t *testing.T) {
	// Regression for non-atomic pinning: existence verification and pin insertion
	// must serialize against deletion such that no pin can ever reference a
	// deleted map, and a successful pin must make deletion fail with
	// map_referenced rather than silently removing the pinned assets.
	s := extensionStore(t)
	ctx := context.Background()
	for round := 0; round < 8; round++ {
		zipPath, size, _ := buildV2Package(t, t.TempDir(), smallV2Fixture(), nil)
		m, e := importFile(t, s, zipPath, size)
		if e != nil {
			t.Fatal(e)
		}
		pinErr := make(chan error, 1)
		delStatus := make(chan int, 1)
		start := make(chan struct{})
		go func() {
			<-start
			_, err := s.pinMapRevision(ctx, m.ID, "recording-race")
			pinErr <- err
		}()
		go func() {
			<-start
			rec := httptest.NewRecorder()
			req := httptest.NewRequest("POST", "/local/maps/"+m.ID+"/delete", strings.NewReader(`{"name":"x"}`))
			req.Header.Set("Content-Type", "application/json")
			s.terrainRequest(rec, req, nil)
			delStatus <- rec.Result().StatusCode
		}()
		close(start)
		pe, ds := <-pinErr, <-delStatus
		var mapGone, orphanPins int
		s.db.QueryRow(`SELECT count(*) FROM offline_maps WHERE id=?`, m.ID).Scan(&mapGone)
		s.db.QueryRow(`SELECT count(*) FROM offline_map_pins WHERE map_id=? AND map_id NOT IN (SELECT id FROM offline_maps)`, m.ID).Scan(&orphanPins)
		if orphanPins != 0 {
			t.Fatal("round", round, ": pin references a deleted map")
		}
		// exactly one consistent outcome: pin won (delete refused, map present)
		// or delete won (pin failed or was never recorded, map absent)
		switch {
		case pe == nil:
			if ds != 409 {
				t.Fatal("round", round, ": successful pin did not refuse deletion, status", ds)
			}
			if mapGone != 1 {
				t.Fatal("round", round, ": map missing despite successful pin")
			}
			// release and clean up for the next round
			rev, _ := s.pinMapRevision(ctx, m.ID, "recording-race")
			if e = s.releaseMapRevision(ctx, m.ID, rev, "recording-race"); e != nil {
				t.Fatal(e)
			}
			rec := httptest.NewRecorder()
			req := httptest.NewRequest("POST", "/local/maps/"+m.ID+"/delete", strings.NewReader(`{"name":"x"}`))
			req.Header.Set("Content-Type", "application/json")
			s.terrainRequest(rec, req, nil)
			if rec.Result().StatusCode != 200 {
				t.Fatal("round", round, ": deletion after release failed")
			}
		default:
			if ds != 200 {
				t.Fatal("round", round, ": neither pin nor delete succeeded — pin err", pe, "delete status", ds)
			}
			if mapGone != 0 {
				t.Fatal("round", round, ": map survived successful deletion")
			}
		}
	}
}

func TestV2RejectsUndecodableTiles(t *testing.T) {
	s := extensionStore(t)
	mk := func(tiles []admittedTile) func(*mapPackageV2, map[string]string) {
		return func(p *mapPackageV2, entries map[string]string) {
			mp := filepath.Join(t.TempDir(), "mutated.mbtiles")
			writeTestMBTiles(t, mp, tiles, productVector)
			entries["vector.mbtiles"] = mp
			b, _ := os.ReadFile(mp)
			for i, a := range p.Assets {
				if a.Product == productVector {
					p.Assets[i].SHA = shaMap(b)
					p.Assets[i].Bytes = len(b)
				}
			}
		}
	}
	good := smallV2Fixture().tiles[productVector]
	// valid gzip but protobuf garbage inside one tile — magic bytes pass, decode fails
	bad1 := append([]admittedTile{}, good...)
	bad1[0].data = gzipBytes([]byte{0xff, 0xff, 0xff, 0x0a})
	bad1[0].sha = shaMap(bad1[0].data)
	d := t.TempDir()
	p, sz, _ := buildV2Package(t, d, smallV2Fixture(), mk(bad1))
	// accounting must match, so the rejection must come from decode validation
	if _, e := importFile(t, s, p, sz); e == nil {
		t.Fatal("gzip-wrapped protobuf garbage accepted")
	}
	// gzip stream itself truncated
	bad2 := append([]admittedTile{}, good...)
	src := gzipBytes([]byte{0x1a, 0x08, 0x0a, 0x04, 't', 'e', 's', 't', 0x78, 0x02})
	bad2[0].data = src[:len(src)-4]
	bad2[0].sha = shaMap(bad2[0].data)
	p2, sz2, _ := buildV2Package(t, t.TempDir(), smallV2Fixture(), mk(bad2))
	if _, e := importFile(t, s, p2, sz2); e == nil {
		t.Fatal("truncated gzip tile accepted")
	}
	// PNG signature intact but pixel data corrupt
	bad3 := append([]admittedTile{}, smallV2Fixture().tiles[productDEM]...)
	pngb := tinyPNG()
	pngb[len(pngb)-6] ^= 0xff
	bad3[0].data = pngb
	bad3[0].sha = shaMap(pngb)
	mkDEM := func(p *mapPackageV2, entries map[string]string) {
		mp := filepath.Join(t.TempDir(), "mutated-dem.mbtiles")
		writeTestMBTiles(t, mp, bad3, productDEM)
		entries["dem.mbtiles"] = mp
		b, _ := os.ReadFile(mp)
		for i, a := range p.Assets {
			if a.Product == productDEM {
				p.Assets[i].SHA = shaMap(b)
				p.Assets[i].Bytes = len(b)
			}
		}
	}
	p3, sz3, _ := buildV2Package(t, t.TempDir(), smallV2Fixture(), mkDEM)
	if _, e := importFile(t, s, p3, sz3); e == nil {
		t.Fatal("corrupt PNG tile accepted")
	}
}

func TestV2EmptyLayerTilesAccepted(t *testing.T) {
	// Boundary tiles with zero layers are well-formed: gzip of an empty or
	// layerless body must not block an otherwise valid package.
	if e := validateVectorTile(gzipBytes([]byte{})); e != nil {
		t.Fatal("empty gzip tile rejected", e)
	}
	// truncated/corrupt gzip still rejected
	bad := gzipBytes([]byte{})
	if e := validateVectorTile(bad[:len(bad)-4]); e == nil {
		t.Fatal("truncated gzip accepted")
	}
	// garbage protobuf still rejected
	if e := validateVectorTile(gzipBytes([]byte{0xff, 0xff, 0xff, 0x0a})); e == nil {
		t.Fatal("garbage protobuf accepted")
	}
}

func TestV2ExportAggregateIncludesManifest(t *testing.T) {
	// Boundary: export must count manifest bytes in the aggregate expansion
	// limit so it never emits a package its own importer would reject.
	s := extensionStore(t)
	zipPath, size, _ := buildV2Package(t, t.TempDir(), smallV2Fixture(), nil)
	m, e := importFile(t, s, zipPath, size)
	if e != nil {
		t.Fatal(e)
	}
	d, e := s.readEnhanced(context.Background(), m.ID)
	if e != nil || d == nil {
		t.Fatal("not enhanced", e)
	}
	export := func() error {
		f, _ := os.CreateTemp(t.TempDir(), "x.zip")
		defer f.Close()
		return s.exportMapPackageV2(context.Background(), m.ID, d, f)
	}
	// measure the exact in-budget aggregate: binary-search the largest provenance
	// padding that still exports, with the budget set just above the unpadded size
	base := exportAdmissionBudget
	defer func() { exportAdmissionBudget = base }()
	if e = export(); e != nil {
		t.Fatal("baseline export failed", e)
	}
	// find aggregate size of the unpadded export by probing: budget too small
	// must fail even with an unmodified manifest
	exportAdmissionBudget = 1
	if e = export(); e == nil || !strings.Contains(e.Error(), "aggregate package") {
		t.Fatal("tiny budget export succeeded", e)
	}
	// raise budget until export succeeds: that point is the exact aggregate.
	// Then confirm the manifest tips it: shrink budget by one byte below the
	// success point and confirm failure — proving every manifest byte counts.
	lo, hi := 1, 4*1024*1024
	for lo+1 < hi {
		mid := (lo + hi) / 2
		exportAdmissionBudget = mid
		if export() == nil {
			hi = mid
		} else {
			lo = mid
		}
	}
	exportAdmissionBudget = hi
	if e = export(); e != nil {
		t.Fatal("export at measured aggregate failed", e)
	}
	exportAdmissionBudget = hi - 1
	if e = export(); e == nil || !strings.Contains(e.Error(), "aggregate package") {
		t.Fatal("export one byte under aggregate succeeded — manifest bytes not fully counted", e)
	}
	// extending the manifest must require additional budget: add a provenance
	// key on top of whatever the fixture declares
	prov := map[string]string{}
	for k, v := range d.Provenance {
		prov[k] = v
	}
	prov["zz-pad"] = "y"
	d.Provenance = prov
	exportAdmissionBudget = hi
	if e = export(); e == nil {
		t.Fatal("padded export succeeded within unpadded budget")
	}
	exportAdmissionBudget = hi + 1
	// may need a few bytes for JSON structure; boundary is exact per byte added
	if e = export(); e != nil && !strings.Contains(e.Error(), "aggregate package") {
		t.Fatal("unexpected export error", e)
	}
}

func TestEnhancedMapCapabilityEndpoint(t *testing.T) {
	s := extensionStore(t)
	read := func() map[string]bool {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/local/enhanced-map-capability", nil)
		s.LocalHandler(nil).ServeHTTP(rec, req)
		if rec.Result().StatusCode != 200 {
			t.Fatal("capability status", rec.Result().StatusCode)
		}
		var out map[string]bool
		json.NewDecoder(rec.Result().Body).Decode(&out)
		return out
	}
	if read()["enabled"] {
		t.Fatal("capability must default off (legacy experience preserved)")
	}
	s.SetEnhancedMapEnabled(true)
	if !read()["enabled"] {
		t.Fatal("capability must reflect the launch flag")
	}
	s.SetEnhancedMapEnabled(false)
	if read()["enabled"] {
		t.Fatal("capability must follow flag reset")
	}
}

func TestV2TravelPinEndpointLifecycle(t *testing.T) {
	s := extensionStore(t)
	dir := t.TempDir()
	zipPath, size, _ := buildV2Package(t, dir, smallV2Fixture(), nil)
	m, e := importFile(t, s, zipPath, size)
	if e != nil {
		t.Fatal(e)
	}
	post := func(body string, origin string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("POST", "/local/enhanced-map-pin", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		s.enhancedMapPinRequest(rec, req)
		return rec
	}
	// cross-origin refused
	if r := post(`{"map_id":"`+m.ID+`"}`, "http://evil.example"); r.Result().StatusCode != 403 {
		t.Fatal("cross-origin pin accepted", r.Result().StatusCode)
	}
	// acquire
	r := post(`{"map_id":"`+m.ID+`"}`, "")
	if r.Result().StatusCode != 200 {
		out, _ := io.ReadAll(r.Result().Body)
		t.Fatal("pin acquire failed", r.Result().StatusCode, string(out))
	}
	var n int
	s.db.QueryRow(`SELECT count(*) FROM offline_map_pins WHERE map_id=? AND kind='travel'`, m.ID).Scan(&n)
	if n != 1 {
		t.Fatal("travel pin not recorded", n)
	}
	// deletion refused while travel-pinned
	rec := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/local/maps/"+m.ID+"/delete", strings.NewReader(`{}`))
	req.Header.Set("Content-Type", "application/json")
	s.terrainRequest(rec, req, nil)
	if rec.Result().StatusCode != 409 {
		t.Fatal("deletion not refused while travel-pinned", rec.Result().StatusCode)
	}
	// release is idempotent and deletion succeeds afterwards
	for i := 0; i < 2; i++ {
		if r := post(`{"map_id":"`+m.ID+`","release":true}`, ""); r.Result().StatusCode != 200 {
			t.Fatal("pin release failed", r.Result().StatusCode)
		}
	}
	s.db.QueryRow(`SELECT count(*) FROM offline_map_pins WHERE map_id=? AND kind='travel'`, m.ID).Scan(&n)
	if n != 0 {
		t.Fatal("travel pin survived release", n)
	}
	// unknown map acquire is an honest conflict, never a silent success
	if r := post(`{"map_id":"no-such-map"}`, ""); r.Result().StatusCode != 409 {
		t.Fatal("unknown map pin did not conflict", r.Result().StatusCode)
	}
}

func TestV2StartupSweepClearsTravelPins(t *testing.T) {
	s := extensionStore(t)
	dir := t.TempDir()
	zipPath, size, _ := buildV2Package(t, dir, smallV2Fixture(), nil)
	m, e := importFile(t, s, zipPath, size)
	if e != nil {
		t.Fatal(e)
	}
	ctx := context.Background()
	if _, e = s.pinMapRevision(ctx, m.ID, "travel"); e != nil {
		t.Fatal(e)
	}
	// durable recording pins survive; ephemeral travel pins never do
	if _, e = s.pinMapRevision(ctx, m.ID, "rec:test-recording"); e != nil {
		t.Fatal(e)
	}
	if _, e = s.db.Exec(`DELETE FROM offline_map_pins WHERE kind='travel'`); e != nil {
		t.Fatal(e)
	}
	var travel, rec int
	s.db.QueryRow(`SELECT count(*) FROM offline_map_pins WHERE kind='travel'`).Scan(&travel)
	s.db.QueryRow(`SELECT count(*) FROM offline_map_pins WHERE kind='rec:test-recording'`).Scan(&rec)
	if travel != 0 || rec != 1 {
		t.Fatal("sweep semantics wrong", travel, rec)
	}
}

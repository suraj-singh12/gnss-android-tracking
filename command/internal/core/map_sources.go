package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type regionFeature struct {
	Geometry   json.RawMessage `json:"geometry"`
	Properties struct {
		ID   string            `json:"id"`
		Name string            `json:"name"`
		URLs map[string]string `json:"urls"`
	} `json:"properties"`
}

// Full rectangle coverage, including holes/concave coastlines: never bbox-overlap
// reuse. A rectangle touching a publisher boundary is conservatively refused.
func footprintCovers(raw json.RawMessage, b [4]float64) bool {
	var g struct {
		Type        string          `json:"type"`
		Coordinates json.RawMessage `json:"coordinates"`
	}
	if json.Unmarshal(raw, &g) != nil {
		return false
	}
	var polygons [][][][2]float64
	if g.Type == "Polygon" {
		var p [][][2]float64
		if json.Unmarshal(g.Coordinates, &p) != nil {
			return false
		}
		polygons = append(polygons, p)
	} else if g.Type == "MultiPolygon" {
		if json.Unmarshal(g.Coordinates, &polygons) != nil {
			return false
		}
	} else {
		return false
	}
	inside := func(p [2]float64, ring [][2]float64) bool {
		in := false
		if len(ring) < 4 {
			return false
		}
		for i, j := 0, len(ring)-1; i < len(ring); j, i = i, i+1 {
			a, c := ring[i], ring[j]
			if (a[1] > p[1]) != (c[1] > p[1]) && p[0] < (c[0]-a[0])*(p[1]-a[1])/(c[1]-a[1])+a[0] {
				in = !in
			}
		}
		return in
	}
	corners := [][2]float64{{b[0], b[1]}, {b[2], b[1]}, {b[2], b[3]}, {b[0], b[3]}}
	for _, poly := range polygons {
		if len(poly) == 0 {
			continue
		}
		covers := true
		for _, p := range corners {
			if !inside(p, poly[0]) {
				covers = false
			}
			for _, hole := range poly[1:] {
				if inside(p, hole) {
					covers = false
				}
			}
		}
		if !covers {
			continue
		}
		// A polygon edge or hole inside/crossing this rectangle invalidates full coverage.
		for _, ring := range poly {
			for i := 1; i < len(ring); i++ {
				a, c := ring[i-1], ring[i]
				if segmentIntersectsBox(a, c, b) {
					covers = false
				}
			}
		}
		if covers {
			return true
		}
	}
	return false
}
func segmentIntersectsBox(a, c [2]float64, b [4]float64) bool {
	lo, hi := 0.0, 1.0
	for axis := 0; axis < 2; axis++ {
		d := c[axis] - a[axis]
		if d == 0 {
			if a[axis] < b[axis] || a[axis] > b[axis+2] {
				return false
			}
			continue
		}
		x, y := (b[axis]-a[axis])/d, (b[axis+2]-a[axis])/d
		if x > y {
			x, y = y, x
		}
		lo = max(lo, x)
		hi = min(hi, y)
		if lo > hi {
			return false
		}
	}
	return true
}
func sourceCovers(src osmSource, b [4]float64) bool {
	if b[0] < src.Bounds[0] || b[1] < src.Bounds[1] || b[2] > src.Bounds[2] || b[3] > src.Bounds[3] {
		return false
	}
	return len(src.Footprint) == 0 || footprintCovers(src.Footprint, b)
}
func (s *Store) acquireVector(ctx context.Context, p *mapProvider, id string, c mapChunk, emit demObserver) ([]byte, error) {
	j, e := s.readMapJob(id)
	if e != nil {
		return nil, e
	}
	// Imported maps bind one immutable source revision. Automatic sources record
	// each chunk's revision; neither mixing snapshots nor overlap implies coherence.
	sources, e := s.osmSources()
	if e != nil {
		return nil, e
	}
	for _, src := range sources {
		if (j.SourceID == "" || j.SourceID == src.ID) && sourceCovers(src, c.Bounds) {
			emitDEM(emit, DEMProgress{Stage: "extracting verified cached regional source"})
			return s.sourceVectors(ctx, src, c.Bounds)
		}
	}
	if j.SourceID != "" {
		return nil, errors.New("imported source does not cover this chunk")
	}
	if b, e := s.reuseVector(ctx, id, c); e != nil || b != nil {
		return b, e
	}
	emitDEM(emit, DEMProgress{Stage: "bounded Overpass request"})
	b, overpassErr := p.areaBounds(ctx, c.Bounds)
	if budget, ok := ctx.Value(mapBudgetKey{}).(*mapBudget); ok {
		message := ""
		if overpassErr != nil {
			message = overpassErr.Error()
		}
		budget.attempts = append(budget.attempts, mapSourceAttempt{p.overpass, "Overpass ways", message, time.Now().UTC().Format(wireTime)})
	}
	if overpassErr == nil {
		return b, nil
	}
	if errors.Is(overpassErr, errMapConsent) || ctx.Err() != nil {
		return nil, overpassErr
	}
	emitDEM(emit, DEMProgress{Stage: "Overpass unavailable; discovering covering regional source", Error: overpassErr.Error()})
	src, e := s.discoverRegion(ctx, p, c.Bounds, emit)
	if budget, ok := ctx.Value(mapBudgetKey{}).(*mapBudget); ok {
		message := ""
		if e != nil {
			message = e.Error()
		}
		budget.attempts = append(budget.attempts, mapSourceAttempt{src.URL, "regional catalog / PBF", message, time.Now().UTC().Format(wireTime)})
	}
	if e != nil {
		return nil, fmt.Errorf("Overpass: %v; regional fallback: %w", overpassErr, e)
	}
	if !sourceCovers(src, c.Bounds) {
		return nil, errors.New("regional source boundary does not cover complete chunk; geographic gap retained")
	}
	return s.sourceVectors(ctx, src, c.Bounds)
}
func (s *Store) reuseVector(ctx context.Context, id string, c mapChunk) ([]byte, error) {
	// Only exact, validated coverage envelopes. Never infer completeness from overlap.
	db := s.viewDB
	if db == nil {
		db = s.db
	}
	rows, e := db.QueryContext(ctx, `SELECT vector FROM offline_map_chunks WHERE map_id<>? AND vector IS NOT NULL AND json_extract(vector_evidence,'$.status')='complete' LIMIT 2048`, id)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	for rows.Next() {
		var b []byte
		if e = rows.Scan(&b); e != nil {
			return nil, e
		}
		var v struct {
			Bounds [4]float64 `json:"requested_bounds"`
		}
		if json.Unmarshal(b, &v) == nil && v.Bounds == c.Bounds && validateMap(b) == nil {
			return b, nil
		}
	}
	return nil, rows.Err()
}
func (s *Store) discoverRegion(ctx context.Context, p *mapProvider, b [4]float64, emit demObserver) (osmSource, error) {
	catalog := p.catalog
	if catalog == "" {
		catalog = "https://download.geofabrik.de/index-v1.json"
	}
	cache := s.path + ".map-cache"
	if s.path == ":memory:" {
		return osmSource{}, errors.New("regional source caching requires a persistent Command database")
	}
	if e := os.MkdirAll(cache, 0700); e != nil {
		return osmSource{}, e
	}
	index := filepath.Join(cache, "regions.json")
	var data []byte
	if st, e := os.Stat(index); e == nil && time.Since(st.ModTime()) < 24*time.Hour && st.Size() <= mapLimit {
		data, _ = os.ReadFile(index)
		hash, _ := os.ReadFile(index + ".sha256")
		if string(hash) != shaMap(data)+"\n"+catalog {
			data = nil
		}
	}
	if data == nil {
		var e error
		data, e = p.request(ctx, "GET", catalog, "")
		if e != nil {
			return osmSource{}, e
		}
		var check struct {
			Features []regionFeature `json:"features"`
		}
		if json.Unmarshal(data, &check) != nil || len(check.Features) == 0 {
			return osmSource{}, errors.New("invalid regional source index")
		}
		if e = os.WriteFile(index+".tmp", data, 0600); e == nil {
			_ = os.Remove(index) // disposable cache replacement also works on Windows
			if os.Rename(index+".tmp", index) == nil {
				_ = os.WriteFile(index+".sha256", []byte(shaMap(data)+"\n"+catalog), 0600)
			}
		}
	}
	var root struct {
		Features []regionFeature `json:"features"`
	}
	if e := json.Unmarshal(data, &root); e != nil {
		return osmSource{}, e
	}
	candidates := []regionFeature{}
	for _, f := range root.Features {
		if footprintCovers(f.Geometry, b) && f.Properties.URLs["pbf"] != "" {
			candidates = append(candidates, f)
		}
	}
	// Publisher hierarchy prefers the smallest covering footprint, not country labels.
	area := func(f regionFeature) float64 {
		var g struct {
			Coordinates any `json:"coordinates"`
		}
		_ = json.Unmarshal(f.Geometry, &g)
		w, s, e, n := 180.0, 90.0, -180.0, -90.0
		var walk func(any)
		walk = func(v any) {
			a, ok := v.([]any)
			if !ok {
				return
			}
			if len(a) >= 2 {
				if x, ok := a[0].(float64); ok {
					if y, ok := a[1].(float64); ok {
						w = min(w, x)
						s = min(s, y)
						e = max(e, x)
						n = max(n, y)
					}
					return
				}
			}
			for _, x := range a {
				walk(x)
			}
		}
		walk(g.Coordinates)
		return (e - w) * (n - s)
	}
	sort.Slice(candidates, func(i, j int) bool { return area(candidates[i]) < area(candidates[j]) })
	if len(candidates) == 0 {
		return osmSource{}, errors.New("no single supported regional polygon covers this chunk; boundary gap remains")
	}
	// At most one regional dataset attempt per chunk, no shared-provider retry storm.
	candidate := candidates[0]
	address := candidate.Properties.URLs["pbf"]
	u, e := url.Parse(address)
	testURL, _ := url.Parse(catalog)
	if e != nil || u.User != nil || u.RawQuery != "" || !strings.HasSuffix(u.Path, ".osm.pbf") || !((u.Scheme == "https" && u.Host == "download.geofabrik.de") || (u.Host == testURL.Host && u.Scheme == testURL.Scheme && u.Hostname() == "127.0.0.1")) {
		return osmSource{}, errors.New("regional source URL is not an approved publisher")
	}
	p.mu.Lock()
	cooling := time.Now().Before(p.nextRegion)
	p.mu.Unlock()
	if cooling {
		return osmSource{}, errors.New("regional publisher cooldown active; retry missing later")
	}
	downloadCtx, cancelDownload := context.WithTimeout(ctx, 5*time.Minute)
	defer cancelDownload()
	client := *p.client
	client.Timeout = 5 * time.Minute
	var resp *http.Response
	for hop := 0; hop < 3; hop++ {
		req, err := http.NewRequestWithContext(downloadCtx, "GET", address, nil)
		if err != nil {
			return osmSource{}, err
		}
		req.Header.Set("User-Agent", "GNSS-Command/0.1 bounded regional preparation")
		resp, e = mapHTTPClient(downloadCtx, &client).Do(req)
		if e != nil {
			return osmSource{}, e
		}
		if resp.StatusCode != 301 && resp.StatusCode != 302 && resp.StatusCode != 303 && resp.StatusCode != 307 && resp.StatusCode != 308 {
			break
		}
		next, err := regionalRedirect(address, resp.Header.Get("Location"))
		resp.Body.Close()
		if err != nil {
			return osmSource{}, err
		}
		if hop == 2 {
			return osmSource{}, errors.New("regional redirect limit exceeded (2)")
		}
		address = next
	}
	defer resp.Body.Close()
	pause := time.Minute
	if value := resp.Header.Get("Retry-After"); value != "" {
		if duration, e := time.ParseDuration(value + "s"); e == nil {
			pause = max(pause, duration)
		} else if at, e := http.ParseTime(value); e == nil {
			pause = max(pause, time.Until(at))
		} else {
			pause = 24 * time.Hour
		}
	}
	p.mu.Lock()
	p.nextRegion = time.Now().Add(pause)
	p.mu.Unlock()
	if resp.StatusCode != 200 {
		return osmSource{}, fmt.Errorf("regional source HTTP %d; explicit retry required", resp.StatusCode)
	}
	if resp.ContentLength > pbfFileLimit {
		return osmSource{}, errors.New("regional source exceeds 1 GB processing limit")
	}
	if e = mapDiskReserve(s.path, uint64(3*pbfFileLimit)); e != nil {
		return osmSource{}, e
	}
	f, e := os.CreateTemp(cache, "regional-*.tmp")
	if e != nil {
		return osmSource{}, e
	}
	path := f.Name()
	defer os.Remove(path)
	emitDEM(emit, DEMProgress{Stage: "receiving regional PBF; cumulative consent enforced"})
	n, e := io.Copy(f, io.LimitReader(resp.Body, pbfFileLimit+1))
	closeErr := f.Close()
	if e == nil {
		e = closeErr
	}
	if e != nil {
		return osmSource{}, e
	}
	if n > pbfFileLimit {
		return osmSource{}, errors.New("PBF exceeds 1 GB")
	}
	src, e := s.indexPBF(ctx, path, osmSource{Name: candidate.Properties.Name, URL: address, Footprint: candidate.Geometry}, emit)
	return src, e
}
func planExtent(b [4]float64) (mapPlan, error) {
	if b[0] < -180 || b[2] > 180 || b[1] < -mapMercatorLimit || b[3] > mapMercatorLimit || b[0] >= b[2] || b[1] >= b[3] {
		return mapPlan{}, errors.New("unsupported full source extent")
	}
	p := mapPlan{Bounds: b, Chunks: []mapChunk{}, SourceTiles: []string{}, FullExtent: true}
	p.Request = mapCircle{Lat: (b[1] + b[3]) / 2, Lon: (b[0] + b[2]) / 2}
	w, e := int(math.Floor(b[0]/mapChunkStep)), int(math.Ceil(b[2]/mapChunkStep))
	s, n := int(math.Floor(b[1]/mapChunkStep)), int(math.Ceil(b[3]/mapChunkStep))
	if int64(e-w)*int64(n-s) > maxMapChunks {
		return p, errors.New("full declared source extent exceeds 2048 viewport-chunk budget; choose Extract Centre + Radius")
	}
	for y := s; y < n; y++ {
		for x := w; x < e; x++ {
			p.Chunks = append(p.Chunks, mapChunk{fmt.Sprintf("%d_%d", x, y), [4]float64{max(b[0], float64(x)*mapChunkStep), max(b[1], float64(y)*mapChunkStep), min(b[2], float64(x+1)*mapChunkStep), min(b[3], float64(y+1)*mapChunkStep)}})
		}
	}
	return p, nil
}
func (s *Store) mapSourceRequest(w http.ResponseWriter, r *http.Request, p *mapProvider) bool {
	path := r.URL.Path
	products := strings.HasPrefix(path, "/local/maps/") && strings.HasSuffix(path, "/products")
	approve := strings.HasPrefix(path, "/local/maps/") && strings.HasSuffix(path, "/approve-transfer")
	if path != "/local/map-import" && !approve && !products {
		return false
	}
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed", errors.New("use POST"))
		return true
	}
	if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
		failure(w, 403, "forbidden", errors.New("same-origin controls only"))
		return true
	}
	if products {
		var in struct {
			Components map[string]bool `json:"components"`
		}
		e := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in)
		id := strings.TrimSuffix(strings.TrimPrefix(path, "/local/maps/"), "/products")
		for k := range in.Components {
			if k != "hillshade" && k != "contours" && k != "elevation" {
				e = errors.New("unsupported terrain component")
			}
		}
		if e == nil {
			s.mu.Lock()
			var raw []byte
			e = s.db.QueryRow(`SELECT data FROM offline_maps WHERE id=?`, id).Scan(&raw)
			var j mapJob
			if e == nil {
				e = json.Unmarshal(raw, &j)
			}
			if e == nil && len(j.Plan.Chunks) == 0 {
				e = errors.New("use legacy terrain preparation")
			}
			if e == nil {
				if j.Components == nil {
					j.Components = map[string]bool{}
				}
				for k, v := range in.Components {
					if v {
						j.Components[k] = true
					}
				}
				_, e = s.db.Exec(`UPDATE offline_maps SET data=? WHERE id=?`, mustJSON(j), id)
			}
			s.mu.Unlock()
		}
		if e != nil {
			failure(w, 400, "invalid_products", e)
		} else {
			respond(w, 200, map[string]bool{"updated": true})
		}
		return true
	}
	if approve {
		var in struct {
			Allowance int64 `json:"approved_bytes"`
		}
		e := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in)
		id := strings.TrimSuffix(strings.TrimPrefix(path, "/local/maps/"), "/approve-transfer")
		if e == nil {
			e = s.approveMapTransfer(id, in.Allowance)
		}
		if e != nil {
			failure(w, 400, "invalid_approval", e)
		} else {
			respond(w, 200, map[string]bool{"approved": true})
		}
		return true
	}
	if !terrainPreparation.TryLock() {
		failure(w, 409, "map_busy", errors.New("another map preparation is active"))
		return true
	}
	defer terrainPreparation.Unlock()
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(10 * time.Minute))
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(10 * time.Minute))
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Minute)
	defer cancel()
	mode := r.URL.Query().Get("mode")
	if mode != "full" && mode != "crop" {
		failure(w, 400, "invalid_import", errors.New("choose crop or full"))
		return true
	}
	var c mapCircle
	if mode == "crop" {
		_, e := fmt.Sscan(r.URL.Query().Get("lat"), &c.Lat)
		if e == nil {
			_, e = fmt.Sscan(r.URL.Query().Get("lon"), &c.Lon)
		}
		if e == nil {
			_, e = fmt.Sscan(r.URL.Query().Get("radius_m"), &c.Radius)
		}
		if e != nil {
			failure(w, 400, "invalid_area", e)
			return true
		}
	}
	if e := mapDiskReserve(s.path, uint64(3*pbfFileLimit)); e != nil {
		failure(w, 400, "resource_limit", e)
		return true
	}
	f, e := os.CreateTemp("", "gnss-source-*.osm.pbf")
	if e != nil {
		failure(w, 503, "storage_unavailable", e)
		return true
	}
	defer os.Remove(f.Name())
	n, e := io.Copy(f, http.MaxBytesReader(w, r.Body, pbfFileLimit))
	ce := f.Close()
	if e == nil {
		e = ce
	}
	if e != nil {
		failure(w, 400, "invalid_import", e)
		return true
	}
	src, e := s.indexPBF(ctx, f.Name(), osmSource{Name: "Local OSM PBF"}, nil)
	if e != nil {
		failure(w, 400, "invalid_pbf", e)
		return true
	}
	_ = n
	var plan mapPlan
	if mode == "full" {
		plan, e = planExtent(src.Bounds)
	} else {
		plan, e = planCircle(c)
		if e == nil && plan.Unsupported != "" {
			e = errors.New(plan.Unsupported)
		}
	}
	if e == nil {
		for _, chunk := range plan.Chunks {
			if !sourceCovers(src, chunk.Bounds) {
				e = errors.New("requested area extends beyond declared PBF coverage; choose an entirely covered crop")
				break
			}
		}
	}
	if e != nil {
		failure(w, 400, "unsupported_coverage", e)
		return true
	}
	j := mapJob{Type: "FeatureCollection", Features: []any{}, Plan: plan, Components: map[string]bool{}, Attribution: "© OpenStreetMap contributors · ODbL", SourceID: src.ID}
	raw, _ := json.Marshal(j)
	sum := shaMap(raw)
	id := "import-" + sum[:32]
	m, e := s.publishJob(id, src.Name, raw)
	if e != nil {
		failure(w, 503, "storage_unavailable", e)
	} else {
		respond(w, 200, m)
	}
	return true
}

// Reuse an independently validated native grid only for the same planned envelope,
// never for a bbox overlap. Product selection changes metadata, not source samples.
func (s *Store) reuseTerrain(ctx context.Context, id string, c mapChunk, components map[string]bool) ([]byte, chunkEvidence, error) {
	db := s.viewDB
	if db == nil {
		db = s.db
	}
	rows, err := db.QueryContext(ctx, `SELECT c.terrain,c.terrain_evidence,m.data FROM offline_map_chunks c JOIN offline_maps m ON m.id=c.map_id WHERE c.map_id<>? AND c.chunk_key=? AND c.terrain IS NOT NULL AND json_extract(c.terrain_evidence,'$.status')='complete' LIMIT 64`, id, c.Key)
	if err != nil {
		return nil, chunkEvidence{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var b, raw []byte
		var text string
		if err = rows.Scan(&b, &text, &raw); err != nil {
			return nil, chunkEvidence{}, err
		}
		var j mapJob
		if json.Unmarshal(raw, &j) != nil {
			continue
		}
		matched := false
		for _, v := range j.Plan.Chunks {
			if v == c {
				matched = true
				break
			}
		}
		if !matched {
			continue
		}
		g, e := decodeTerrain(b)
		if e != nil {
			continue
		}
		var evidence chunkEvidence
		if json.Unmarshal([]byte(text), &evidence) != nil {
			continue
		}
		g.Components = components
		b, e = encodeTerrain(g)
		if e != nil {
			return nil, evidence, e
		}
		g.Samples = nil
		evidence.Metadata = &g
		evidence.Bytes = len(b)
		evidence.SourceBytes = 0
		evidence.Reused = true
		return b, evidence, nil
	}
	return nil, chunkEvidence{}, rows.Err()
}

// Authenticated publisher redirects may select a dated snapshot or its download
// mirror, not a different region, arbitrary host, query credential or local service.
func regionalRedirect(from, location string) (string, error) {
	old, e := url.Parse(from)
	if e != nil {
		return "", errors.New("invalid regional redirect origin")
	}
	target, e := url.Parse(location)
	if e != nil {
		return "", errors.New("invalid regional redirect")
	}
	target = old.ResolveReference(target)
	approved := target.Scheme == "https" && target.Port() == "" && (target.Hostname() == "download.geofabrik.de" || strings.HasPrefix(target.Hostname(), "download-") && strings.HasSuffix(target.Hostname(), ".geofabrik.de"))
	if old.Hostname() == "127.0.0.1" {
		approved = target.Host == old.Host && target.Scheme == old.Scheme
	}
	if !approved || target.User != nil || target.RawQuery != "" || target.Fragment != "" || path.Dir(target.Path) != path.Dir(old.Path) {
		return "", errors.New("regional redirect outside approved publisher/dataset path")
	}
	before, after := path.Base(old.Path), path.Base(target.Path)
	if before != after {
		stem := strings.TrimSuffix(before, "-latest.osm.pbf")
		date := strings.TrimSuffix(strings.TrimPrefix(after, stem+"-"), ".osm.pbf")
		digits := len(date) == 6 || len(date) == 8
		for _, c := range date {
			digits = digits && c >= '0' && c <= '9'
		}
		if stem == before || !strings.HasPrefix(after, stem+"-") || !strings.HasSuffix(after, ".osm.pbf") || !digits {
			return "", errors.New("regional redirect changes dataset identity")
		}
	}
	return target.String(), nil
}

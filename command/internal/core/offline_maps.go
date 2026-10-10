package core

// Offline cartography is presentation-only and shares the existing local database.
// No observation, projection, recording or sender path uses these tables/APIs.
import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"mime"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const mapLimit = 20 * 1024 * 1024

type savedMap struct {
	ID      string `json:"id"`
	Source  string `json:"source"`
	Created string `json:"created"`
	Bytes   int    `json:"bytes"`
}
type mapProvider struct {
	client               *http.Client
	search, overpass     string
	dem                  string
	mu                   sync.Mutex
	nextSearch, nextArea time.Time
}

func newMapProvider() *mapProvider {
	// Finish before the existing local HTTP server's 30-second write deadline.
	return &mapProvider{client: &http.Client{Timeout: 25 * time.Second}, search: "https://nominatim.openstreetmap.org/search", overpass: "https://overpass-api.de/api/interpreter", dem: "https://elevation-tiles-prod.s3.amazonaws.com/skadi"}
}
func (p *mapProvider) reserve(search bool) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	next, interval := &p.nextArea, 10*time.Second
	if search {
		next, interval = &p.nextSearch, time.Second
	}
	if time.Now().Before(*next) {
		return errors.New("provider cooldown active; wait before another request")
	}
	*next = time.Now().Add(interval)
	return nil
}
func (p *mapProvider) request(ctx context.Context, method, address, body string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, method, address, strings.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "GNSS-Command/0.1 (bounded offline field-map preparation; https://github.com/suraj-singh12/gnss-android-tracking)")
	req.Header.Set("Accept", "application/json")
	if method == "POST" {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("map provider request failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("map provider returned HTTP %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, mapLimit+1))
	if len(b) > mapLimit {
		return nil, errors.New("provider data exceeds 20 MB")
	}
	return b, err
}
func (s *Store) RecentEvents() ([]Evidence, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT ordinal,data FROM field_evidence ORDER BY ordinal DESC LIMIT 500`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []Evidence{}
	for rows.Next() {
		var e Evidence
		var b string
		if err = rows.Scan(&e.Ordinal, &b); err != nil {
			return nil, err
		}
		n := e.Ordinal
		if err = json.Unmarshal([]byte(b), &e); err != nil {
			return nil, err
		}
		e.Ordinal = n
		result = append(result, e)
	}
	return result, rows.Err()
}
func (s *Store) listMaps() ([]savedMap, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.Query(`SELECT id,source,created,length(data) FROM offline_maps ORDER BY created DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []savedMap{}
	for rows.Next() {
		var m savedMap
		if err = rows.Scan(&m.ID, &m.Source, &m.Created, &m.Bytes); err != nil {
			return nil, err
		}
		result = append(result, m)
	}
	return result, rows.Err()
}
func validateMap(b []byte) error {
	if len(b) > mapLimit {
		return errors.New("map exceeds 20 MB")
	}
	var root map[string]any
	if err := json.Unmarshal(b, &root); err != nil {
		return err
	}
	count := 0
	min, max := 180.0, -180.0
	var geometry func(map[string]any, int) error
	var coordinates func(any, int) error
	coordinates = func(v any, depth int) error {
		if depth > 8 {
			return errors.New("coordinate nesting too deep")
		}
		a, ok := v.([]any)
		if !ok || len(a) == 0 {
			return errors.New("invalid coordinates")
		}
		if x, ok := a[0].(float64); ok {
			if len(a) < 2 {
				return errors.New("coordinate needs longitude/latitude")
			}
			y, ok := a[1].(float64)
			if !ok || math.Abs(x) > 180 || math.Abs(y) > 85.05112878 {
				return errors.New("coordinate outside WGS84/Web Mercator coverage")
			}
			count++
			if count > 100000 {
				return errors.New("map exceeds 100,000 coordinates")
			}
			min = math.Min(min, x)
			max = math.Max(max, x)
			return nil
		}
		for _, c := range a {
			if err := coordinates(c, depth+1); err != nil {
				return err
			}
		}
		return nil
	}
	geometry = func(g map[string]any, depth int) error {
		if depth > 16 || g["crs"] != nil {
			return errors.New("unsupported nesting or CRS")
		}
		switch g["type"] {
		case "FeatureCollection":
			a, ok := g["features"].([]any)
			if !ok {
				return errors.New("invalid features")
			}
			for _, v := range a {
				m, ok := v.(map[string]any)
				if !ok || m["type"] != "Feature" {
					return errors.New("invalid feature")
				}
				if err := geometry(m, depth+1); err != nil {
					return err
				}
			}
		case "Feature":
			if g["geometry"] == nil {
				return nil
			}
			m, ok := g["geometry"].(map[string]any)
			if !ok {
				return errors.New("invalid geometry")
			}
			return geometry(m, depth+1)
		case "GeometryCollection":
			a, ok := g["geometries"].([]any)
			if !ok {
				return errors.New("invalid geometries")
			}
			for _, v := range a {
				m, ok := v.(map[string]any)
				if !ok {
					return errors.New("invalid geometry")
				}
				if err := geometry(m, depth+1); err != nil {
					return err
				}
			}
		case "Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon":
			// Check geometry cardinality and ring closure before accepting it into
			// the local library; the browser independently enforces the same limits.
			var structure func(any, int, bool) error
			structure = func(v any, nesting int, ring bool) error {
				a, ok := v.([]any)
				if !ok || len(a) == 0 {
					return errors.New("empty or invalid geometry")
				}
				if nesting > 0 {
					for _, c := range a {
						if err := structure(c, nesting-1, ring); err != nil {
							return err
						}
					}
					return nil
				}
				minimum := 2
				if ring {
					minimum = 4
				}
				if len(a) < minimum {
					return errors.New("line/ring has too few coordinates")
				}
				for _, c := range a {
					point, ok := c.([]any)
					if !ok || len(point) < 2 {
						return errors.New("invalid point")
					}
					if _, ok = point[0].(float64); !ok {
						return errors.New("invalid point")
					}
					if _, ok = point[1].(float64); !ok {
						return errors.New("invalid point")
					}
				}
				if ring {
					first := a[0].([]any)
					last := a[len(a)-1].([]any)
					if first[0] != last[0] || first[1] != last[1] {
						return errors.New("polygon rings must be closed")
					}
				}
				return nil
			}
			switch g["type"] {
			case "Point":
				a, ok := g["coordinates"].([]any)
				if !ok || len(a) < 2 {
					return errors.New("invalid point")
				}
				if _, ok = a[0].(float64); !ok {
					return errors.New("invalid point")
				}
			case "LineString":
				if err := structure(g["coordinates"], 0, false); err != nil {
					return err
				}
			case "MultiLineString":
				if err := structure(g["coordinates"], 1, false); err != nil {
					return err
				}
			case "Polygon":
				if err := structure(g["coordinates"], 1, true); err != nil {
					return err
				}
			case "MultiPolygon":
				if err := structure(g["coordinates"], 2, true); err != nil {
					return err
				}
			case "MultiPoint":
				a, ok := g["coordinates"].([]any)
				if !ok {
					return errors.New("invalid multipoint")
				}
				for _, c := range a {
					point, ok := c.([]any)
					if !ok || len(point) < 2 {
						return errors.New("invalid point")
					}
					if _, ok = point[0].(float64); !ok {
						return errors.New("invalid point")
					}
				}
			}
			return coordinates(g["coordinates"], 0)
		default:
			return errors.New("unsupported GeoJSON geometry")
		}
		return nil
	}
	if err := geometry(root, 0); err != nil {
		return err
	}
	if count == 0 {
		return errors.New("map has no coordinates")
	}
	if max-min > 180 {
		return errors.New("split antimeridian-spanning maps before import")
	}
	return nil
}
func (s *Store) saveMap(source string, b []byte) (savedMap, error) {
	if source == "" || len(source) > 200 {
		return savedMap{}, errors.New("source name must contain 1–200 bytes")
	}
	if err := validateMap(b); err != nil {
		return savedMap{}, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return savedMap{}, err
	}
	defer tx.Rollback()
	var count, total int
	if err = tx.QueryRow(`SELECT count(*),coalesce(sum(length(data)),0) FROM offline_maps`).Scan(&count, &total); err != nil {
		return savedMap{}, err
	}
	if count >= 64 || total+len(b) > 256*1024*1024 {
		return savedMap{}, errors.New("map library full (64 maps / 256 MB); no existing map was removed")
	}
	m := savedMap{ID: id(), Source: source, Created: s.Now().UTC().Format(wireTime), Bytes: len(b)}
	if _, err = tx.Exec(`INSERT INTO offline_maps(id,source,data,created) VALUES(?,?,?,?)`, m.ID, m.Source, b, m.Created); err != nil {
		return savedMap{}, err
	}
	return m, tx.Commit()
}
func (s *Store) mapRequest(w http.ResponseWriter, r *http.Request, p *mapProvider) bool {
	if s.terrainRequest(w, r, p) {
		return true
	}
	path := r.URL.Path
	if path != "/local/maps" && !strings.HasPrefix(path, "/local/maps/") && path != "/local/map-search" && path != "/local/map-preview" {
		return false
	}
	if r.Method == "GET" && path == "/local/maps" {
		m, err := s.listMaps()
		if err != nil {
			failure(w, 503, "storage_unavailable", err)
		} else {
			respond(w, 200, m)
		}
		return true
	}
	if r.Method == "GET" && strings.HasPrefix(path, "/local/maps/") {
		s.mu.Lock()
		var b []byte
		err := s.db.QueryRow(`SELECT data FROM offline_maps WHERE id=?`, strings.TrimPrefix(path, "/local/maps/")).Scan(&b)
		s.mu.Unlock()
		if err != nil {
			failure(w, 404, "map_unavailable", errors.New("saved map not found"))
		} else {
			w.Header().Set("Content-Type", "application/geo+json")
			w.Header().Set("Cache-Control", "no-store")
			_, _ = w.Write(b)
		}
		return true
	}
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed", errors.New("use GET or POST"))
		return true
	}
	if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
		failure(w, 403, "forbidden", errors.New("same-origin controls only"))
		return true
	}
	media, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || media != "application/json" {
		failure(w, 415, "unsupported_media_type", errors.New("use application/json"))
		return true
	}
	limit := int64(65536)
	if path == "/local/maps" {
		limit = mapLimit + 65536
	}
	b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, limit))
	if err != nil {
		failure(w, 400, "invalid_map", err)
		return true
	}
	if path == "/local/maps" {
		var in struct {
			Source string          `json:"source"`
			Data   json.RawMessage `json:"data"`
		}
		if err = json.Unmarshal(b, &in); err == nil {
			var m savedMap
			m, err = s.saveMap(in.Source, in.Data)
			if err == nil {
				respond(w, 200, m)
				return true
			}
		}
		failure(w, 400, "invalid_map", err)
		return true
	}
	if path == "/local/map-search" {
		var in struct {
			Text string `json:"text"`
		}
		err = json.Unmarshal(b, &in)
		if err == nil && (len(strings.TrimSpace(in.Text)) < 2 || len(in.Text) > 120) {
			err = errors.New("place search requires 2–120 bytes")
		}
		if err == nil {
			err = p.reserve(true)
		}
		if err == nil {
			var data []byte
			data, err = p.request(r.Context(), "GET", p.search+"?"+url.Values{"q": {in.Text}, "format": {"jsonv2"}, "limit": {"5"}}.Encode(), "")
			if err == nil {
				var results any
				err = json.Unmarshal(data, &results)
				if err == nil {
					respond(w, 200, results)
					return true
				}
			}
		}
		failure(w, 502, "map_search_unavailable", err)
		return true
	}
	var in struct {
		Lat    *float64 `json:"lat"`
		Lon    *float64 `json:"lon"`
		Width  float64  `json:"width"`
		Height float64  `json:"height"`
	}
	err = json.Unmarshal(b, &in)
	if err == nil && (in.Lat == nil || in.Lon == nil || math.Abs(*in.Lat) > 85 || math.Abs(*in.Lon) > 180 || in.Width < 100 || in.Width > 2000 || in.Height < 100 || in.Height > 2000) {
		err = errors.New("choose 100–2000 m dimensions and a valid WGS84 center (±85°)")
	}
	var data []byte
	if err == nil {
		data, err = p.area(r.Context(), *in.Lat, *in.Lon, in.Width, in.Height)
	}
	if err != nil {
		failure(w, 502, "map_preparation_unavailable", err)
	} else {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(data)
	}
	return true
}
func (p *mapProvider) area(ctx context.Context, lat, lon, width, height float64) ([]byte, error) {
	dy := height / 2 / 111320
	dx := width / 2 / (111320 * math.Cos(lat*math.Pi/180))
	if lon-dx < -180 || lon+dx > 180 || math.Abs(lat-dy) > 85.05112878 || math.Abs(lat+dy) > 85.05112878 {
		return nil, errors.New("area crosses unsupported map coverage")
	}
	if err := p.reserve(false); err != nil {
		return nil, err
	}
	bbox := fmt.Sprintf("%.7f,%.7f,%.7f,%.7f", lat-dy, lon-dx, lat+dy, lon+dx)
	query := fmt.Sprintf(`[out:json][timeout:20][maxsize:20000000];(way["highway"](%s);way["building"](%s);way["waterway"](%s);way["natural"](%s);way["landuse"](%s););out geom;`, bbox, bbox, bbox, bbox, bbox)
	b, err := p.request(ctx, "POST", p.overpass, url.Values{"data": {query}}.Encode())
	if err != nil {
		return nil, err
	}
	var result struct {
		Remark   string `json:"remark"`
		Elements []struct {
			ID       int64             `json:"id"`
			Tags     map[string]string `json:"tags"`
			Geometry []struct {
				Lat float64 `json:"lat"`
				Lon float64 `json:"lon"`
			} `json:"geometry"`
		} `json:"elements"`
	}
	if err = json.Unmarshal(b, &result); err != nil {
		return nil, err
	}
	if result.Remark != "" {
		return nil, errors.New("provider returned incomplete data: " + result.Remark)
	}
	features := []any{}
	for _, e := range result.Elements {
		if len(e.Geometry) < 2 {
			continue
		}
		coords := [][2]float64{}
		for _, c := range e.Geometry {
			coords = append(coords, [2]float64{c.Lon, c.Lat})
		}
		typ := "LineString"
		var geometry any = coords
		if len(coords) >= 4 && coords[0] == coords[len(coords)-1] && e.Tags["area"] != "no" && (e.Tags["building"] != "" || e.Tags["landuse"] != "" || e.Tags["natural"] != "") {
			typ = "Polygon"
			geometry = [][][2]float64{coords}
		}
		features = append(features, map[string]any{"type": "Feature", "id": fmt.Sprintf("way/%d", e.ID), "properties": e.Tags, "geometry": map[string]any{"type": typ, "coordinates": geometry}})
	}
	if len(features) == 0 {
		return nil, errors.New("no supported OSM ways in this area; try a different center or import GeoJSON")
	}
	data, err := json.Marshal(map[string]any{"type": "FeatureCollection", "features": features, "attribution": "© OpenStreetMap contributors · ODbL", "source": "OpenStreetMap via Overpass (ways only)", "prepared_at": time.Now().UTC().Format(wireTime), "requested_bounds": [4]float64{lon - dx, lat - dy, lon + dx, lat + dy}})
	if err == nil {
		err = validateMap(data)
	}
	return data, err
}

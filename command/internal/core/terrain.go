package core

// Bounded local DEM preparation. This never participates in GNSS or recording.
import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"math"
	"mime"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

const terrainLimit = 1024 * 1024

var terrainPreparation sync.Mutex // at most one bounded upload/decoder at a time
var hgtName = regexp.MustCompile(`^([NS])(\d{2})([EW])(\d{3})\.hgt$`)

type terrainGrid struct {
	Bounds     [4]float64      `json:"bounds"` // west,south,east,north sample centres
	Columns    int             `json:"columns"`
	Rows       int             `json:"rows"`
	ArcSeconds float64         `json:"arc_seconds"`
	Source     string          `json:"source"`
	Datum      string          `json:"vertical_datum"`
	Components map[string]bool `json:"components"`
	Samples    []*float64      `json:"samples,omitempty"` // null is unavailable; persisted as binary int16
	Provenance *demProvenance  `json:"provenance,omitempty"`
}

func (g terrainGrid) validate() error {
	b := g.Bounds
	if g.Columns < 2 || g.Rows < 2 || g.Columns > 257 || g.Rows > 257 || len(g.Samples) != g.Columns*g.Rows || b[0] >= b[2] || b[1] >= b[3] || math.Abs(b[0]) > 180 || math.Abs(b[2]) > 180 || math.Abs(b[1]) > 85 || math.Abs(b[3]) > 85 || g.ArcSeconds < 1 || g.ArcSeconds > 3 || len(g.Source) > 200 || g.Source == "" || g.Datum != "EGM96" {
		return errors.New("invalid bounded WGS84/EGM96 terrain grid")
	}
	for _, v := range b {
		if math.IsNaN(v) || math.IsInf(v, 0) {
			return errors.New("invalid terrain bounds")
		}
	}
	if math.Abs((b[2]-b[0])*3600/float64(g.Columns-1)-g.ArcSeconds) > 1e-6 || math.Abs((b[3]-b[1])*3600/float64(g.Rows-1)-g.ArcSeconds) > 1e-6 {
		return errors.New("terrain spacing does not match metadata")
	}
	selected, valid := false, false
	for k, v := range g.Components {
		if k != "hillshade" && k != "contours" && k != "elevation" {
			return errors.New("unknown terrain component")
		}
		selected = selected || v
	}
	for _, v := range g.Samples {
		if v != nil {
			if math.IsNaN(*v) || math.IsInf(*v, 0) || *v < -12000 || *v > 9000 || math.Trunc(*v) != *v {
				return errors.New("invalid integer metre terrain sample")
			}
			valid = true
		}
	}
	if !selected || !valid {
		return errors.New("choose a component and non-void DEM coverage")
	}
	return nil
}

// GTERR001: uint32 JSON metadata length, metadata (without samples), int16 BE samples.
func encodeTerrain(g terrainGrid) ([]byte, error) {
	if err := g.validate(); err != nil {
		return nil, err
	}
	values := g.Samples
	g.Samples = nil
	m, err := json.Marshal(g)
	if err != nil || len(m) > 8192 {
		return nil, errors.New("terrain metadata too large")
	}
	var out bytes.Buffer
	out.WriteString("GTERR001")
	binary.Write(&out, binary.BigEndian, uint32(len(m)))
	out.Write(m)
	for _, v := range values {
		n := int16(-32768)
		if v != nil {
			n = int16(*v)
		}
		binary.Write(&out, binary.BigEndian, n)
	}
	return out.Bytes(), nil
}
func decodeTerrain(b []byte) (terrainGrid, error) {
	var g terrainGrid
	if len(b) < 12 || len(b) > terrainLimit || string(b[:8]) != "GTERR001" {
		return g, errors.New("unsupported or oversized terrain package")
	}
	n := int(binary.BigEndian.Uint32(b[8:12]))
	if n > 8192 || 12+n > len(b) {
		return g, errors.New("invalid terrain metadata")
	}
	if err := json.Unmarshal(b[12:12+n], &g); err != nil {
		return g, err
	}
	if g.Rows < 2 || g.Rows > 257 || g.Columns < 2 || g.Columns > 257 || len(b) != 12+n+g.Rows*g.Columns*2 {
		return g, errors.New("invalid terrain dimensions")
	}
	g.Samples = make([]*float64, g.Rows*g.Columns)
	for i := range g.Samples {
		v := int16(binary.BigEndian.Uint16(b[12+n+i*2:]))
		if v != -32768 {
			f := float64(v)
			g.Samples[i] = &f
		}
	}
	return g, g.validate()
}

func importHGT(name string, b []byte, bounds [4]float64, components map[string]bool) (terrainGrid, error) {
	var g terrainGrid
	m := hgtName.FindStringSubmatch(name)
	if m == nil {
		return g, errors.New("use an uncompressed SRTM tile named N28E077.hgt (SW corner)")
	}
	side := 0
	switch len(b) {
	case 1201 * 1201 * 2:
		side = 1201
	case 3601 * 3601 * 2:
		side = 3601
	default:
		return g, errors.New("HGT must be square 1201 or 3601 int16 big-endian samples")
	}
	lat, _ := strconv.Atoi(m[2])
	lon, _ := strconv.Atoi(m[4])
	if m[1] == "S" {
		lat = -lat
	}
	if m[3] == "W" {
		lon = -lon
	}
	if lat < -56 || lat >= 60 || lon < -180 || lon >= 180 {
		return g, errors.New("tile outside supported SRTM coverage")
	}
	if bounds[0] < float64(lon) || bounds[2] > float64(lon+1) || bounds[1] < float64(lat) || bounds[3] > float64(lat+1) {
		return g, errors.New("one HGT tile must cover the complete selected map area; cross-tile mosaics unsupported")
	}
	step := 1 / float64(side-1)
	west := int(math.Floor((bounds[0] - float64(lon)) / step))
	east := int(math.Ceil((bounds[2] - float64(lon)) / step))
	north := int(math.Floor((float64(lat+1) - bounds[3]) / step))
	south := int(math.Ceil((float64(lat+1) - bounds[1]) / step))
	west = max(0, west-1)
	east = min(side-1, east+1)
	north = max(0, north-1)
	south = min(side-1, south+1)
	g = terrainGrid{Bounds: [4]float64{float64(lon) + float64(west)*step, float64(lat+1) - float64(south)*step, float64(lon) + float64(east)*step, float64(lat+1) - float64(north)*step}, Columns: east - west + 1, Rows: south - north + 1, ArcSeconds: step * 3600, Source: "Operator-supplied SRTM · " + name, Datum: "EGM96", Components: components}
	if g.Columns > 257 || g.Rows > 257 || g.Columns < 2 || g.Rows < 2 {
		return g, errors.New("DEM subset exceeds 257×257; choose a smaller map")
	}
	g.Samples = make([]*float64, g.Columns*g.Rows)
	for y := 0; y < g.Rows; y++ {
		for x := 0; x < g.Columns; x++ {
			v := int16(binary.BigEndian.Uint16(b[((north+y)*side+west+x)*2:]))
			if v != -32768 {
				f := float64(v)
				g.Samples[y*g.Columns+x] = &f
			}
		}
	}
	return g, g.validate()
}

func mapAreaBounds(b []byte) ([4]float64, error) {
	var root map[string]any
	if err := json.Unmarshal(b, &root); err != nil {
		return [4]float64{}, err
	}
	result := [4]float64{180, 85, -180, -85}
	if a, ok := root["requested_bounds"].([]any); ok && len(a) == 4 {
		for i, v := range a {
			f, ok := v.(float64)
			if !ok {
				return result, errors.New("invalid requested bounds")
			}
			result[i] = f
		}
	} else {
		var walk func(any)
		walk = func(v any) {
			switch a := v.(type) {
			case map[string]any:
				for k, v := range a {
					if k == "coordinates" || k == "features" || k == "geometry" || k == "geometries" {
						walk(v)
					}
				}
			case []any:
				if len(a) >= 2 {
					x, ok := a[0].(float64)
					y, okY := a[1].(float64)
					if ok && okY {
						result[0] = math.Min(result[0], x)
						result[1] = math.Min(result[1], y)
						result[2] = math.Max(result[2], x)
						result[3] = math.Max(result[3], y)
						return
					}
				}
				for _, v := range a {
					walk(v)
				}
			}
		}
		walk(root)
	}
	if result[0] >= result[2] || result[1] >= result[3] || result[2]-result[0] > 180 || math.Abs(result[0]) > 180 || math.Abs(result[2]) > 180 || math.Abs(result[1]) > 85 || math.Abs(result[3]) > 85 {
		return result, errors.New("terrain needs a nonempty bounded geographic area")
	}
	return result, nil
}

func (s *Store) terrainRequest(w http.ResponseWriter, r *http.Request, p *mapProvider) bool {
	path := strings.TrimPrefix(r.URL.Path, "/local/maps/")
	parts := strings.Split(path, "/")
	if !strings.HasPrefix(r.URL.Path, "/local/maps/") || len(parts) != 2 || (parts[1] != "terrain" && parts[1] != "terrain-file" && parts[1] != "terrain-download" && parts[1] != "download-report" && parts[1] != "delete") {
		return false
	}
	mapID := parts[0]
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == "GET" && parts[1] == "download-report" {
		s.downloadReport(w, mapID)
		return true
	}
	if parts[1] == "download-report" {
		failure(w, 405, "method_not_allowed", errors.New("use GET for download reports"))
		return true
	}
	if r.Method == "GET" && (parts[1] == "terrain" || parts[1] == "terrain-file") {
		s.mu.Lock()
		var b []byte
		err := s.db.QueryRow(`SELECT data FROM offline_terrain WHERE map_id=?`, mapID).Scan(&b)
		s.mu.Unlock()
		if err != nil {
			failure(w, 404, "terrain_unavailable", errors.New("terrain not prepared for this saved map"))
			return true
		}
		if parts[1] == "terrain-file" {
			w.Header().Set("Content-Type", "application/octet-stream")
			w.Header().Set("Content-Disposition", `attachment; filename="offline-map.gterrain"`)
			w.Write(b)
		} else {
			g, err := decodeTerrain(b)
			if err != nil {
				failure(w, 503, "invalid_terrain", err)
			} else {
				respond(w, 200, g)
			}
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
	mediaType, _, mediaErr := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if mediaErr != nil || mediaType != "application/json" {
		failure(w, 415, "unsupported_media_type", errors.New("use application/json"))
		return true
	}
	if !terrainPreparation.TryLock() {
		failure(w, 409, "terrain_busy", errors.New("terrain preparation busy; retry when it completes"))
		return true
	}
	defer terrainPreparation.Unlock()
	if parts[1] == "delete" {
		s.mu.Lock()
		defer s.mu.Unlock()
		tx, err := s.db.Begin()
		if err == nil {
			defer tx.Rollback()
			_, err = tx.Exec(`DELETE FROM offline_terrain WHERE map_id=?`, mapID)
			if err == nil {
				_, err = tx.Exec(`DELETE FROM offline_maps WHERE id=?`, mapID)
			}
			if err == nil {
				err = tx.Commit()
			}
		}
		if err != nil {
			failure(w, 503, "storage_unavailable", err)
		} else {
			respond(w, 200, map[string]bool{"deleted": true})
		}
		return true
	}
	var in struct {
		Name       string          `json:"name"`
		Data       []byte          `json:"data"`
		Components map[string]bool `json:"components"`
	}
	err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 36*1024*1024)).Decode(&in)
	if err != nil {
		failure(w, 400, "invalid_terrain", err)
		return true
	}
	s.mu.Lock()
	var mapBytes []byte
	err = s.db.QueryRow(`SELECT data FROM offline_maps WHERE id=?`, mapID).Scan(&mapBytes)
	s.mu.Unlock()
	if err != nil {
		failure(w, 404, "map_unavailable", errors.New("save the vector map first"))
		return true
	}
	bounds, err := mapAreaBounds(mapBytes)
	if err == nil && parts[1] == "terrain-download" {
		s.downloadTerrainResponse(w, r, p, mapID, bounds, in.Components)
		return true
	}
	var g terrainGrid
	if err == nil {
		if parts[1] == "terrain-download" {
			g, err = p.acquireDEM(r.Context(), bounds, in.Components)
		} else if strings.HasSuffix(in.Name, ".gterrain") {
			g, err = decodeTerrain(in.Data)
			if err == nil && (g.Bounds[0] > bounds[0] || g.Bounds[1] > bounds[1] || g.Bounds[2] < bounds[2] || g.Bounds[3] < bounds[3]) {
				err = errors.New("terrain package does not cover selected map")
			}
		} else {
			g, err = importHGT(in.Name, in.Data, bounds, in.Components)
		}
	}
	var encoded []byte
	if err == nil {
		encoded, err = encodeTerrain(g)
	}
	if err == nil {
		s.mu.Lock()
		var total int
		err = s.db.QueryRow(`SELECT coalesce(sum(length(data)),0) FROM offline_terrain WHERE map_id<>?`, mapID).Scan(&total)
		if err == nil && total+len(encoded) > 16*1024*1024 {
			err = errors.New("terrain library exceeds 16 MB")
		}
		if err == nil {
			_, err = s.db.Exec(`INSERT INTO offline_terrain(map_id,data) VALUES(?,?) ON CONFLICT(map_id) DO UPDATE SET data=excluded.data`, mapID, encoded)
		}
		s.mu.Unlock()
	}
	if err != nil {
		failure(w, 400, "invalid_terrain", err)
	} else {
		respond(w, 200, g)
	}
	return true
}

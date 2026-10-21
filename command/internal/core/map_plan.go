package core

// Requested geometry is ellipsoidal WGS84, independent of the display projection.
import (
	"errors"
	"fmt"
	"math"
	"sort"

	"github.com/pymaxion/geographiclib-go/geodesic"
)

const mapChunkStep = 240.0 / 3600 // aligned native nodes; each crop plus halo fits GTERR001
const maxMapChunks = 2048         // <= 1 MiB plan metadata and 256 MiB encoded DEM, not a radius cap

type mapCircle struct {
	Lat    float64 `json:"lat"`
	Lon    float64 `json:"lon"`
	Radius float64 `json:"radius_m"`
}
type mapChunk struct {
	Key    string     `json:"key"`
	Bounds [4]float64 `json:"bounds"`
}
type mapPlan struct {
	AreaM2              float64      `json:"approximate_ellipsoidal_area_m2"`
	Request             mapCircle    `json:"request"`
	Circle              [][2]float64 `json:"circle"` // lon,lat; render as geodesic polygon, not screen circle
	Bounds              [4]float64   `json:"bounds"` // unwrapped longitudes relative to centre
	Chunks              []mapChunk   `json:"chunks"`
	SourceTiles         []string     `json:"source_tiles"`
	DEMUnsupported      string       `json:"dem_unsupported,omitempty"`
	Unsupported         string       `json:"unsupported,omitempty"`
	SourceRequests      int          `json:"maximum_source_requests_before_retries"`
	EstimatedDEMBytes   int64        `json:"maximum_compressed_dem_bytes"`
	MaximumVectorBytes  int64        `json:"maximum_vector_bytes"`
	MaximumTerrainBytes int64        `json:"maximum_terrain_bytes"`
	Confirmation        bool         `json:"requires_confirmation"`
}

func wrapLongitude(x float64) float64           { return x - 360*math.Floor((x+180)/360) }
func unwrapLongitude(x, center float64) float64 { return center + wrapLongitude(x-center) }
func planCircle(c mapCircle) (mapPlan, error) {
	p := mapPlan{Request: c, Chunks: []mapChunk{}, SourceTiles: []string{}}
	if math.IsNaN(c.Lat) || math.IsNaN(c.Lon) || math.IsNaN(c.Radius) || math.IsInf(c.Lat, 0) || math.IsInf(c.Lon, 0) || math.IsInf(c.Radius, 0) || math.Abs(c.Lat) > 90 || math.Abs(c.Lon) > 180 || c.Radius <= 0 {
		return p, errors.New("enter a valid WGS84 centre and a finite positive radius in metres")
	}
	// A circle enclosing a pole exceeds this renderer's actual latitude coverage.
	pole := geodesic.WGS84.Inverse(c.Lat, c.Lon, math.Copysign(90, c.Lat), c.Lon).S12
	if c.Radius >= pole {
		p.Unsupported = "requested circle reaches a pole: existing Web Mercator renderer supports only ±85.05112878°"
		return p, nil
	}
	point := func(bearing float64) [2]float64 {
		d := geodesic.WGS84.Direct(c.Lat, c.Lon, bearing, c.Radius)
		return [2]float64{unwrapLongitude(d.Lon2, c.Lon), d.Lat2}
	}
	p.Bounds = [4]float64{math.Inf(1), math.Inf(1), math.Inf(-1), math.Inf(-1)}
	for i := 0; i <= 720; i++ {
		a := point(float64(i) * 0.5)
		p.Circle = append(p.Circle, a)
		p.Bounds[0] = math.Min(p.Bounds[0], a[0])
		p.Bounds[1] = math.Min(p.Bounds[1], a[1])
		p.Bounds[2] = math.Max(p.Bounds[2], a[0])
		p.Bounds[3] = math.Max(p.Bounds[3], a[1])
	}
	polygon := geodesic.NewPolygonArea(geodesic.WGS84, false)
	for _, v := range p.Circle {
		polygon.AddPoint(v[1], v[0])
	}
	p.AreaM2 = math.Abs(polygon.Compute(false, true).Area)
	// Find longitude extrema rather than underestimating the envelope between vertices.
	for _, r := range [][2]float64{{0, 180}, {180, 360}} {
		lo, hi := r[0], r[1]
		sign := 1.0
		if lo == 180 {
			sign = -1
		}
		for i := 0; i < 70; i++ {
			a, b := lo+(hi-lo)/3, hi-(hi-lo)/3
			if sign*point(a)[0] < sign*point(b)[0] {
				lo = a
			} else {
				hi = b
			}
		}
		x := point((lo + hi) / 2)[0]
		p.Bounds[0] = math.Min(p.Bounds[0], x)
		p.Bounds[2] = math.Max(p.Bounds[2], x)
	}
	if p.Bounds[0] >= p.Bounds[2] || p.Bounds[1] >= p.Bounds[3] {
		p.Unsupported = "requested radius is below floating-point geographic coordinate resolution at this centre"
		return p, nil
	}
	if p.Bounds[1] < -85.05112878 || p.Bounds[3] > 85.05112878 {
		p.Unsupported = "requested circle exceeds Web Mercator latitude coverage (±85.05112878°)"
		return p, nil
	}
	if p.Bounds[1] < -56 || p.Bounds[3] > 60 {
		p.DEMUnsupported = "Skadi adapter has verified source support only within 56°S–60°N; unsupported chunks remain gaps"
	}
	west, east := int(math.Floor(p.Bounds[0]/mapChunkStep)), int(math.Floor(p.Bounds[2]/mapChunkStep))
	south, north := int(math.Floor(p.Bounds[1]/mapChunkStep)), int(math.Floor(p.Bounds[3]/mapChunkStep))
	count := int64(east-west+1) * int64(north-south+1)
	p.MaximumTerrainBytes = count * 132 * 1024
	p.MaximumVectorBytes = count * mapLimit
	if count > maxMapChunks {
		p.Unsupported = fmt.Sprintf("requested envelope requires %d chunks; plan budget is %d chunks (1 MiB metadata / 256 MiB native DEM). No coverage acquired or truncated", count, maxMapChunks)
		return p, nil
	}
	tiles := map[string]bool{}
	for y := south; y <= north; y++ {
		for x := west; x <= east; x++ {
			wx := ((x+2700)%5400+5400)%5400 - 2700
			b := [4]float64{float64(wx) * mapChunkStep, float64(y) * mapChunkStep, float64(wx+1) * mapChunkStep, float64(y+1) * mapChunkStep}
			// Every envelope chunk is retained: conservative coverage, never omit boundary cells.
			p.Chunks = append(p.Chunks, mapChunk{fmt.Sprintf("%d_%d", wx, y), b})
			if b[1] >= -56 && b[3] <= 60 {
				for ty := int(math.Floor(b[1] - 1.0/3600)); ty <= int(math.Floor(b[3]+1.0/3600)); ty++ {
					for tx := int(math.Floor(b[0] - 1.0/3600)); tx <= int(math.Floor(b[2]+1.0/3600)); tx++ {
						if ty >= -56 && ty < 60 && tx >= -180 && tx < 180 {
							tiles[tileName(ty, tx)] = true
							p.SourceRequests++
						}
					}
				}
			}
		}
	}
	// Stable order for manifests and repeatable planning.
	for name := range tiles {
		p.SourceTiles = append(p.SourceTiles, name)
	}
	sort.Strings(p.SourceTiles)
	p.EstimatedDEMBytes = int64(p.SourceRequests) * demCompressedLimit
	p.Confirmation = len(p.Chunks) > 1 || len(p.SourceTiles) > 1
	return p, nil
}

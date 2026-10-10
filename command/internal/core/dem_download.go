package core

// Fixed public Skadi endpoint, bounded acquisition only; no tracking dependency.
import (
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"time"
)

// Retain the provider's complete multi-source notice, rather than mislabelling
// every tile as NASA SRTM. Skadi is a composite; grid spacing is not source accuracy.
const demAttribution = "Mapzen Terrain Tiles. ArcticDEM: DigitalGlobe imagery, NSF awards 1043681, 1559691, 1542736; Australia © Commonwealth of Australia (Geoscience Australia) 2017; Austria © offene Daten Österreichs – DGM Österreich; Canada: Open Government Licence – Canada; Europe: Copernicus data funded by European Union – EU-DEM; ETOPO1: U.S. NOAA; Mexico: INEGI, Continental relief, 2016; New Zealand © 2011 Crown copyright, Land Information New Zealand and New Zealand Government (All rights reserved); Norway © Kartverket; United Kingdom © Environment Agency copyright/database right 2015 (All rights reserved); United States 3DEP and global GMTED2010/SRTM courtesy of U.S. Geological Survey."

type demTileEvidence struct {
	URL    string `json:"url"`
	SHA256 string `json:"decoded_sha256"`
}
type demProvenance struct {
	Provider    string            `json:"provider"`
	Acquired    string            `json:"acquired_at"`
	Attribution string            `json:"attribution"`
	Terms       string            `json:"terms_url"`
	Tiles       []demTileEvidence `json:"tiles"`
}

func tileName(lat, lon int) string {
	ns, ew := "N", "E"
	if lat < 0 {
		ns = "S"
		lat = -lat
	}
	if lon < 0 {
		ew = "W"
		lon = -lon
	}
	return fmt.Sprintf("%s%02d%s%03d.hgt", ns, lat, ew, lon)
}

func (p *mapProvider) acquireDEM(ctx context.Context, b [4]float64, components map[string]bool) (terrainGrid, error) {
	var g terrainGrid
	for _, v := range b {
		if math.IsNaN(v) || math.IsInf(v, 0) {
			return g, errors.New("invalid DEM bounds")
		}
	}
	if b[0] >= b[2] || b[1] >= b[3] || b[0] < -180 || b[2] > 180 || b[1] < -56 || b[3] > 60 {
		return g, errors.New("automatic DEM supports 56°S–60°N, no antimeridian crossing; vector map retained")
	}
	selected := false
	for k, v := range components {
		if k != "hillshade" && k != "contours" && k != "elevation" {
			return g, errors.New("unknown terrain component")
		}
		selected = selected || v
	}
	if !selected {
		return g, errors.New("select a terrain component")
	}
	// Global integer sample indices remove floating seam drift. One sample halo
	// supports existing Horn shading; coverage edges are clipped, never fabricated.
	w, e := max(-180*3600, int(math.Floor(b[0]*3600))-1), min(180*3600, int(math.Ceil(b[2]*3600))+1)
	s, n := max(-56*3600, int(math.Floor(b[1]*3600))-1), min(60*3600, int(math.Ceil(b[3]*3600))+1)
	if e-w+1 > 257 || n-s+1 > 257 {
		return g, errors.New("DEM subset exceeds 257×257; choose a smaller area")
	}
	g = terrainGrid{Bounds: [4]float64{float64(w) / 3600, float64(s) / 3600, float64(e) / 3600, float64(n) / 3600}, Columns: e - w + 1, Rows: n - s + 1, ArcSeconds: 1, Source: "Mapzen Terrain Tiles · AWS Open Data · Skadi composite", Datum: "EGM96", Components: components, Provenance: &demProvenance{Provider: "AWS Open Data Terrain Tiles / Mapzen Skadi", Acquired: time.Now().UTC().Format(time.RFC3339), Attribution: demAttribution, Terms: "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"}}
	g.Samples = make([]*float64, g.Rows*g.Columns)
	west, east := int(math.Floor(g.Bounds[0])), int(math.Ceil(g.Bounds[2]))-1
	south, north := int(math.Floor(g.Bounds[1])), int(math.Ceil(g.Bounds[3]))-1
	if (east-west+1)*(north-south+1) > 4 {
		return g, errors.New("DEM acquisition exceeds four source tiles")
	}
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	for lat := south; lat <= north; lat++ {
		for lon := west; lon <= east; lon++ {
			name := tileName(lat, lon)
			address := p.dem + "/" + name[:3] + "/" + name + ".gz"
			req, err := http.NewRequestWithContext(ctx, "GET", address, nil)
			if err != nil {
				return g, err
			}
			req.Header.Set("User-Agent", "GNSS-Command/0.1 (bounded DEM acquisition; https://github.com/suraj-singh12/gnss-android-tracking)")
			resp, err := p.client.Do(req)
			if err != nil {
				return g, fmt.Errorf("DEM acquisition failed: %w", err)
			}
			if resp.StatusCode != http.StatusOK {
				resp.Body.Close()
				return g, fmt.Errorf("DEM provider returned HTTP %d; retry terrain later", resp.StatusCode)
			}
			// A tile is decoded alone, with compressed/decompressed bomb limits and
			// gzip CRC verification. Full raw tiles are never persisted or cached.
			zr, err := gzip.NewReader(io.LimitReader(resp.Body, 26*1024*1024+1))
			var raw []byte
			if err == nil {
				raw, err = io.ReadAll(io.LimitReader(zr, 3601*3601*2+1))
				zr.Close()
			}
			resp.Body.Close()
			if err != nil {
				return g, fmt.Errorf("invalid compressed DEM: %w", err)
			}
			if len(raw) != 3601*3601*2 {
				return g, errors.New("DEM provider must return a 3601×3601 HGT tile")
			}
			clip := [4]float64{math.Max(g.Bounds[0], float64(lon)), math.Max(g.Bounds[1], float64(lat)), math.Min(g.Bounds[2], float64(lon+1)), math.Min(g.Bounds[3], float64(lat+1))}
			crop, err := importHGT(name, raw, clip, components)
			if err != nil {
				return g, err
			}
			hash := sha256.Sum256(raw)
			g.Provenance.Tiles = append(g.Provenance.Tiles, demTileEvidence{address, hex.EncodeToString(hash[:])})
			for y := 0; y < crop.Rows; y++ {
				for x := 0; x < crop.Columns; x++ {
					gx := int(math.Round(crop.Bounds[0]*3600)) + x - w
					gy := n - int(math.Round(crop.Bounds[3]*3600)) + y
					if gx >= 0 && gx < g.Columns && gy >= 0 && gy < g.Rows {
						// Shared seam samples belong to the same geographic node. Preserve
						// a valid adjacent sample when one source tile has a void there.
						if v := crop.Samples[y*crop.Columns+x]; v != nil {
							g.Samples[gy*g.Columns+gx] = v
						}
					}
				}
			}
		}
	}
	return g, g.validate()
}

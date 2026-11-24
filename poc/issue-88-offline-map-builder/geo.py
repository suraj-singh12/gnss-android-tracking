"""Small dependency-free geodesy helpers (WGS84) for the POC planner."""
from __future__ import annotations

import math

A = 6378137.0
F = 1 / 298.257223563
B = A * (1 - F)


def direct(lat: float, lon: float, azimuth_deg: float, dist_m: float) -> tuple[float, float]:
    """Vincenty direct geodesic on WGS84 (sub-millimetre at 20 km)."""
    phi1, alpha1 = math.radians(lat), math.radians(azimuth_deg)
    s_a, c_a = math.sin(alpha1), math.cos(alpha1)
    tan_u1 = (1 - F) * math.tan(phi1)
    cos_u1 = 1 / math.sqrt(1 + tan_u1 * tan_u1)
    sin_u1 = tan_u1 * cos_u1
    sigma1 = math.atan2(tan_u1, c_a)
    sin_alpha = cos_u1 * s_a
    cos2_alpha = 1 - sin_alpha * sin_alpha
    u2 = cos2_alpha * (A * A - B * B) / (B * B)
    k_a = 1 + u2 / 16384 * (4096 + u2 * (-768 + u2 * (320 - 175 * u2)))
    k_b = u2 / 1024 * (256 + u2 * (-128 + u2 * (74 - 47 * u2)))
    sigma = dist_m / (B * k_a)
    for _ in range(200):
        c2sm = math.cos(2 * sigma1 + sigma)
        s_s, c_s = math.sin(sigma), math.cos(sigma)
        d_sigma = k_b * s_s * (c2sm + k_b / 4 * (c_s * (-1 + 2 * c2sm * c2sm) - k_b / 6 * c2sm * (-3 + 4 * s_s * s_s) * (-3 + 4 * c2sm * c2sm)))
        nxt = dist_m / (B * k_a) + d_sigma
        if abs(nxt - sigma) < 1e-12:
            sigma = nxt
            break
        sigma = nxt
    s_s, c_s = math.sin(sigma), math.cos(sigma)
    c2sm = math.cos(2 * sigma1 + sigma)
    tmp = sin_u1 * s_s - cos_u1 * c_s * c_a
    phi2 = math.atan2(sin_u1 * c_s + cos_u1 * s_s * c_a, (1 - F) * math.sqrt(sin_alpha * sin_alpha + tmp * tmp))
    lam = math.atan2(s_s * s_a, cos_u1 * c_s - sin_u1 * s_s * c_a)
    c = F / 16 * cos2_alpha * (4 + F * (4 - 3 * cos2_alpha))
    big_l = lam - (1 - c) * F * sin_alpha * (sigma + c * s_s * (c2sm + c * c_s * (-1 + 2 * c2sm * c2sm)))
    return math.degrees(phi2), lon + math.degrees(big_l)


def haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def circle(lat: float, lon: float, radius_m: float, n: int = 360) -> list[tuple[float, float]]:
    """Closed ring of (lon, lat) points of the geodesic circle."""
    ring = [direct(lat, lon, 360 * i / n, radius_m)[::-1] for i in range(n)]
    ring.append(ring[0])
    return ring


def bbox(ring: list[tuple[float, float]], margin_deg: float = 0.0) -> tuple[float, float, float, float]:
    xs, ys = [p[0] for p in ring], [p[1] for p in ring]
    return (min(xs) - margin_deg, min(ys) - margin_deg, max(xs) + margin_deg, max(ys) + margin_deg)


def point_in_ring(x: float, y: float, ring) -> bool:
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i][0], ring[i][1]
        xj, yj = ring[j][0], ring[j][1]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi + 1e-300) + xi:
            inside = not inside
        j = i
    return inside


def point_in_polygon(x: float, y: float, polygon) -> bool:
    """GeoJSON Polygon coordinates (outer ring + holes)."""
    if not polygon or not point_in_ring(x, y, polygon[0]):
        return False
    return not any(point_in_ring(x, y, hole) for hole in polygon[1:])


def point_in_geometry(x: float, y: float, geom: dict) -> bool:
    if geom["type"] == "Polygon":
        return point_in_polygon(x, y, geom["coordinates"])
    if geom["type"] == "MultiPolygon":
        return any(point_in_polygon(x, y, p) for p in geom["coordinates"])
    return False


def sample_circle(lat: float, lon: float, radius_m: float, spacing_m: float = 1000.0) -> list[tuple[float, float]]:
    """Grid samples inside the circle plus its boundary, as (lon, lat)."""
    pts = []
    dlat = spacing_m / 111320.0
    dlon = spacing_m / (111320.0 * math.cos(math.radians(lat)))
    steps = int(radius_m / spacing_m) + 1
    for i in range(-steps, steps + 1):
        for j in range(-steps, steps + 1):
            y, x = lat + i * dlat, lon + j * dlon
            if haversine(lat, lon, y, x) <= radius_m:
                pts.append((x, y))
    pts.extend(circle(lat, lon, radius_m, 180)[:-1])
    return pts


def lonlat_to_tile(lon: float, lat: float, z: int) -> tuple[float, float]:
    n = 2 ** z
    x = (lon + 180) / 360 * n
    r = math.radians(lat)
    y = (1 - math.asinh(math.tan(r)) / math.pi) / 2 * n
    return x, y

// gnss-mvt-repair — deterministic post-tiling geometry validation and repair
// for GDAL MVT output (issue #97). Native replacement for the qualified
// experimental Python correction (#93): no Python/Node/Conda in the operator
// runtime, GEOS MakeValid semantics preserved exactly.
//
// Contract (mirrors the qualified experiment):
// - decode every tile, validate all polygon geometry with GEOS
// - repair invalid polygons with GEOS MakeValid, polygonal parts only;
//   features with no polygonal remainder are dropped
// - re-encode changed tiles deterministically (gzip level 9, mtime 0);
//   copy unchanged tiles byte-identically
// - preserve feature identity (id), all properties (names, references,
//   classes), holes and multipolygons
// - report JSON: tiles, tiles_with_repairs, features_repaired, per-repair
//   details, byte counts, wall time, and a post-encode verification count
//   proving the final encoded geometry passes the zero-invalid gate
//
// Usage: gnss-mvt-repair <src_dir> <dst_dir> <report.json>
#include <protozero/pbf_reader.hpp>
#include <protozero/pbf_writer.hpp>

#include <geos_c.h>

#include <zlib.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <vector>

namespace fs = std::filesystem;

namespace {

// ---------------------------------------------------------------- gzip ----

bool is_gzip(const std::string& data) {
  return data.size() >= 2 && (unsigned char)data[0] == 0x1f && (unsigned char)data[1] == 0x8b;
}

std::string gunzip(const std::string& in) {
  z_stream zs{};
  inflateInit2(&zs, 16 + MAX_WBITS);
  zs.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(in.data()));
  zs.avail_in = static_cast<uInt>(in.size());
  std::string out;
  char buf[65536];
  int ret;
  do {
    zs.next_out = reinterpret_cast<Bytef*>(buf);
    zs.avail_out = sizeof(buf);
    ret = inflate(&zs, 0);
    out.append(buf, sizeof(buf) - zs.avail_out);
  } while (ret == Z_OK);
  inflateEnd(&zs);
  if (ret != Z_STREAM_END) throw std::runtime_error("invalid gzip stream");
  return out;
}

// gzip with deterministic header: mtime 0, no original name — byte-identical
// output for identical input, matching the qualified experiment's contract.
std::string gzip_deterministic(const std::string& in) {
  z_stream zs{};
  deflateInit2(&zs, 9, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY);
  zs.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(in.data()));
  zs.avail_in = static_cast<uInt>(in.size());
  std::string out;
  char buf[65536];
  int ret;
  do {
    zs.next_out = reinterpret_cast<Bytef*>(buf);
    zs.avail_out = sizeof(buf);
    ret = deflate(&zs, Z_FINISH);
    out.append(buf, sizeof(buf) - zs.avail_out);
  } while (ret == Z_OK);
  deflateEnd(&zs);
  if (ret != Z_STREAM_END) throw std::runtime_error("gzip compression failed");
  // deflateInit2 writes mtime=0 already when using default gzip header.
  return out;
}

// ------------------------------------------------------- mvt data model ----

struct Value {
  // exactly one alternative, tagged by the MVT Value field number
  uint32_t field = 0;
  std::string s;
  double d = 0;
  float f = 0;
  int64_t i = 0;
  uint64_t u = 0;
  bool b = false;
  bool operator==(const Value& o) const {
    return field == o.field && s == o.s && d == o.d && f == o.f && i == o.i && u == o.u && b == o.b;
  }
};

using Ring = std::vector<std::pair<int32_t, int32_t>>;

struct Feature {
  std::string geom_raw;  // original packed geometry — passthrough unless repaired
  uint64_t id = 0;
  bool has_id = false;
  uint32_t geom_type = 0; // 1 point, 2 linestring, 3 polygon
  std::vector<std::pair<std::string, Value>> props; // resolved key/value pairs
  std::vector<Ring> rings;                          // decoded geometry as rings/parts
  bool changed = false;
  bool dropped = false;
  // repair reporting
  std::string klass;
  std::string reason;
  double area_before = 0, area_after = 0;
};

struct Layer {
  std::string name;
  uint32_t extent = 4096;
  std::vector<Feature> features;
};

// -------------------------------------------------------- geometry codec ----

inline uint32_t zz(int32_t v) { return (static_cast<uint32_t>(v) << 1) ^ static_cast<uint32_t>(v >> 31); }
inline int32_t unzz(uint32_t v) { return static_cast<int32_t>((v >> 1) ^ (~(v & 1) + 1)); }

// decode a packed geometry command stream into rings/parts. Each MoveTo
// starts a new part; ClosePath terminates rings but points are already
// absolute-ized here from the relative wire deltas.
inline std::vector<uint32_t> parse_varints(const std::string& payload) {
  std::vector<uint32_t> out;
  size_t i = 0;
  while (i < payload.size()) {
    uint64_t v = 0;
    int shift = 0;
    while (i < payload.size()) {
      unsigned char c = static_cast<unsigned char>(payload[i++]);
      v |= static_cast<uint64_t>(c & 0x7f) << shift;
      if (!(c & 0x80)) break;
      shift += 7;
    }
    out.push_back(static_cast<uint32_t>(v));
  }
  return out;
}

std::vector<Ring> decode_geometry(const std::string& payload) {
  std::vector<Ring> parts;
  int64_t cx = 0, cy = 0;
  Ring cur;
  std::vector<uint32_t> cmds = parse_varints(payload);
  size_t pos = 0;
  while (pos < cmds.size()) {
    uint32_t c = cmds[pos++];
    uint32_t id = c & 7, count = c >> 3;
    if (id == 1 || id == 2) { // MoveTo / LineTo
      for (uint32_t k = 0; k < count && pos + 1 < cmds.size(); k++) {
        int32_t dx = unzz(cmds[pos++]);
        int32_t dy = unzz(cmds[pos++]);
        cx += dx; cy += dy;
        if (id == 1) {
          if (!cur.empty()) parts.push_back(cur);
          cur.clear();
        }
        cur.emplace_back(static_cast<int32_t>(cx), static_cast<int32_t>(cy));
      }
    } else if (id == 7) { // ClosePath
      if (!cur.empty()) { parts.push_back(cur); cur.clear(); }
    }
  }
  if (!cur.empty()) parts.push_back(cur);
  return parts;
}

// surveyor signed area in tile coordinates (y-down): positive = exterior.
double ring_area(const Ring& r) {
  if (r.size() < 3) return 0;
  double a = 0;
  for (size_t i = 0; i + 1 < r.size(); i++)
    a += static_cast<double>(r[i + 1].first - r[i].first) * (r[i + 1].second + r[i].second);
  // close the ring if not explicit
  if (r.front() != r.back())
    a += static_cast<double>(r.front().first - r.back().first) * (r.front().second + r.back().second);
  return a / 2.0;
}

void append_varint(std::string& out, uint64_t v) {
  while (v >= 0x80) { out.push_back(static_cast<char>((v & 0x7f) | 0x80)); v >>= 7; }
  out.push_back(static_cast<char>(v));
}

// encode rings into a packed geometry command stream.
std::string encode_geometry(const std::vector<Ring>& parts) {
  std::string body;
  int64_t cx = 0, cy = 0;
  for (const Ring& ring_in : parts) {
    Ring ring = ring_in;
    // drop duplicated closing point — encoding adds ClosePath commands
    while (ring.size() > 1 && ring.front() == ring.back()) ring.pop_back();
    if (ring.size() < 2) continue;
    uint32_t n = static_cast<uint32_t>(ring.size());
    uint32_t moveto = (1u & 7) | (1u << 3);
    append_varint(body, moveto);
    append_varint(body, zz(static_cast<int32_t>(ring[0].first - cx)));
    append_varint(body, zz(static_cast<int32_t>(ring[0].second - cy)));
    cx = ring[0].first; cy = ring[0].second;
    uint32_t lineto = (2u & 7) | ((n - 1) << 3);
    append_varint(body, lineto);
    for (size_t i = 1; i < ring.size(); i++) {
      append_varint(body, zz(static_cast<int32_t>(ring[i].first - cx)));
      append_varint(body, zz(static_cast<int32_t>(ring[i].second - cy)));
      cx = ring[i].first; cy = ring[i].second;
    }
    append_varint(body, (7u & 7) | (1u << 3));
  }
  return body;
}

// ------------------------------------------------------------ geos side ----

struct GeosInit {
  GeosInit() { initGEOS(nullptr, nullptr); }
  ~GeosInit() { finishGEOS(); }
};

GEOSGeometry* ring_to_geos(const Ring& r, GEOSContextHandle_t ctx) {
  GEOSCoordSequence* cs = GEOSCoordSeq_create_r(ctx, static_cast<unsigned>(r.size() + 1), 2);
  for (size_t i = 0; i < r.size(); i++) {
    GEOSCoordSeq_setX_r(ctx, cs, static_cast<unsigned>(i), r[i].first);
    GEOSCoordSeq_setY_r(ctx, cs, static_cast<unsigned>(i), r[i].second);
  }
  GEOSCoordSeq_setX_r(ctx, cs, static_cast<unsigned>(r.size()), r[0].first);
  GEOSCoordSeq_setY_r(ctx, cs, static_cast<unsigned>(r.size()), r[0].second);
  return GEOSGeom_createLinearRing_r(ctx, cs);
}

// build a GEOS polygon/multipolygon from rings classified by winding. Ring
// grouping mirrors the proven decoder of the qualified experiment exactly:
// zero-area rings are skipped, and the FIRST ring's sign anchors the exterior
// orientation for this feature — rings sharing it start new polygons, rings
// opposing it are holes of the current one. Hard-coding one orientation
// instead mis-groups real GDAL output whose first ring is sign-flipped by
// buffer clipping, tearing holes away from their shells.
GEOSGeometry* build_geos_polygon(const std::vector<Ring>& rings, GEOSContextHandle_t ctx) {
  std::vector<std::pair<size_t, std::vector<size_t>>> polys; // exterior idx -> hole idxs
  int exterior_sign = 0;
  for (size_t i = 0; i < rings.size(); i++) {
    double a = ring_area(rings[i]);
    int sign = a < 0 ? -1 : a > 0 ? 1 : 0;
    if (sign == 0) continue;
    if (exterior_sign == 0) exterior_sign = sign;
    if (sign == exterior_sign || polys.empty())
      polys.emplace_back(i, std::vector<size_t>{});
    else
      polys.back().second.push_back(i);
  }
  std::vector<GEOSGeometry*> geos_polys;
  for (auto& [ext, holes] : polys) {
    GEOSGeometry* shell = ring_to_geos(rings[ext], ctx);
    std::vector<GEOSGeometry*> hs;
    for (size_t h : holes) hs.push_back(ring_to_geos(rings[h], ctx));
    GEOSGeometry* p = GEOSGeom_createPolygon_r(ctx, shell, hs.empty() ? nullptr : hs.data(), static_cast<unsigned>(hs.size()));
    if (p) geos_polys.push_back(p);
  }
  if (geos_polys.empty()) return nullptr;
  if (geos_polys.size() == 1) return geos_polys[0];
  GEOSGeometry* mp = GEOSGeom_createCollection_r(ctx, GEOS_MULTIPOLYGON, geos_polys.data(), static_cast<unsigned>(geos_polys.size()));
  return mp;
}

double geos_area(GEOSGeometry* g, GEOSContextHandle_t ctx) {
  double a = 0;
  if (g) GEOSArea_r(ctx, g, &a);
  return a;
}

// extract polygonal parts of a (possibly collection) geometry into rings,
// winding-normalized for re-encoding: exterior positive, holes negative.
bool extract_polygonal(GEOSGeometry* g, GEOSContextHandle_t ctx, std::vector<Ring>& out) {
  if (!g) return false;
  bool any = false;
  int type = GEOSGeomTypeId_r(ctx, g);
  std::vector<const GEOSGeometry*> polys;
  if (type == GEOS_POLYGON) polys.push_back(g);
  else if (type == GEOS_MULTIPOLYGON || type == GEOS_GEOMETRYCOLLECTION) {
    int n = GEOSGetNumGeometries_r(ctx, g);
    for (int i = 0; i < n; i++) {
      const GEOSGeometry* part = GEOSGetGeometryN_r(ctx, g, i);
      if (!part) continue;
      int pt = GEOSGeomTypeId_r(ctx, part);
      if (pt == GEOS_POLYGON) polys.push_back(part);
      else if (pt == GEOS_MULTIPOLYGON) {
        int m = GEOSGetNumGeometries_r(ctx, part);
        for (int j = 0; j < m; j++) {
          const GEOSGeometry* sub = GEOSGetGeometryN_r(ctx, part, j);
          if (sub && GEOSGeomTypeId_r(ctx, sub) == GEOS_POLYGON) polys.push_back(sub);
        }
      }
    }
  }
  for (const GEOSGeometry* p : polys) {
    auto read_ring = [&](const GEOSGeometry* lr, bool exterior) {
      const GEOSCoordSequence* cs = GEOSGeom_getCoordSeq_r(ctx, lr);
      if (!cs) return;
      unsigned sz = 0;
      GEOSCoordSeq_getSize_r(ctx, cs, &sz);
      Ring r;
      for (unsigned i = 0; i + 1 < sz; i++) { // last == first, drop duplicate
        double x = 0, y = 0;
        GEOSCoordSeq_getX_r(ctx, cs, i, &x);
        GEOSCoordSeq_getY_r(ctx, cs, i, &y);
        r.emplace_back(static_cast<int32_t>(std::llround(x)), static_cast<int32_t>(std::llround(y)));
      }
      if (r.size() < 3) return;
      double a = ring_area(r);
      // re-encode uses the same MVT winding the decode relies on
      if ((exterior && a > 0) || (!exterior && a < 0)) std::reverse(r.begin(), r.end());
      out.push_back(std::move(r));
      any = true;
    };
    const GEOSGeometry* ext = GEOSGetExteriorRing_r(ctx, p);
    if (ext) read_ring(ext, true);
    int nh = GEOSGetNumInteriorRings_r(ctx, p);
    for (int i = 0; i < nh; i++) {
      const GEOSGeometry* hole = GEOSGetInteriorRingN_r(ctx, p, i);
      if (hole) read_ring(hole, false);
    }
  }
  return any;
}

// count invalid polygon features in a fully decoded tileset input — used by
// the post-encode verification pass proving the zero-invalid gate.
struct VerifyCounter {
  uint64_t polygons = 0, invalid = 0;
};

// -------------------------------------------------------------- io utils ----

std::string read_file(const fs::path& p) {
  // failures are fatal: silently skipping a tile would produce incomplete
  // output that could pass superficial inspection
  std::ifstream f(p, std::ios::binary);
  if (!f) throw std::runtime_error("cannot read " + p.string());
  std::ostringstream ss;
  ss << f.rdbuf();
  if (f.bad()) throw std::runtime_error("read error " + p.string());
  return ss.str();
}

void write_file(const fs::path& p, const std::string& data) {
  if (!p.parent_path().empty()) fs::create_directories(p.parent_path());
  std::ofstream f(p, std::ios::binary | std::ios::trunc);
  if (!f) throw std::runtime_error("cannot write " + p.string());
  f.write(data.data(), static_cast<std::streamsize>(data.size()));
  f.flush();
  if (!f) throw std::runtime_error("write error " + p.string());
}

std::string json_escape(const std::string& s) {
  std::string out;
  for (char c : s) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (static_cast<unsigned char>(c) < 0x20) {
          char buf[8];
          snprintf(buf, sizeof(buf), "\\u%04x", c);
          out += buf;
        } else out += c;
    }
  }
  return out;
}

double round2(double v) { return std::round(v * 100.0) / 100.0; }
double round3(double v) { return std::round(v * 1000.0) / 1000.0; }

} // namespace

int run_repair(const fs::path& src, const fs::path& dst, const fs::path& report_path);
int run_verify(const fs::path& dir, VerifyCounter& total);

int main(int argc, char** argv) {
  if (argc == 3 && std::string(argv[1]) == "--verify") {
    // verification mode: count invalid polygon features across a tileset
    VerifyCounter total;
    try {
      if (run_verify(argv[2], total) != 0) return 2;
    } catch (const std::exception& e) {
      std::fprintf(stderr, "verify failed: %s\n", e.what());
      return 2;
    }
    std::printf("{\"polygon_features\": %llu, \"invalid\": %llu}\n",
                static_cast<unsigned long long>(total.polygons),
                static_cast<unsigned long long>(total.invalid));
    return total.invalid == 0 ? 0 : 1;
  }
  if (argc != 4) {
    std::fprintf(stderr, "usage: gnss-mvt-repair <src_dir> <dst_dir> <report.json>\n"
                         "       gnss-mvt-repair --verify <dir>\n");
    return 2;
  }
  try {
    return run_repair(argv[1], argv[2], argv[3]);
  } catch (const std::exception& e) {
    std::fprintf(stderr, "repair failed: %s\n", e.what());
    return 2;
  }
}

namespace {

// decode one tile body into layers, resolving tags against key/value tables.
std::vector<Layer> decode_tile(const std::string& data) {
  std::vector<Layer> layers;
  protozero::pbf_reader tile(data);
  while (tile.next(3)) {
    protozero::pbf_reader lmsg = tile.get_message();
    Layer layer;
    std::vector<std::string> keys;
    std::vector<Value> values;
    // feature blobs must be read in encounter order, so buffer the raw
    // feature messages while collecting keys/values which may follow them.
    std::vector<std::string> feat_blobs;
    while (lmsg.next()) {
      switch (lmsg.tag()) {
        case 1: layer.name = lmsg.get_string(); break;
        case 2: feat_blobs.push_back(lmsg.get_bytes()); break;
        case 3: keys.push_back(lmsg.get_string()); break;
        case 4: {
          protozero::pbf_reader vmsg = lmsg.get_message();
          Value v;
          while (vmsg.next()) {
            v.field = vmsg.tag();
            switch (vmsg.tag()) {
              case 1: v.s = vmsg.get_string(); break;
              case 2: v.f = vmsg.get_float(); v.d = v.f; break;
              case 3: v.d = vmsg.get_double(); break;
              case 4: v.i = vmsg.get_int64(); break;
              case 5: v.u = vmsg.get_uint64(); break;
              case 6: v.i = vmsg.get_sint64(); break;
              case 7: v.b = vmsg.get_bool(); break;
              default: vmsg.skip();
            }
          }
          values.push_back(std::move(v));
          break;
        }
        case 5: layer.extent = lmsg.get_uint32(); break;
        default: lmsg.skip();
      }
    }
    for (const std::string& blob : feat_blobs) {
      protozero::pbf_reader fmsg(blob);
      Feature feat;
      std::vector<uint32_t> tags;
      std::string geom_packed;
      while (fmsg.next()) {
        switch (fmsg.tag()) {
          case 1: feat.id = fmsg.get_uint64(); feat.has_id = true; break;
          case 2: {
            for (uint32_t v : parse_varints(fmsg.get_bytes())) tags.push_back(v);
            break;
          }
          case 3: feat.geom_type = fmsg.get_enum(); break;
          case 4: geom_packed = fmsg.get_bytes(); break;
          default: fmsg.skip();
        }
      }
      for (size_t t = 0; t + 1 < tags.size(); t += 2) {
        if (tags[t] < keys.size() && tags[t + 1] < values.size())
          feat.props.emplace_back(keys[tags[t]], values[tags[t + 1]]);
        if (tags[t] < keys.size() && keys[tags[t]] == "class" && tags[t + 1] < values.size())
          feat.klass = values[tags[t + 1]].s;
      }
      feat.geom_raw = geom_packed;
      if (!geom_packed.empty()) feat.rings = decode_geometry(geom_packed);
      layer.features.push_back(std::move(feat));
    }
    layers.push_back(std::move(layer));
  }
  return layers;
}

// re-encode one tile deterministically: key/value tables rebuilt in order of
// first appearance. A packed repeated uint32 field is wire-identical to a
// length-delimited varint payload, so add_bytes carries the packed tags and
// geometry command streams exactly.
std::string encode_tile(std::vector<Layer>& layers) {
  std::string tile_buf;
  protozero::pbf_writer tw(tile_buf);
  for (Layer& layer : layers) {
    std::string layer_buf;
    protozero::pbf_writer lw(layer_buf);
    lw.add_string(1, layer.name);
    std::vector<std::string> keys;
    std::vector<Value> values;
    std::vector<std::string> feat_blobs;
    for (Feature& feat : layer.features) {
      if (feat.dropped) continue;
      std::vector<std::pair<uint32_t, uint32_t>> tagidx;
      for (auto& [k, v] : feat.props) {
        auto ki = std::find(keys.begin(), keys.end(), k);
        if (ki == keys.end()) { keys.push_back(k); ki = keys.end() - 1; }
        auto vi = std::find(values.begin(), values.end(), v);
        if (vi == values.end()) { values.push_back(v); vi = values.end() - 1; }
        tagidx.emplace_back(static_cast<uint32_t>(ki - keys.begin()),
                            static_cast<uint32_t>(vi - values.begin()));
      }
      std::string fb;
      protozero::pbf_writer fw(fb);
      if (feat.has_id) fw.add_uint64(1, feat.id);
      std::string packed;
      for (auto [ki, vi] : tagidx) {
        append_varint(packed, ki);
        append_varint(packed, vi);
      }
      fw.add_bytes(2, packed);
      fw.add_enum(3, static_cast<int32_t>(feat.geom_type));
      // untouched features keep their exact original geometry bytes — only
      // repaired polygons are re-encoded. Re-encoding points/lines through the
      // polygon-oriented ring model would corrupt them (points dropped,
      // lines falsely closed).
      std::string geom = feat.changed ? encode_geometry(feat.rings) : feat.geom_raw;
      fw.add_bytes(4, geom);
      feat_blobs.push_back(fb);
    }
    for (const std::string& fb : feat_blobs) lw.add_bytes(2, fb);
    for (const std::string& k : keys) lw.add_string(3, k);
    for (const Value& v : values) {
      std::string vb;
      protozero::pbf_writer vw(vb);
      switch (v.field) {
        case 1: vw.add_string(1, v.s); break;
        case 2: vw.add_float(2, v.f); break;
        case 3: vw.add_double(3, v.d); break;
        case 4: vw.add_int64(4, v.i); break;
        case 5: vw.add_uint64(5, v.u); break;
        case 6: vw.add_sint64(6, v.i); break;
        case 7: vw.add_bool(7, v.b); break;
      }
      lw.add_bytes(4, vb);
    }
    lw.add_uint32(5, layer.extent);
    lw.add_uint32(15, 2);
    tw.add_bytes(3, layer_buf);
  }
  return tile_buf;
}

} // namespace

int run_verify(const fs::path& dir, VerifyCounter& total) {
  GeosInit init;
  GEOSContextHandle_t ctx = GEOS_init_r();
  std::vector<fs::path> files;
  for (auto& e : fs::recursive_directory_iterator(dir))
    if (e.path().extension() == ".pbf") files.push_back(e.path());
  std::sort(files.begin(), files.end());
  for (const auto& p : files) {
    std::string raw = read_file(p);
    std::string data = is_gzip(raw) ? gunzip(raw) : raw;
    for (Layer& layer : decode_tile(data)) {
      for (Feature& feat : layer.features) {
        if (feat.geom_type != 3) continue;
        total.polygons++;
        GEOSGeometry* g = build_geos_polygon(feat.rings, ctx);
        if (!g) { total.invalid++; continue; }
        char valid = GEOSisValid_r(ctx, g);
        if (valid != 1) {
          total.invalid++;
          char* reason = GEOSisValidReason_r(ctx, g);
          std::fprintf(stderr, "invalid: %s layer=%s class=%s reason=%s\n",
                       p.c_str(), layer.name.c_str(), feat.klass.c_str(),
                       reason ? reason : "?");
          if (reason) GEOSFree_r(ctx, reason);
        }
        GEOSGeom_destroy_r(ctx, g);
      }
    }
  }
  GEOS_finish_r(ctx);
  return 0;
}

int run_repair(const fs::path& src, const fs::path& dst, const fs::path& report_path) {
  GeosInit init;
  GEOSContextHandle_t ctx = GEOS_init_r();
  // never destroy operator data: refuse overlapping or pre-existing output
  // instead of silently deleting it. The previous behavior removed dst before
  // even validating src — with dst == src that erased the original tiles.
  fs::path src_abs = fs::weakly_canonical(src);
  fs::path dst_abs = fs::weakly_canonical(dst);
  if (src_abs == dst_abs)
    throw std::runtime_error("output directory must differ from input");
  auto nested = [](const fs::path& a, const fs::path& b) {
    auto ai = a.begin(), bi = b.begin();
    for (; ai != a.end() && bi != b.end(); ++ai, ++bi)
      if (*ai != *bi) return false;
    return ai == a.end();
  };
  if (nested(dst_abs, src_abs) || nested(src_abs, dst_abs))
    throw std::runtime_error("output and input directories must not contain each other");
  if (!fs::is_directory(src_abs))
    throw std::runtime_error("input directory does not exist: " + src.string());
  if (fs::exists(dst_abs) && !fs::is_empty(dst_abs))
    throw std::runtime_error("output directory exists and is not empty: " + dst.string());
  fs::create_directories(dst_abs);
  auto t0 = std::chrono::steady_clock::now();

  uint64_t tiles = 0, tiles_with_repairs = 0, features_repaired = 0;
  uint64_t bytes_before = 0, bytes_after = 0;
  std::ostringstream details;

  std::vector<fs::path> files;
  for (auto& e : fs::recursive_directory_iterator(src)) {
    if (!e.is_regular_file()) continue;
    if (e.path().extension() == ".pbf") { files.push_back(e.path()); continue; }
    // non-tile files (GDAL metadata.json declaring bounds/zoom/layers) are
    // copied verbatim — dropping them silently strips the tileset contract.
    fs::path rel0 = fs::relative(e.path(), src);
    write_file(dst / rel0, read_file(e.path()));
  }
  std::sort(files.begin(), files.end());

  bool first_detail = true;
  for (const auto& p : files) {
    fs::path rel = fs::relative(p, src);
    std::string raw = read_file(p);
    bytes_before += raw.size();
    std::string data = is_gzip(raw) ? gunzip(raw) : raw;
    std::vector<Layer> layers = decode_tile(data);
    bool changed = false;
    for (Layer& layer : layers) {
      for (Feature& feat : layer.features) {
        if (feat.geom_type != 3 || feat.rings.empty()) continue;
        GEOSGeometry* g = build_geos_polygon(feat.rings, ctx);
        if (!g) continue;
        if (GEOSisValid_r(ctx, g) == 1) { GEOSGeom_destroy_r(ctx, g); continue; }
        char* reason = GEOSisValidReason_r(ctx, g);
        feat.reason = reason ? reason : "invalid";
        if (reason) GEOSFree_r(ctx, reason);
        feat.area_before = geos_area(g, ctx);
        GEOSGeom_destroy_r(ctx, g);
        // repair: rebuild, MakeValid, then reduce onto the integer output
        // grid with validity-preserving precision reduction. Without this,
        // rounding MakeValid's computed intersection coordinates back to
        // tile integers can re-introduce self-intersections — the encoded
        // output must pass the zero-invalid gate, not merely the pre-encode
        // repair.
        GEOSGeometry* orig = build_geos_polygon(feat.rings, ctx);
        GEOSGeometry* mv = orig ? GEOSMakeValid_r(ctx, orig) : nullptr;
        GEOSGeometry* fixed = mv ? GEOSGeom_setPrecision_r(ctx, mv, 1.0, GEOS_PREC_VALID_OUTPUT) : nullptr;
        if (mv) GEOSGeom_destroy_r(ctx, mv);
        std::vector<Ring> repaired;
        bool keep = fixed && extract_polygonal(fixed, ctx, repaired);
        feat.area_after = 0;
        if (keep) {
          GEOSGeometry* chk = build_geos_polygon(repaired, ctx);
          feat.area_after = geos_area(chk, ctx);
          if (chk) GEOSGeom_destroy_r(ctx, chk);
        }
        if (!keep) feat.dropped = true;
        else feat.rings = std::move(repaired);
        if (orig) GEOSGeom_destroy_r(ctx, orig);
        if (fixed) GEOSGeom_destroy_r(ctx, fixed);
        feat.changed = true;
        changed = true;
        features_repaired++;
        // detail record mirrors the qualified experiment's report shape
        std::string zxy = rel.generic_string();
        if (!first_detail) details << ",\n";
        first_detail = false;
        double pct = feat.area_before ? 100.0 * (feat.area_after - feat.area_before) / feat.area_before : 0;
        details << "    {\"tile\": \"" << json_escape(zxy) << "\", \"layer\": \"" << json_escape(layer.name)
                << "\", \"class\": \"" << json_escape(feat.klass) << "\", \"reason\": \"" << json_escape(feat.reason)
                << "\", \"area_before\": " << round2(feat.area_before) << ", \"area_after\": " << round2(feat.area_after)
                << ", \"area_change_pct\": " << round3(pct) << ", \"dropped\": " << (feat.dropped ? "true" : "false") << "}";
      }
    }
    std::string body = raw;
    if (changed) {
      tiles_with_repairs++;
      std::string encoded = encode_tile(layers);
      body = gzip_deterministic(encoded);
    }
    write_file(dst / rel, body);
    bytes_after += body.size();
    tiles++;
  }
  GEOS_finish_r(ctx);

  // post-encode verification: the gate is on the final encoded geometry,
  // not the pre-encode repair — decode the output independently and count.
  VerifyCounter verify;
  run_verify(dst, verify);

  auto t1 = std::chrono::steady_clock::now();
  double wall = std::chrono::duration<double>(t1 - t0).count();

  std::ostringstream rep;
  rep << "{\n"
      << "  \"tiles\": " << tiles << ",\n"
      << "  \"tiles_with_repairs\": " << tiles_with_repairs << ",\n"
      << "  \"features_repaired\": " << features_repaired << ",\n"
      << "  \"polygon_features_after\": " << verify.polygons << ",\n"
      << "  \"invalid_after\": " << verify.invalid << ",\n"
      << "  \"bytes_before\": " << bytes_before << ",\n"
      << "  \"bytes_after\": " << bytes_after << ",\n"
      << "  \"wall_s\": " << round2(wall) << ",\n"
      << "  \"details\": [\n" << details.str() << "\n  ]\n}\n";
  write_file(report_path, rep.str());
  std::printf("{\"tiles\": %llu, \"tiles_with_repairs\": %llu, \"features_repaired\": %llu, \"invalid_after\": %llu, \"bytes_before\": %llu, \"bytes_after\": %llu, \"wall_s\": %.2f}\n",
              (unsigned long long)tiles, (unsigned long long)tiles_with_repairs,
              (unsigned long long)features_repaired, (unsigned long long)verify.invalid,
              (unsigned long long)bytes_before, (unsigned long long)bytes_after, wall);
  // zero-invalid gate: nonzero invalid output is a hard failure
  if (verify.invalid != 0) {
    std::fprintf(stderr, "zero-invalid gate FAILED: %llu invalid polygon features after encoding\n",
                 (unsigned long long)verify.invalid);
    return 1;
  }
  return 0;
}

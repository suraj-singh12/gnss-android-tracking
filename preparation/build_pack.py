#!/usr/bin/env python3
"""Build one native GNSS map preparation pack (issue #97).

Reproducible recipe for all three targets: pinned conda-forge package versions
staged with hash-pinned micromamba, portable relocation-safe layout, the
project-built gnss-mvt-repair compiled against the same GEOS, per-file sha256
manifest, license inventory. No operator toolchain is ever required: the pack
is the output, this script is the build-time recipe only.

Usage:
  python3 build_pack.py --target linux-x64|windows-x64|macos-arm64 \
      --workdir <scratch> --out <packdir> [--repo <repo-root>]

The same script runs natively on each platform (Linux/macOS hosts or CI
windows-2022/macos-14 runners): there is no cross-compilation.
"""
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

# Pinned inputs (issue #97): exact package versions qualified in the #93
# geometry and Windows evidence. conda-forge immutable artifacts.
PACK_VERSION = "1.0.0"
PINNED = {
    "libgdal-core": "3.13.3",
    "osmium-tool": "1.19.1",
    "geos": "3.14.1",
    "proj": "9.9.0",
}
# compatible Command range: the pack interface is the manifest contract below
COMPAT_COMMAND_MIN = "0.0.0"  # any current Command accepting pack format 1
PACK_FORMAT = 1

MICROMAMBA_RELEASE = "2.10.0-0"
MICROMAMBA = {
    # sha256 measured against the pinned release artifacts; the Windows hash
    # is the one already qualified in the #93 smoke evidence.
    "linux-64": ("micromamba-linux-64", "701f39c06f8e6a670a0e186c8479b9e5c0502a2f0cda6cef65d83bdd2875e8bd"),
    "win-64": ("micromamba-win-64.exe", "f8b4284bd4eb1134122eaf93b3ea6c7be567e596de5829e1a6f478e34e41974a"),
    "osx-arm64": ("micromamba-osx-arm64", "c547639f5caf742236641c362746abb4a3505774753ecd08fe341643670c4c01"),
}
SUBDIR = {"linux-x64": "linux-64", "windows-x64": "win-64", "macos-arm64": "osx-arm64"}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def fetch_micromamba(subdir, dest):
    fname, want = MICROMAMBA[subdir]
    url = f"https://github.com/mamba-org/micromamba-releases/releases/download/{MICROMAMBA_RELEASE}/{fname.replace('.exe', '') if subdir != 'win-64' else 'micromamba-win-64'}"
    print("downloading", url)
    urllib.request.urlretrieve(url, dest)
    got = sha256(dest)
    if got != want:
        raise SystemExit(f"micromamba hash mismatch: got {got} want {want}")
    dest.chmod(0o755)
    return dest


def create_env(mm, envdir, workdir):
    pkgs = [f"{n}={v}" for n, v in PINNED.items()]
    run([mm, "create", "-y", "-q", "-r", workdir / "mamba", "-p", envdir,
         "-c", "conda-forge", "--override-channels"] + pkgs)
    out = subprocess.run([str(mm), "list", "-p", str(envdir), "--json"],
                         capture_output=True, text=True, check=True)
    data = json.loads(out.stdout)
    return data["packages"] if isinstance(data, dict) else data


def ldd_closure(binaries, libroot):
    """Iterative ldd closure of the given binaries, restricted to libroot."""
    keep = set()
    queue = list(binaries)
    seen = set()
    while queue:
        path = queue.pop()
        if path in seen:
            continue
        seen.add(path)
        try:
            out = subprocess.run(["ldd", str(path)], capture_output=True, text=True).stdout
        except OSError:
            continue
        for line in out.splitlines():
            m = re.search(r"=>\s*(/\S+)", line) or re.match(r"\s*(/\S+)", line)
            if not m:
                continue
            lib = os.path.normpath(m.group(1))
            if lib.startswith(os.path.normpath(str(libroot))) and os.path.isfile(lib):
                real = os.path.realpath(lib)
                if real not in keep:
                    keep.add(real)
                    queue.append(real)
    return sorted(keep)


def otool_closure(binaries, libroot):
    """macOS: iterative otool -L closure restricted to libroot."""
    keep = set()
    queue = list(binaries)
    seen = set()
    while queue:
        path = queue.pop()
        if path in seen:
            continue
        seen.add(path)
        try:
            out = subprocess.run(["otool", "-L", str(path)], capture_output=True, text=True).stdout
        except OSError:
            continue
        for line in out.splitlines()[1:]:
            m = re.match(r"\s+(\S+)\s+\(", line)
            if not m:
                continue
            lib = m.group(1)
            # conda uses @rpath entries; resolve against libroot basename
            if lib.startswith("@rpath/"):
                lib = os.path.join(libroot, lib[len("@rpath/"):])
            if str(libroot) in str(lib) and os.path.isfile(lib):
                real = os.path.realpath(lib)
                if real not in keep:
                    keep.add(real)
                    queue.append(real)
    return sorted(keep)


def compile_repair(repo, envdir, out_bin, target, compiler=None):
    src = repo / "preparation/gnss-mvt-repair/src/main.cpp"
    inc = repo / "preparation/third_party/protozero/include"
    if target == "windows-x64":
        # MSVC on the CI runner; import libs resolved by presence because
        # conda-forge naming has varied across releases.
        libdir = envdir / "Library" / "lib"
        def pick(*names):
            for n in names:
                if (libdir / n).exists():
                    return n
            raise SystemExit(f"missing import lib among {names} in {libdir}")
        cl = compiler or "cl.exe"
        run([cl, "/nologo", "/O2", "/std:c++17", "/EHsc",
             f"/I{inc}", f"/I{envdir/'Library'/'include'}",
             str(src), "/Fe:" + str(out_bin),
             "/link", f"/LIBPATH:{libdir}",
             pick("geos_c.lib"), pick("zlib.lib", "z.lib", "zdll.lib")])
    elif target == "macos-arm64":
        run([compiler or "clang++", "-O2", "-std=c++17", "-o", out_bin, src,
             f"-I{inc}", f"-I{envdir/'include'}", f"-L{envdir/'lib'}",
             "-lgeos_c", "-lz"])
    else:
        run([compiler or "g++", "-O2", "-std=c++17", "-o", out_bin, src,
             f"-I{inc}", f"-I{envdir/'include'}", f"-L{envdir/'lib'}",
             "-lgeos_c", "-lz", f"-Wl,-rpath,$ORIGIN/../lib"])


def assemble(target, envdir, repair_bin, packdir, pkg_inventory):
    if packdir.exists():
        shutil.rmtree(packdir)
    (packdir / "bin").mkdir(parents=True)
    (packdir / "share").mkdir()
    windows = target == "windows-x64"
    root = envdir / "Library" if windows else envdir
    bins = ["ogr2ogr", "ogrinfo", "osmium", "gdal_translate", "gdalwarp", "gdalinfo"]
    ext = ".exe" if windows else ""
    for b in bins:
        shutil.copy2(root / "bin" / (b + ext), packdir / "bin" / (b + ext))
    shutil.copy2(repair_bin, packdir / "bin" / ("gnss-mvt-repair" + ext))

    if windows:
        # dlls live beside the executables; the qualified Windows evidence
        # demonstrated this layout relocating cleanly.
        n = 0
        for dll in (root / "bin").glob("*.dll"):
            shutil.copy2(dll, packdir / "bin" / dll.name)
            n += 1
        print(f"copied {n} runtime dlls")
    else:
        libroot = root / "lib"
        # closure is computed on the env binaries, whose rpaths resolve into
        # the env — after copying, $ORIGIN/../lib semantics carry the pack.
        # the repair binary is staged inside env/bin so its own $ORIGIN/../lib
        # rpath resolves for the closure scan exactly as it will in the pack.
        staged_repair = root / "bin" / ("gnss-mvt-repair" + ext)
        shutil.copy2(repair_bin, staged_repair)
        closure = (otool_closure if target == "macos-arm64" else ldd_closure)(
            [root / "bin" / (b + ext) for b in bins] + [staged_repair], libroot)
        (packdir / "lib").mkdir()
        # copy preserving soname symlinks: link name -> real file
        names = set()
        for lib in closure:
            base = os.path.basename(lib)
            shutil.copy2(lib, packdir / "lib" / base)
            names.add(base)
        # recreate the soname symlink chain pointing into the pack
        for lib in list(libroot.iterdir()):
            if lib.is_symlink() and os.path.realpath(lib) in closure:
                dst = packdir / "lib" / lib.name
                if not dst.exists():
                    os.symlink(os.path.basename(os.path.realpath(lib)), dst)
        print(f"copied {len(closure)} shared libraries (+ soname links)")
    for d in ("proj", "gdal"):
        src = root / "share" / d
        if src.exists():
            shutil.copytree(src, packdir / "share" / d)
    return packdir


def license_inventory(envdir, packdir):
    """License inventory: every conda package's declared license/family from
    its conda-meta record, plus the actual license texts where packages ship
    them. GPL-compatibility review is a documented judgment, not a scan
    afterthought: the notable copyleft component is GEOS (LGPL-2.1+), linked
    dynamically as a shared library exactly as the LGPL provides."""
    out = packdir / "licenses"
    out.mkdir()
    inventory = {}
    meta = envdir / "conda-meta"
    if meta.exists():
        for rec in sorted(meta.glob("*.json")):
            try:
                d = json.loads(rec.read_text())
            except Exception:
                continue
            name = d.get("name") or rec.stem.rsplit("-", 2)[0]
            inventory[name] = {
                "license": d.get("license") or "unspecified",
                "license_family": d.get("license_family") or "",
                "texts": [],
            }
    licroot = envdir / "share" / "licenses"
    if not licroot.exists():
        licroot = envdir / "Library" / "share" / "licenses"
    if licroot.exists():
        for pkg in sorted(licroot.iterdir()):
            if not pkg.is_dir():
                continue
            texts = []
            for f in pkg.rglob("*"):
                if f.is_file():
                    rel = f"{pkg.name}/{f.relative_to(pkg)}"
                    dest = out / pkg.name / f.relative_to(pkg)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(f, dest)
                    texts.append(str(f.relative_to(pkg)))
            if texts:
                inventory.setdefault(pkg.name, {"license": "see texts", "license_family": "", "texts": []})
                inventory[pkg.name]["texts"] = sorted(texts)
    # project-built component license context: GEOS linkage is LGPL-2.1+
    # dynamically linked; protozero is BSD-2 vendored in the repo.
    return inventory


def write_manifest(packdir, target, pkg_inventory, lic_inventory):
    files = {}
    for f in sorted(packdir.rglob("*")):
        if f.is_file() and f.name != "manifest.json":
            files[str(f.relative_to(packdir)).replace(os.sep, "/")] = sha256(f)
    manifest = {
        "format": PACK_FORMAT,
        "pack": "gnss-preparation-pack",
        "version": PACK_VERSION,
        "target": target,
        "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "compatible_command_min": COMPAT_COMMAND_MIN,
        "pinned_inputs": PINNED,
        "micromamba": {"release": MICROMAMBA_RELEASE,
                        "sha256": MICROMAMBA[SUBDIR[target]][1]},
        "conda_packages": [
            {"name": p.get("name"), "version": p.get("version"),
             "build": p.get("build_string") or p.get("build"),
             "channel": p.get("channel")}
            for p in pkg_inventory
        ],
        "license_inventory": lic_inventory,
        "project_components": {
            "gnss-mvt-repair": {
                "source": "preparation/gnss-mvt-repair (this repository)",
                "links": {"geos": PINNED["geos"], "zlib": "system/conda runtime",
                           "protozero": "1.8.1 (vendored, BSD-2-Clause)"},
            }
        },
        "env_contract": {
            "GDAL_DATA": "share/gdal",
            "PROJ_DATA": "share/proj",
            "PROJ_NETWORK": "OFF",
        },
        "files": files,
    }
    (packdir / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True) + "\n")
    return manifest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", required=True, choices=["linux-x64", "windows-x64", "macos-arm64"])
    ap.add_argument("--workdir", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent)
    ap.add_argument("--compiler", default=None)
    args = ap.parse_args()

    subdir = SUBDIR[args.target]
    args.workdir.mkdir(parents=True, exist_ok=True)
    mm = fetch_micromamba(subdir, args.workdir / ("mm" + (".exe" if subdir == "win-64" else "")))
    envdir = args.workdir / "env"
    inventory = create_env(mm, envdir, args.workdir)
    ext = ".exe" if args.target == "windows-x64" else ""
    repair_bin = args.workdir / ("gnss-mvt-repair" + ext)
    compile_repair(args.repo, envdir, repair_bin, args.target, args.compiler)
    assemble(args.target, envdir, repair_bin, args.out, inventory)
    lic = license_inventory(envdir, args.out)
    manifest = write_manifest(args.out, args.target, inventory, lic)
    nfiles = len(manifest["files"])
    total = sum(f.lstat().st_size for f in args.out.rglob("*") if f.is_file() and not f.is_symlink())
    print(f"pack assembled: {nfiles} files, {total/1e6:.1f} MB unpacked at {args.out}")
    print("next: python3 preparation/smoke_pack.py --pack", args.out)


if __name__ == "__main__":
    main()

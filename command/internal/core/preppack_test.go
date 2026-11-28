package core

// Focused regression tests for preparation pack management (issue #97):
// validation honesty, corrupt/incompatible rejection preserving prior state,
// version selection with last-valid fallback, archive admission safety.

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func fakePackFile(t *testing.T, dir, rel string, content []byte) string {
	t.Helper()
	p := filepath.Join(dir, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(p), 0700); err != nil {
		t.Fatal(err)
	}
	perm := os.FileMode(0600)
	if filepath.Dir(rel) == "bin" {
		perm = 0700
	}
	if err := os.WriteFile(p, content, perm); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(content)
	return hex.EncodeToString(sum[:])
}

// writeFakePack creates a minimal structurally valid pack: three tiny
// executables plus one data file, with correct manifest hashes.
func writeFakePack(t *testing.T, dir, target, version string) {
	t.Helper()
	files := map[string]string{}
	for _, exe := range []string{"ogr2ogr", "osmium", "gnss-mvt-repair"} {
		name := exe
		if target == "windows-x64" {
			name += ".exe"
		}
		files["bin/"+name] = fakePackFile(t, dir, "bin/"+name, []byte("#!/bin/true\n"))
	}
	files["share/proj/proj.db"] = fakePackFile(t, dir, "share/proj/proj.db", []byte("projdata"))
	manifest := map[string]any{
		"format": 1, "pack": "gnss-preparation-pack", "version": version,
		"target": target, "files": files,
	}
	raw, _ := json.Marshal(manifest)
	if err := os.WriteFile(filepath.Join(dir, "manifest.json"), raw, 0600); err != nil {
		t.Fatal(err)
	}
}

func openPackStore(t *testing.T) *Store {
	t.Helper()
	dir := t.TempDir()
	s, err := Open(filepath.Join(dir, "cmd.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func TestPrepPackValidationHonest(t *testing.T) {
	dir := t.TempDir()
	pack := filepath.Join(dir, "pack")
	writeFakePack(t, pack, prepPackTarget(), "1.0.0")
	if _, err := validatePrepPack(pack); err != nil {
		t.Fatalf("valid pack rejected: %v", err)
	}
	// corrupt one byte of an executable: rejection must name corruption
	binp := filepath.Join(pack, "bin", "osmium")
	raw, _ := os.ReadFile(binp)
	raw[0] ^= 0xff
	_ = os.WriteFile(binp, raw, 0700)
	if _, err := validatePrepPack(pack); err == nil {
		t.Fatal("corrupt pack accepted")
	} else if got := err.Error(); !bytes.Contains([]byte(got), []byte("checksum mismatch")) {
		t.Fatalf("corruption not honestly disclosed: %v", err)
	}
}

func TestPrepPackRejectsForeignTarget(t *testing.T) {
	dir := t.TempDir()
	pack := filepath.Join(dir, "pack")
	other := "windows-x64"
	if prepPackTarget() == "windows-x64" {
		other = "linux-x64"
	}
	writeFakePack(t, pack, other, "1.0.0")
	if _, err := validatePrepPack(pack); err == nil {
		t.Fatal("foreign-target pack accepted")
	}
}

func TestPrepPackImportSelectAndFallback(t *testing.T) {
	s := openPackStore(t)

	buildArchive := func(version string, corrupt bool) []byte {
		var buf bytes.Buffer
		gz := gzip.NewWriter(&buf)
		tw := tar.NewWriter(gz)
		tmp := t.TempDir()
		pack := filepath.Join(tmp, "pack")
		writeFakePack(t, pack, prepPackTarget(), version)
		if corrupt {
			binp := filepath.Join(pack, "bin", "ogr2ogr")
			raw, _ := os.ReadFile(binp)
			raw = append(raw, 0x41)
			_ = os.WriteFile(binp, raw, 0700)
		}
		base := "gnss-prep-pack"
		_ = filepath.Walk(pack, func(p string, fi os.FileInfo, err error) error {
			if err != nil || fi.IsDir() {
				return nil
			}
			rel, _ := filepath.Rel(pack, p)
			raw, _ := os.ReadFile(p)
			_ = tw.WriteHeader(&tar.Header{Name: base + "/" + filepath.ToSlash(rel), Mode: int64(fi.Mode()), Size: int64(len(raw))})
			_, _ = tw.Write(raw)
			return nil
		})
		_ = tw.Close()
		_ = gz.Close()
		return buf.Bytes()
	}

	// corrupt archive: rejection preserves the empty prior state
	if _, err := s.importPrepPack(t.Context(), bytes.NewReader(buildArchive("9.9.9", true)), 0); err == nil {
		t.Fatal("corrupt pack archive admitted")
	}
	if packs := s.scanPrepPacks(); len(packs) != 0 {
		t.Fatalf("corrupt import changed state: %+v", packs)
	}

	// two valid versions: newest becomes active
	if _, err := s.importPrepPack(t.Context(), bytes.NewReader(buildArchive("1.0.0", false)), 0); err != nil {
		t.Fatalf("valid import rejected: %v", err)
	}
	if _, err := s.importPrepPack(t.Context(), bytes.NewReader(buildArchive("1.1.0", false)), 0); err != nil {
		t.Fatalf("valid import rejected: %v", err)
	}
	packs := s.scanPrepPacks()
	if len(packs) != 2 {
		t.Fatalf("expected 2 packs, got %d", len(packs))
	}
	var active string
	for _, p := range packs {
		if p.Active {
			active = p.Dir
		}
	}
	if active == "" || !bytes.Contains([]byte(active), []byte("1.1.0")) {
		t.Fatalf("newest valid version not active: %q", active)
	}

	// explicit selection of the older valid version
	root := s.prepPackRoot()
	state := prepPackState{Version: prepPackStateVersion}
	var older string
	for _, p := range packs {
		if bytes.Contains([]byte(p.Dir), []byte("1.0.0")) {
			older = p.Dir
		}
	}
	state.Selected = older
	state.Pinned = true
	raw, _ := json.Marshal(state)
	_ = os.WriteFile(filepath.Join(root, "active.json"), raw, 0600)
	for _, p := range s.scanPrepPacks() {
		if p.Dir == older && !p.Active {
			t.Fatal("recorded valid selection not honored")
		}
	}

	// last-valid fallback: corrupt the selected copy on disk, rescan
	binp := filepath.Join(root, older, "bin", "osmium")
	if prepPackTarget() == "windows-x64" {
		binp += ".exe"
	}
	raw2, _ := os.ReadFile(binp)
	raw2[0] ^= 0xff
	_ = os.WriteFile(binp, raw2, 0700)
	active = ""
	fellBack := false
	for _, p := range s.scanPrepPacks() {
		if p.Active {
			active = p.Dir
		}
	}
	raw, _ = os.ReadFile(filepath.Join(root, "active.json"))
	_ = json.Unmarshal(raw, &state)
	fellBack = state.FellBack
	if !bytes.Contains([]byte(active), []byte("1.1.0")) || !fellBack {
		t.Fatalf("last-valid fallback failed: active=%q fellBack=%v", active, fellBack)
	}
}

func TestPrepPackArchiveSlipRejected(t *testing.T) {
	s := openPackStore(t)
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	payload := []byte("x")
	_ = tw.WriteHeader(&tar.Header{Name: "../evil", Mode: 0600, Size: int64(len(payload))})
	_, _ = tw.Write(payload)
	_ = tw.Close()
	_ = gz.Close()
	if _, err := s.importPrepPack(t.Context(), bytes.NewReader(buf.Bytes()), 0); err == nil {
		t.Fatal("zip-slip archive admitted")
	}
	// nothing may have escaped into the data home
	if _, err := os.Stat(filepath.Join(filepath.Dir(s.path), "evil")); err == nil {
		t.Fatal("zip-slip wrote outside staging")
	}
}

func TestVersionLessOrdering(t *testing.T) {
	cases := [][2]string{{"1.0.0", "1.1.0"}, {"1.9.0", "1.10.0"}, {"0.9", "1.0"}}
	for _, c := range cases {
		if !versionLess(c[0], c[1]) {
			t.Errorf("versionLess(%s,%s) should hold", c[0], c[1])
		}
		if versionLess(c[1], c[0]) {
			t.Errorf("versionLess(%s,%s) should not hold", c[1], c[0])
		}
	}
	if versionLess("1.0.0", "1.0.0") {
		t.Error("equal versions must not compare less")
	}
}

func TestPrepPackImportWrongTargetRejected(t *testing.T) {
	s := openPackStore(t)
	other := "windows-x64"
	if prepPackTarget() == "windows-x64" {
		other = "linux-x64"
	}
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	tmp := t.TempDir()
	writeFakePack(t, filepath.Join(tmp, "pack"), other, "1.0.0")
	_ = filepath.Walk(filepath.Join(tmp, "pack"), func(p string, fi os.FileInfo, err error) error {
		if err != nil || fi.IsDir() {
			return nil
		}
		rel, _ := filepath.Rel(filepath.Join(tmp, "pack"), p)
		raw, _ := os.ReadFile(p)
		_ = tw.WriteHeader(&tar.Header{Name: "pack/" + filepath.ToSlash(rel), Mode: int64(fi.Mode()), Size: int64(len(raw))})
		_, _ = tw.Write(raw)
		return nil
	})
	_ = tw.Close()
	_ = gz.Close()
	if _, err := s.importPrepPack(t.Context(), bytes.NewReader(buf.Bytes()), 0); err == nil {
		t.Fatal("foreign-target pack admitted")
	} else if got := fmt.Sprint(err); !bytes.Contains([]byte(got), []byte("rejected")) {
		t.Fatalf("rejection not honest: %v", err)
	}
	if packs := s.scanPrepPacks(); len(packs) != 0 {
		t.Fatal("foreign-target import changed state")
	}
}

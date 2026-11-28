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
	"strings"
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

// sha256hex is the independently supplied digest an operator would have
// obtained from the official build for exactly these archive bytes.
func sha256hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// importOK imports archive bytes with their correct independent digest.
func importOK(t *testing.T, s *Store, archive []byte) error {
	t.Helper()
	_, err := s.importPrepPack(t.Context(), bytes.NewReader(archive), 0, sha256hex(archive))
	return err
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

	// corrupt archive: rejection preserves the empty prior state. The digest
	// is correct for the bytes — authentication passes, validation must fail.
	corruptArchive := buildArchive("9.9.9", true)
	if err := importOK(t, s, corruptArchive); err == nil {
		t.Fatal("corrupt pack archive admitted")
	}
	if packs := s.scanPrepPacks(); len(packs) != 0 {
		t.Fatalf("corrupt import changed state: %+v", packs)
	}

	// two valid versions: newest becomes active
	if err := importOK(t, s, buildArchive("1.0.0", false)); err != nil {
		t.Fatalf("valid import rejected: %v", err)
	}
	if err := importOK(t, s, buildArchive("1.1.0", false)); err != nil {
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
	if err := importOK(t, s, buf.Bytes()); err == nil {
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

// TestPrepPackImportDigestAuthentication pins the archive-authentication
// contract: an independently supplied whole-archive sha256 is mandatory, and
// only exact byte equality with that digest admits an archive to extraction.
// The critical case is the coherent substitution attack — a modified
// executable shipped with a coherently rewritten internal manifest passes
// every validation check, so the independent digest is the only barrier.
func TestPrepPackImportDigestAuthentication(t *testing.T) {
	buildArchive := func(version string, mutate bool) []byte {
		var buf bytes.Buffer
		gz := gzip.NewWriter(&buf)
		tw := tar.NewWriter(gz)
		tmp := t.TempDir()
		pack := filepath.Join(tmp, "pack")
		writeFakePack(t, pack, prepPackTarget(), version)
		if mutate {
			// the attacker modifies an executable AND rewrites the manifest
			// with the matching new hash: internally self-consistent.
			binp := filepath.Join(pack, "bin", "gnss-mvt-repair")
			if prepPackTarget() == "windows-x64" {
				binp += ".exe"
			}
			evil := []byte("#!/bin/evil\n")
			_ = os.WriteFile(binp, evil, 0700)
			manifestPath := filepath.Join(pack, "manifest.json")
			raw, _ := os.ReadFile(manifestPath)
			var m map[string]any
			_ = json.Unmarshal(raw, &m)
			files := m["files"].(map[string]any)
			sum := sha256.Sum256(evil)
			key := "bin/gnss-mvt-repair"
			if prepPackTarget() == "windows-x64" {
				key += ".exe"
			}
			files[key] = hex.EncodeToString(sum[:])
			out, _ := json.Marshal(m)
			_ = os.WriteFile(manifestPath, out, 0600)
		}
		_ = filepath.Walk(pack, func(p string, fi os.FileInfo, err error) error {
			if err != nil || fi.IsDir() {
				return nil
			}
			rel, _ := filepath.Rel(pack, p)
			raw, _ := os.ReadFile(p)
			_ = tw.WriteHeader(&tar.Header{Name: "pack/" + filepath.ToSlash(rel), Mode: int64(fi.Mode()), Size: int64(len(raw))})
			_, _ = tw.Write(raw)
			return nil
		})
		_ = tw.Close()
		_ = gz.Close()
		return buf.Bytes()
	}
	genuine := buildArchive("1.0.0", false)
	// the attack uses a distinct version so its admission in case 6 is a real
	// extraction and validation, not the already-present identity shortcut.
	attack := buildArchive("1.0.1", true)
	if sha256hex(genuine) == sha256hex(attack) {
		t.Fatal("test construction flaw: archives must differ")
	}

	// 1. missing digest: rejected before anything is read
	s := openPackStore(t)
	if _, err := s.importPrepPack(t.Context(), bytes.NewReader(genuine), 0, ""); err == nil {
		t.Fatal("missing digest admitted")
	}
	// 2. malformed digests: wrong length, non-hex characters
	for _, bad := range []string{"abcd", strings.Repeat("g", 64), strings.Repeat("a", 63), strings.Repeat("a", 65)} {
		if _, err := s.importPrepPack(t.Context(), bytes.NewReader(genuine), 0, bad); err == nil {
			t.Fatalf("malformed digest %q admitted", bad)
		}
	}
	// 3. well-formed but incorrect digest: genuine archive rejected, state untouched
	wrong := strings.Repeat("0", 64)
	if wrong == sha256hex(genuine) {
		wrong = strings.Repeat("1", 64)
	}
	if _, err := s.importPrepPack(t.Context(), bytes.NewReader(genuine), 0, wrong); err == nil {
		t.Fatal("incorrect digest admitted")
	} else if got := err.Error(); !bytes.Contains([]byte(got), []byte("digest mismatch")) {
		t.Fatalf("mismatch not honestly disclosed: %v", err)
	}
	if packs := s.scanPrepPacks(); len(packs) != 0 {
		t.Fatal("rejected import disturbed pack state")
	}
	// 4. the substitution attack: coherently-rewritten internal manifest,
	// presented with the genuine archive's digest — must be rejected even
	// though the archive would pass every validation check on its own
	if _, err := s.importPrepPack(t.Context(), bytes.NewReader(attack), 0, sha256hex(genuine)); err == nil {
		t.Fatal("coherently-rewritten substitute archive admitted under genuine digest")
	}
	if packs := s.scanPrepPacks(); len(packs) != 0 {
		t.Fatal("attack import disturbed pack state")
	}
	// 5. correct digest: the genuine archive imports cleanly
	if _, err := s.importPrepPack(t.Context(), bytes.NewReader(genuine), 0, sha256hex(genuine)); err != nil {
		t.Fatalf("correctly authenticated genuine archive rejected: %v", err)
	}
	if packs := s.scanPrepPacks(); len(packs) != 1 {
		t.Fatalf("authenticated import not published: %d packs", len(packs))
	}
	// 6. and the attack archive with its OWN correct digest is admitted —
	// proving the independent digest, not internal consistency, is the anchor
	if _, err := s.importPrepPack(t.Context(), bytes.NewReader(attack), 0, sha256hex(attack)); err != nil {
		t.Fatalf("self-consistent archive with correct independent digest rejected: %v", err)
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
	if err := importOK(t, s, buf.Bytes()); err == nil {
		t.Fatal("foreign-target pack admitted")
	} else if got := fmt.Sprint(err); !bytes.Contains([]byte(got), []byte("rejected")) {
		t.Fatalf("rejection not honest: %v", err)
	}
	if packs := s.scanPrepPacks(); len(packs) != 0 {
		t.Fatal("foreign-target import changed state")
	}
}

package core

// Native preparation pack management (issue #97). The pack is optional:
// viewing and importing maps never require it, and its absence disables only
// enhanced preparation. Detection, offline import, validation, version
// selection and last-valid fallback live here; no operator toolchain, PATH
// configuration or network access is ever involved at runtime.
//
// Trust model: a pack is admitted only after every file matches the sha256
// pinned in its own manifest AND the manifest itself satisfies the format,
// target and compatibility contract. Corrupt or incompatible packs are
// rejected preserving the prior state exactly. The manifest is self-consistent
// evidence, not authentication of origin — operator-controlled offline import
// is the trust root for this review-only scope, exactly like Format 1/2 map
// package imports.

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

const (
	prepPackFormat       = 1
	prepPackMaxArchive   = 512 << 20 // compressed admission ceiling
	prepPackMaxExpanded  = 2 << 30   // unpacked admission ceiling
	prepPackMaxFile      = 512 << 20
	prepPackRequiredExe  = 3 // ogr2ogr, osmium, gnss-mvt-repair
	prepPackStateVersion = 1
)

type prepPackManifest struct {
	Format     int               `json:"format"`
	Pack       string            `json:"pack"`
	Version    string            `json:"version"`
	Target     string            `json:"target"`
	Files      map[string]string `json:"files"`
	EnvContract map[string]string `json:"env_contract"`
}

type prepPackInfo struct {
	Dir     string `json:"dir"`
	Version string `json:"version"`
	Target  string `json:"target"`
	Valid   bool   `json:"valid"`
	Reason  string `json:"reason,omitempty"`
	Files   int    `json:"files"`
	Bytes   int64  `json:"bytes"`
	Active  bool   `json:"active"`
}

type prepPackState struct {
	Version  int    `json:"version"`
	Selected string `json:"selected"`
	Pinned   bool   `json:"pinned,omitempty"` // explicit operator selection, not auto-resolution
	FellBack bool   `json:"fell_back,omitempty"`
	Reason   string `json:"reason,omitempty"`
}

func prepPackTarget() string {
	if runtime.GOOS == "linux" && runtime.GOARCH == "amd64" {
		return "linux-x64"
	}
	if runtime.GOOS == "windows" && runtime.GOARCH == "amd64" {
		return "windows-x64"
	}
	if runtime.GOOS == "darwin" && runtime.GOARCH == "arm64" {
		return "macos-arm64"
	}
	return runtime.GOOS + "-" + runtime.GOARCH
}

func (s *Store) prepPackRoot() string {
	return filepath.Join(filepath.Dir(s.path), "packs")
}

// validatePrepPack inspects one extracted pack directory: manifest contract,
// target match, required executables, and sha256 of every manifest entry.
// Error strings are operator-facing honesty, never internal detail.
func validatePrepPack(dir string) (prepPackManifest, error) {
	var m prepPackManifest
	raw, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if err != nil {
		return m, errors.New("manifest.json missing or unreadable")
	}
	if len(raw) > 4<<20 {
		return m, errors.New("manifest exceeds bounded size")
	}
	if err := json.Unmarshal(raw, &m); err != nil {
		return m, fmt.Errorf("manifest unreadable: %v", err)
	}
	if m.Pack != "gnss-preparation-pack" {
		return m, errors.New("not a GNSS preparation pack")
	}
	if m.Format != prepPackFormat {
		return m, fmt.Errorf("unsupported pack format %d", m.Format)
	}
	if m.Target != prepPackTarget() {
		return m, fmt.Errorf("pack targets %s but this Command runs on %s", m.Target, prepPackTarget())
	}
	if len(m.Files) == 0 {
		return m, errors.New("manifest carries no file evidence")
	}
	exe := map[string]bool{"ogr2ogr": false, "osmium": false, "gnss-mvt-repair": false}
	suffix := ""
	if m.Target == "windows-x64" {
		suffix = ".exe"
	}
	for rel, want := range m.Files {
		if strings.Contains(rel, "\\") || strings.HasPrefix(rel, "/") || strings.Contains(rel, "..") {
			return m, fmt.Errorf("manifest path %q is unsafe", rel)
		}
		base := strings.TrimSuffix(filepath.Base(rel), suffix)
		if strings.HasPrefix(rel, "bin/") {
			if _, ok := exe[base]; ok {
				exe[base] = true
			}
		}
		f, err := os.Open(filepath.Join(dir, filepath.FromSlash(rel)))
		if err != nil {
			return m, fmt.Errorf("missing file %s", rel)
		}
		h := sha256.New()
		_, copyErr := io.Copy(h, f)
		_ = f.Close()
		if copyErr != nil {
			return m, fmt.Errorf("unreadable file %s", rel)
		}
		if hex.EncodeToString(h.Sum(nil)) != want {
			return m, fmt.Errorf("checksum mismatch for %s — pack is corrupt or tampered", rel)
		}
	}
	missing := []string{}
	for b, ok := range exe {
		if !ok {
			missing = append(missing, b+suffix)
		}
	}
	if len(missing) > 0 {
		return m, fmt.Errorf("required executables missing: %s", strings.Join(missing, ", "))
	}
	return m, nil
}

// ScanPrepPacks is the exported startup/entry point for pack detection.
func (s *Store) ScanPrepPacks() []prepPackInfo { return s.scanPrepPacks() }

// scanPrepPacks detects extracted packs, validates each, and resolves the
// active selection with last-valid fallback: the recorded selection is kept
// while valid; when it is absent or corrupt the newest other valid version is
// selected and the fallback is recorded honestly. Selection is atomic via
// write-temp-then-rename.
func (s *Store) scanPrepPacks() []prepPackInfo {
	root := s.prepPackRoot()
	entries, _ := os.ReadDir(root)
	var packs []prepPackInfo
	for _, e := range entries {
		if !e.IsDir() || !strings.HasPrefix(e.Name(), "gnss-prep-pack-") {
			continue
		}
		dir := filepath.Join(root, e.Name())
		info := prepPackInfo{Dir: e.Name()}
		m, err := validatePrepPack(dir)
		if err != nil {
			info.Reason = err.Error()
		} else {
			info.Valid = true
			info.Version = m.Version
			info.Target = m.Target
			info.Files = len(m.Files)
		}
		packs = append(packs, info)
	}
	sort.Slice(packs, func(i, j int) bool { return packs[i].Dir < packs[j].Dir })

	state := prepPackState{Version: prepPackStateVersion}
	if raw, err := os.ReadFile(filepath.Join(root, "active.json")); err == nil {
		_ = json.Unmarshal(raw, &state)
	}
	validIdx := map[string]int{}
	for i, p := range packs {
		if p.Valid {
			validIdx[p.Dir] = i
		}
	}
	selected := ""
	// An explicit operator selection is honored while valid; automatic
	// resolution always prefers the newest valid version so importing a newer
	// pack takes effect without manual intervention.
	if i, ok := validIdx[state.Selected]; ok && state.Pinned {
		selected = packs[i].Dir
	} else {
		// newest valid version wins; dir names embed version and sort cleanly
		best := -1
		for i, p := range packs {
			if p.Valid && (best < 0 || versionLess(packs[best].Version, p.Version) ||
				(packs[best].Version == p.Version && packs[best].Dir < p.Dir)) {
				best = i
			}
		}
		if best >= 0 {
			selected = packs[best].Dir
			if state.Selected != "" {
				state.FellBack = true
				if state.Reason == "" {
					state.Reason = "recorded selection missing or corrupt; fell back to newest valid pack"
				}
			}
		} else {
			selected = ""
		}
	}
	changed := state.Selected != selected
	state.Selected = selected
	if changed || state.Selected != "" {
		if raw, err := json.Marshal(state); err == nil {
			tmp := filepath.Join(root, "active.json.tmp")
			if os.MkdirAll(root, 0700) == nil {
				if os.WriteFile(tmp, raw, 0600) == nil {
					_ = os.Rename(tmp, filepath.Join(root, "active.json"))
				}
			}
		}
	}
	for i := range packs {
		packs[i].Active = packs[i].Dir == selected && selected != ""
		if packs[i].Valid {
			packs[i].Bytes = dirSize(filepath.Join(root, packs[i].Dir))
		}
	}
	return packs
}

func versionLess(a, b string) bool {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(pa) || i < len(pb); i++ {
		var x, y string
		if i < len(pa) {
			x = pa[i]
		}
		if i < len(pb) {
			y = pb[i]
		}
		if x == y {
			continue
		}
		nx, ny := len(x), len(y)
		numx, numy := true, true
		for _, c := range x {
			if c < '0' || c > '9' {
				numx = false
			}
		}
		for _, c := range y {
			if c < '0' || c > '9' {
				numy = false
			}
		}
		if numx && numy {
			if nx != ny {
				return nx < ny
			}
		}
		return x < y
	}
	return false
}

func dirSize(dir string) int64 {
	var total int64
	_ = filepath.Walk(dir, func(_ string, fi os.FileInfo, err error) error {
		if err == nil && fi.Mode().IsRegular() {
			total += fi.Size()
		}
		return nil
	})
	return total
}

// activePrepPack reports the currently selected valid pack, or nil. Enhanced
// preparation stages resolve executables beneath Dir; no PATH manipulation is
// ever required.
// ActivePrepPackSummary is the one-line startup surface: the active pack
// identity or "" when none is valid. Detection itself never fails the launch.
func (s *Store) ActivePrepPackSummary() string {
	defer func() { _ = recover() }()
	for _, p := range s.scanPrepPacks() {
		if p.Active {
			return p.Dir
		}
	}
	return ""
}

func (s *Store) activePrepPack() *prepPackInfo {
	for _, p := range s.scanPrepPacks() {
		if p.Active {
			p := p
			return &p
		}
	}
	return nil
}

// ---- offline import ----

type unpackEntry struct {
	rel   string
	mode  os.FileMode
	link  string // non-empty for symlinks
	size  int64
	write func(w io.Writer) error
	open  func() (io.ReadCloser, error)
}

// admitPrepPackArchive streams the archive once into memory-bounded staging on
// disk. Zip-slip, absolute paths, symlinks escaping the pack, device entries
// and over-capacity admission are all rejected before anything is published.
func admitPrepPackArchive(src io.Reader, archiveSize int64, staging string) error {
	if archiveSize > prepPackMaxArchive {
		return fmt.Errorf("archive exceeds bounded admission size")
	}
	if err := os.MkdirAll(staging, 0700); err != nil {
		return err
	}
	var total int64
	entries := 0
	admit := func(rel string, mode os.FileMode, link string, size int64, open func() (io.ReadCloser, error)) error {
		entries++
		if entries > 20000 {
			return errors.New("archive carries unreasonable entry count")
		}
		if size > prepPackMaxFile {
			return fmt.Errorf("entry %s exceeds bounded file size", rel)
		}
		total += size
		if total > prepPackMaxExpanded {
			return errors.New("archive exceeds bounded expanded admission")
		}
		if rel == "" || strings.HasPrefix(rel, "/") || strings.Contains(rel, "\\") {
			return fmt.Errorf("unsafe archive path %q", rel)
		}
		clean := filepath.Clean(filepath.FromSlash(rel))
		if strings.HasPrefix(clean, "..") || filepath.IsAbs(clean) {
			return fmt.Errorf("archive path %q escapes the pack", rel)
		}
		dst := filepath.Join(staging, clean)
		if link != "" {
			// symlink admission: relative, non-escaping targets only
			if filepath.IsAbs(link) || strings.Contains(link, "..") {
				return fmt.Errorf("symlink %s escapes the pack", rel)
			}
			target := filepath.Clean(filepath.Join(filepath.Dir(dst), filepath.FromSlash(link)))
			relTarget, rerr := filepath.Rel(staging, target)
			if rerr != nil || strings.HasPrefix(relTarget, "..") || filepath.IsAbs(relTarget) {
				return fmt.Errorf("symlink %s escapes the pack", rel)
			}
			if err := os.MkdirAll(filepath.Dir(dst), 0700); err != nil {
				return err
			}
			return os.Symlink(link, dst)
		}
		if err := os.MkdirAll(filepath.Dir(dst), 0700); err != nil {
			return err
		}
		rc, err := open()
		if err != nil {
			return err
		}
		defer rc.Close()
		w, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode&0111|0600)
		if err != nil {
			return err
		}
		_, copyErr := io.Copy(w, rc)
		cerr := w.Close()
		if copyErr != nil {
			return copyErr
		}
		return cerr
	}

	// format sniffing: zip magic PK\x03\x04, gzip magic 1f 8b
	buf := make([]byte, 4)
	if _, err := io.ReadFull(src, buf); err != nil {
		return errors.New("archive unreadable")
	}
	head := io.MultiReader(strings.NewReader(string(buf)), src)
	if buf[0] == 'P' && buf[1] == 'K' {
		// zip needs a ReaderAt — buffer to a temp file (bounded by admission cap)
		tmp, err := os.CreateTemp("", "gnss-pack-zip-*")
		if err != nil {
			return err
		}
		defer os.Remove(tmp.Name())
		defer tmp.Close()
		n, err := io.Copy(tmp, head)
		if err != nil {
			return err
		}
		if n > prepPackMaxArchive {
			return errors.New("archive exceeds bounded admission size")
		}
		zr, err := zip.NewReader(tmp, n)
		if err != nil {
			return errors.New("not a readable zip archive")
		}
		for _, zf := range zr.File {
			name := strings.TrimPrefix(filepath.ToSlash(zf.Name), "./")
			if zf.FileInfo().IsDir() {
				continue
			}
			mode := zf.Mode()
			link := ""
			if mode&os.ModeSymlink != 0 {
				rc, err := zf.Open()
				if err != nil {
					return err
				}
				b, _ := io.ReadAll(io.LimitReader(rc, 4096))
				rc.Close()
				link = string(b)
			}
			zfCopy := zf
			if err := admit(name, mode, link, int64(zf.UncompressedSize64), func() (io.ReadCloser, error) {
				return zfCopy.Open()
			}); err != nil {
				return err
			}
		}
		return nil
	}
	if buf[0] == 0x1f && buf[1] == 0x8b {
		gz, err := gzip.NewReader(head)
		if err != nil {
			return errors.New("not a readable gzip archive")
		}
		defer gz.Close()
		tr := tar.NewReader(gz)
		for {
			hdr, err := tr.Next()
			if err == io.EOF {
				break
			}
			if err != nil {
				return errors.New("corrupt tar archive")
			}
			name := strings.TrimPrefix(filepath.ToSlash(hdr.Name), "./")
			switch hdr.Typeflag {
			case tar.TypeDir:
				continue
			case tar.TypeReg, tar.TypeRegA:
				// admit() consumes the opener inline and in order, so the
				// streaming tar reader can be passed straight through with a
				// per-entry bound — large libraries never touch memory.
				size := hdr.Size
				if err := admit(name, os.FileMode(hdr.Mode), "", size, func() (io.ReadCloser, error) {
					return io.NopCloser(io.LimitReader(tr, size)), nil
				}); err != nil {
					return err
				}
			case tar.TypeSymlink:
				if err := admit(name, 0, hdr.Linkname, 0, func() (io.ReadCloser, error) {
					return io.NopCloser(strings.NewReader("")), nil
				}); err != nil {
					return err
				}
			default:
				return fmt.Errorf("unsupported archive entry type for %s", name)
			}
		}
		return nil
	}
	return errors.New("unrecognized archive format — expected .tar.gz or .zip")
}

// importPrepPack stages, validates and atomically publishes one pack archive.
// The archive may carry the pack beneath a single top-level directory or at
// its root. Corrupt or incompatible input preserves the prior state exactly:
// nothing is published, no selection changes.
func (s *Store) importPrepPack(ctx context.Context, src io.Reader, size int64) (prepPackInfo, error) {
	root := s.prepPackRoot()
	if err := os.MkdirAll(root, 0700); err != nil {
		return prepPackInfo{}, err
	}
	staging, err := os.MkdirTemp(root, ".staging-*")
	if err != nil {
		return prepPackInfo{}, err
	}
	defer os.RemoveAll(staging)
	if err := admitPrepPackArchive(src, size, staging); err != nil {
		return prepPackInfo{}, fmt.Errorf("pack rejected: %v", err)
	}
	// locate the pack root: manifest at staging root or beneath exactly one
	// top-level directory.
	packDir := staging
	if _, err := os.Stat(filepath.Join(staging, "manifest.json")); err != nil {
		entries, _ := os.ReadDir(staging)
		var dirs []os.DirEntry
		for _, e := range entries {
			if e.IsDir() {
				dirs = append(dirs, e)
			}
		}
		if len(dirs) != 1 {
			return prepPackInfo{}, errors.New("pack rejected: manifest.json not found at archive root or single top-level directory")
		}
		packDir = filepath.Join(staging, dirs[0].Name())
	}
	m, err := validatePrepPack(packDir)
	if err != nil {
		return prepPackInfo{}, fmt.Errorf("pack rejected: %v", err)
	}
	sha := sha256.Sum256([]byte(m.Target + "/" + m.Version))
	name := fmt.Sprintf("gnss-prep-pack-%s-%s-%s", m.Target, m.Version, hex.EncodeToString(sha[:])[:8])
	final := filepath.Join(root, name)
	if _, err := os.Stat(final); err == nil {
		// identical identity already imported: revalidate the existing copy
		// honestly rather than replacing beneath a running selection.
		if verr := func() error { _, e := validatePrepPack(final); return e }(); verr == nil {
			return prepPackInfo{Dir: name, Version: m.Version, Target: m.Target, Valid: true, Reason: "already present"}, nil
		}
		_ = os.RemoveAll(final)
	}
	if err := os.Rename(packDir, final); err != nil {
		return prepPackInfo{}, fmt.Errorf("publish failed: %v", err)
	}
	info := prepPackInfo{Dir: name, Version: m.Version, Target: m.Target, Valid: true, Files: len(m.Files)}
	// selection resolution (including fallback honesty) is shared with startup
	packs := s.scanPrepPacks()
	for _, p := range packs {
		if p.Dir == name {
			info.Active = p.Active
		}
	}
	return info, nil
}

// ---- HTTP surface ----

func (s *Store) prepPackHTTP(w http.ResponseWriter, r *http.Request) bool {
	switch r.URL.Path {
	case "/local/preparation-pack":
		if r.Method != "GET" {
			failure(w, 405, "method_not_allowed", errors.New("unsupported method"))
			return true
		}
		packs := s.scanPrepPacks()
		var active *prepPackInfo
		for i := range packs {
			if packs[i].Active {
				active = &packs[i]
			}
		}
		respond(w, 200, map[string]any{
			"target": prepPackTarget(),
			"active": active,
			"packs":  packs,
			"note":   "the pack is optional: viewing and importing maps never require it",
		})
		return true
	case "/local/preparation-pack-import":
		if r.Method != "POST" {
			failure(w, 405, "method_not_allowed", errors.New("unsupported method"))
			return true
		}
		if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
			failure(w, 403, "forbidden", errors.New("same-origin controls only"))
			return true
		}
		size := r.ContentLength
		if size < 0 {
			size = 0
		}
		if size > prepPackMaxArchive {
			failure(w, 413, "too_large", fmt.Errorf("archive exceeds bounded admission size"))
			return true
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
		defer cancel()
		info, err := s.importPrepPack(ctx, r.Body, size)
		if err != nil {
			failure(w, 422, "pack_rejected", err)
			return true
		}
		respond(w, 200, info)
		return true
	case "/local/preparation-pack-select":
		if r.Method != "POST" {
			failure(w, 405, "method_not_allowed", errors.New("unsupported method"))
			return true
		}
		if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
			failure(w, 403, "forbidden", errors.New("same-origin controls only"))
			return true
		}
		var req struct {
			Dir string `json:"dir"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req); err != nil || req.Dir == "" {
			failure(w, 400, "invalid_request", errors.New("expected {\"dir\": \"<pack-dir>\"}"))
			return true
		}
		if strings.Contains(req.Dir, "/") || strings.Contains(req.Dir, "\\") || strings.Contains(req.Dir, "..") {
			failure(w, 400, "invalid_request", errors.New("dir must be a pack directory name"))
			return true
		}
		root := s.prepPackRoot()
		if _, err := validatePrepPack(filepath.Join(root, req.Dir)); err != nil {
			failure(w, 422, "pack_rejected", fmt.Errorf("selection refused: %v", err))
			return true
		}
		state := prepPackState{Version: prepPackStateVersion, Selected: req.Dir, Pinned: true}
		raw, _ := json.Marshal(state)
		tmp := filepath.Join(root, "active.json.tmp")
		if err := os.WriteFile(tmp, raw, 0600); err != nil {
			failure(w, 500, "write_failed", err)
			return true
		}
		if err := os.Rename(tmp, filepath.Join(root, "active.json")); err != nil {
			failure(w, 500, "write_failed", err)
			return true
		}
		respond(w, 200, map[string]any{"selected": req.Dir})
		return true
	}
	return false
}

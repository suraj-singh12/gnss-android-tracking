package core

import (
	"github.com/ncruces/go-sqlite3/util/vfsutil"
	"github.com/ncruces/go-sqlite3/vfs"
	"net/url"
	"os"
	"path/filepath"
)

// The embedded OS VFS creates journals with 0666. Precreate privately on every
// open, including after checkpoints/reconnects. Never open/close existing SHM:
// doing so would release POSIX locks held by another SQLite connection.
type privateVFS struct{ vfs.VFS }

func init() { vfs.Register("gnss-private", privateVFS{vfs.Find("")}) }
func privateFile(path string) error {
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE|os.O_EXCL, 0600)
	if err == nil {
		err = f.Close()
	} else if os.IsExist(err) {
		err = nil
	}
	if err != nil {
		return err
	}
	return os.Chmod(path, 0600)
}
func (p privateVFS) OpenFilename(name *vfs.Filename, flags vfs.OpenFlag) (vfs.File, vfs.OpenFlag, error) {
	if name != nil && flags&vfs.OPEN_CREATE != 0 {
		if err := privateFile(name.String()); err != nil {
			return nil, flags, err
		}
	}
	if name != nil && flags&vfs.OPEN_MAIN_DB != 0 && flags&vfs.OPEN_READWRITE != 0 {
		if err := privateFile(name.String() + "-shm"); err != nil {
			return nil, flags, err
		}
	}
	return vfsutil.WrapOpenFilename(p.VFS, name, flags)
}
func privateDSN(path string) (string, error) {
	if path == ":memory:" {
		return path, nil
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	u := url.URL{Scheme: "file", Path: filepath.ToSlash(absolute), RawQuery: "vfs=gnss-private"}
	return u.String(), nil
}

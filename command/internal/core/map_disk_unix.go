//go:build linux || darwin

package core

import (
	"fmt"
	"golang.org/x/sys/unix"
	"path/filepath"
)

func mapDiskReserve(path string, bytes uint64) error {
	if path == ":memory:" {
		return nil
	}
	var stat unix.Statfs_t
	if err := unix.Statfs(filepath.Dir(path), &stat); err != nil {
		return err
	}
	free := uint64(stat.Bavail) * uint64(stat.Bsize)
	if free < bytes {
		return fmt.Errorf("insufficient disk: %d bytes available; %d bytes required including 128 MiB reserve", free, bytes)
	}
	return nil
}

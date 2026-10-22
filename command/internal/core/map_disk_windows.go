package core

import (
	"fmt"
	"golang.org/x/sys/windows"
	"path/filepath"
)

func mapDiskReserve(path string, bytes uint64) error {
	if path == ":memory:" {
		return nil
	}
	name, err := windows.UTF16PtrFromString(filepath.Dir(path))
	if err != nil {
		return err
	}
	var free uint64
	if err = windows.GetDiskFreeSpaceEx(name, &free, nil, nil); err != nil {
		return err
	}
	if free < bytes {
		return fmt.Errorf("insufficient disk: %d bytes available; %d bytes required including 128 MiB reserve", free, bytes)
	}
	return nil
}

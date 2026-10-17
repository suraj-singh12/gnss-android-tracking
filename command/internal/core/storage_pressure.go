package core

import (
	"context"
	"os"
	"time"
)

// This budget protects receipt availability when a reader prevents WAL reset.
// Urgent individual envelopes remain eligible. It is not a disk-capacity promise:
// SQLITE_FULL is still an error and never earns an ACK.
const historyWALBudget = 128 * 1024 * 1024

func (s *Store) historyStoragePressure(ctx context.Context) bool {
	if s.path == ":memory:" {
		return false
	}
	budget := s.walBudget
	if budget == 0 {
		budget = historyWALBudget
	}
	info, err := os.Stat(s.path + "-wal")
	if err != nil || info.Size() < budget {
		return false
	}
	if !s.mu.TryLock() {
		return true
	}
	defer s.mu.Unlock()
	ctx, cancel := context.WithTimeout(ctx, 250*time.Millisecond)
	defer cancel()
	// Never wait behind a long reader while holding the writer admission lock.
	if _, err = s.db.ExecContext(ctx, "PRAGMA busy_timeout=0"); err != nil {
		return true
	}
	defer s.db.Exec("PRAGMA busy_timeout=5000")
	var busy, frames, checkpointed int
	err = s.db.QueryRowContext(ctx, "PRAGMA wal_checkpoint(TRUNCATE)").Scan(&busy, &frames, &checkpointed)
	return err != nil || busy != 0
}

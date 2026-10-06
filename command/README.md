# Command boundary

Future Issue #3 implements Go core + SQLite + bundled local web dashboard here.
The same source must run on macOS, Windows and Linux. Choose a SQLite integration
that supports those targets without a separately installed database or runtime;
prove the build matrix when selecting it. Use portable paths and Go networking,
not OS-specific launchers, native macOS frameworks, shell services or Docker.

Keep receiver/transactional raw store, quality/track derivation, live state,
recording lifecycle and dashboard as explicit responsibilities inside one small
application; do not create microservices. Depend only on
[Protocol v1](../protocol/protocol-v1.md) across the Android boundary.
Test independently with the [phone simulator contract](../test-tools/phone-simulator/README.md).
[Architecture](../docs/architecture.md) freezes chronology, segmentation and distance.
No server, SQLite schema or dashboard is implemented in Issue #1.

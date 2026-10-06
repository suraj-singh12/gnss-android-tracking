# Android boundary

Future Issue #2 implements a Kotlin Android application here. Use a foreground
tracking service, platform GNSS/location APIs and Room/SQLite local durability.
Persist immutable protocol messages and sequence allocation atomically before
sending. Keep latest fix, local reporting settings, applied remote configuration
and the durable outbox distinct. SOS later enters the same store/sender path.

Implement only against [Protocol v1](../protocol/protocol-v1.md), never Command
storage/UI internals. Test independently with the
[mock receiver contract](../test-tools/mock-receiver/README.md).
[Architecture](../docs/architecture.md) defines ownership and sampling boundaries.
No Android project, dependencies or product features are scaffolded in Issue #1.

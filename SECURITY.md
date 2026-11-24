# Security policy

## Supported versions

| Version | Supported |
| --- | --- |
| 1.1.x (pre-release) | Yes |
| 1.0.x and earlier | No |

## Reporting a vulnerability

Report vulnerabilities privately through GitHub:
**Security → Report a vulnerability** on this repository. Do not open a public issue.
Include the affected component (Android, Command or protocol), version or commit,
steps to reproduce and impact. You will receive an acknowledgement, and fixes are
released as new versions.

## Deployment model

- Phones send to Command over plain HTTP (port 8080) on a trusted, isolated field LAN.
  Protocol v1 does not supply transport encryption; do not expose port 8080 to the
  internet or untrusted networks.
- The Command dashboard listens on `127.0.0.1:8081` by default.
- Command → Android instructions are authenticated after enrollment; see
  [Protocol v1](protocol/protocol-v1.md).
- Release binaries are unsigned (Command) and debug-signed (Android). Verify downloads
  with the published `SHA256SUMS`.

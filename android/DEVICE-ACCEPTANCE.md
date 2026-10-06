# Real-device acceptance checklist

Not executed in the cloud. Record phone/OEM, Android version, app commit, test
receiver address, battery settings, timestamps and outcomes for every run.
Use a trusted isolated Wi-Fi LAN, disable mobile data/SIM if available, and use
an outdoor GNSS view. Run the mock with an explicit LAN bind and capture requests.
Mock receipt means test-memory storage only, never production durability.

- Deny precise location; verify a clear explanation and no tracking start. Grant
  precise location and Start Tracking from the visible app. Test notification
  denial/grant on Android 13+. Confirm persistent location foreground notification.
- Verify fix, accuracy and increasing observation timestamps with honest nullable
  fields. Disable location, obstruct GNSS and wait over 30 seconds: disabled/no-fix/
  stale state must not claim a fresh position. Re-enable and verify recovery.
- Run **30+ minutes** initially, including screen off and locked screen. Verify
  continuous GNSS observation/reporting at 10 seconds, no callback-per-packet flood,
  and field battery consumption. Repeat on API 26/28, 31/33 and 34/35+ where available.
- Remove Wi-Fi for several minutes while moving. Verify GNSS/report snapshots keep
  accumulating locally. Restore Wi-Fi with no internet; inspect current snapshot
  before ascending-sequence backlog and newly due reports interleaving with drain.
- Stop/restart the receiver. Test transient 503/429 and drop-after-store. Verify
  exact unchanged retry identity/content, duplicate ACK acceptance, one mock row,
  and last ACK separate from Wi-Fi attachment. Test identity/protocol errors remain
  saved and visible without rapid retries. Correct/re-enroll receiver explicitly.
- Apply override 30 seconds, edit local to 20 while override active, then explicitly
  clear at a higher version. Verify effective 30 then 20, captured config echoes,
  and immutable old snapshots. Replay older/equal config; test equal conflict and
  different authority errors. Re-enroll only via the deliberate UI action.
- Reopen Activity while service runs. Verify no duplicate service, retained settings,
  queue, override, and identity. Change Party labels: identity must remain stable.
- Kill the process without force-stop where testable. Verify permitted sticky
  recreation uses retained identity/sequence/config/outbox and current-first
  recovery. Force-stop/reboot: explicitly Start Tracking again; no promise of auto
  startup. Stop Tracking: deliberate confirmation and retained pending messages.
- Exercise low storage safely on a test device: explicit save failure, no silent
  pending-message eviction or sending an uncommitted packet.
- Repeat screen-off tests with default and OEM battery-management settings. Document
  any required operator battery-optimization setting. Doze/OEM kill behavior remains
  a hardware acceptance item; the app does not bypass platform restrictions.

Longer four-hour runs and full Android/Command integration belong to later field
hardening, but do not treat the core as field-accepted until these initial checks pass.

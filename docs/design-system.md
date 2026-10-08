# Shared operational design contract

Professional, simple, restrained. Share terminology and semantic meaning between
Android and Command, while using native Android and desktop/web conventions.
This is a UI contract, not polished screens or a pixel-identical theme.

## Status and alerts

| Meaning | Treatment | Required information |
| --- | --- | --- |
| Healthy | Green indicator + “Healthy” | Fresh usable GNSS and expected Command contact |
| Degraded | Amber indicator + reason | Poor/stale/no GNSS, delayed ACK or growing backlog |
| Lost | Red indicator + “Contact lost” | Time of last contact; location age separately |
| SOS | Prominent red alert with SOS label/icon | Device/party, event time, fix age/accuracy, receipt and operator acknowledgement |

Never rely on colour alone; use text/icons and adequate contrast. Contact and GNSS
quality are separate dimensions: a connected device may have stale GNSS, and an
unreachable device's last known coordinates are not current. Health timing follows
effective per-device reporting intervals; Command policy sets explicit grace periods.
No arbitrary fixed loss timeout is frozen here. Unknown/unavailable telemetry is
“Unknown” or “Unavailable”, never zero or a healthy indicator.

SOS is more severe than contact loss and persists until the operator acts. “Saved
on phone”, “Received by Command”, and “Acknowledged by operator” mean different
things. Transport ACK must never imply a human acknowledged SOS. Audible
alerts accompany visible alerts; status still remains readable without audio.

## Visual foundations

- Use platform/system sans-serif type, clear hierarchy, readable body text (web
  baseline 16 px, Android scalable sp), and tabular numerals for distance/time.
- Use a small 4-unit spacing scale (4/8/12/16/24/32), generous grouping, and sparse
  borders. Respect text scaling and responsive layout.
- Use one primary action per control group; secondary actions are quieter. Clear
  Recording is destructive and requires explicit confirmation explaining what clears.
  Stop Recording must never read as Stop Tracking.
- Meet accessible contrast (4.5:1 normal text, 3:1 large text/essential UI), visible
  keyboard focus and labelled controls. Web pointer targets should be at least
  24 px with usable spacing; Android touch targets at least 48 dp.
- Assign stable, distinguishable per-device track colours keyed by `device_id`
  within a recording. Use labels/line styles as additional cues and cycle colours
  for arbitrary device counts. Track colours never encode health/SOS severity.

## Command

Track/map/canvas is the dominant surface, roughly two-thirds of available desktop
space. Device list, recording controls and status support it. Dynamic devices get
show/hide controls, total travelled distance and concise hover details: observation
time, cumulative distance and accuracy. Show/hide and display-dot interval only
change presentation. Live state/status/SOS remain visible while recording is stopped.
Resizing/smaller screens may reorganize controls without hiding critical alerts.

## Android

Minimal operational screen: tracking state, GNSS quality/age, Command connection/
last ACK, battery, queue count, effective reporting interval, configuration and SOS
fallback. Show remote override and local interval clearly. Keep diagnostics in
secondary details; do not expose internal protocol/version machinery as primary UI.
Foreground notification uses consistent operational wording.

Use “Device” for technical identity, “Party ID/name” for editable operator label,
“Reporting interval” for Android transmission cadence, “Display-dot interval” for
Command dot visibility, “Recording” for Command collection, “Tracking” for Android
measurement, and “Travelled distance” for accepted horizontal segment distance.
Use metres and seconds consistently; show local display time with timezone while
stored/wire times remain UTC. [Protocol](../protocol/protocol-v1.md) and
[architecture](architecture.md) are authoritative for these meanings.

Issue #5 uses a prominent hold-to-activate SOS control and explicitly labelled
foreground-only volume-key option. Android displays saved versus Command-received
status and marks human acknowledgement Command-only. Command keeps event-specific
alert cards, keyboard acknowledgement, original event/receipt times and GNSS quality.
Audio blocked/unavailable status accompanies visible alerts. Acknowledged events
remain visible with quieter styling; Recording controls never dismiss emergencies.

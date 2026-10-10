"use strict";
const assert = require("node:assert/strict");
const SosAlarm = require("../../command/web/sos-alarm.js");
let now = 0,
  serial = 0,
  beeps = [],
  stopped = 0;
const tasks = new Map();
const alarm = new SosAlarm(
  () => beeps.push(now),
  () => stopped++,
  (fn, delay) => {
    const id = ++serial;
    tasks.set(id, { fn, at: now + delay });
    return id;
  },
  (id) => tasks.delete(id),
);
function advance(ms) {
  const end = now + ms;
  while (true) {
    const next = [...tasks].sort((a, b) => a[1].at - b[1].at)[0];
    if (!next || next[1].at > end) break;
    now = next[1].at;
    tasks.delete(next[0]);
    next[1].fn();
  }
  now = end;
}
alarm.update(true, false);
advance(3000);
assert.deepEqual(beeps, []);
alarm.update(true, true);
assert.deepEqual(beeps, [3000]);
for (let i = 0; i < 10; i++) {
  alarm.update(true, true);
  advance(100);
}
assert.deepEqual(beeps, [3000, 4000]);
assert.equal(tasks.size, 1);
// Polling, toast dismissal, dialogs and partial ACK all leave one pending condition.
alarm.update(true, true);
advance(2000);
assert.deepEqual(beeps, [3000, 4000, 5000, 6000]);
alarm.update(false, true);
assert.equal(tasks.size, 0);
assert.ok(stopped > 0);
advance(3000);
assert.equal(beeps.length, 4);
alarm.update(true, true);
assert.equal(beeps.at(-1), 9000);
alarm.update(true, false);
assert.equal(tasks.size, 0);
alarm.update(true, true);
assert.equal(beeps.at(-1), 9000);
assert.equal(tasks.size, 1);
// Recreating from authoritative unacknowledged state after refresh sounds immediately.
const restored = new SosAlarm(
  () => beeps.push(now),
  () => {},
  (fn, delay) => {
    const id = ++serial;
    tasks.set(id, { fn, at: now + delay });
    return id;
  },
  (id) => tasks.delete(id),
);
restored.update(true, true);
assert.equal(beeps.at(-1), 9000);
restored.update(false, true);
alarm.update(false, true);
assert.equal(tasks.size, 0);
console.log(
  "PASS: shared SOS alarm immediate arrival, exact 1 s cadence, no timer duplication, partial/final ACK, blocked/resumed audio and restored pending state.",
);

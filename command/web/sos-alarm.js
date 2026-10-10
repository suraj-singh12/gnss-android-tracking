/* One shared cadence, driven only by persisted operator-unacknowledged events. */
"use strict";
class SosAlarm {
  constructor(
    beep,
    stop,
    schedule = (fn, ms) => setTimeout(fn, ms),
    cancel = (id) => clearTimeout(id),
  ) {
    Object.assign(this, { beep, stop, schedule, cancel });
    this.pending = false;
    this.enabled = false;
    this.timer = null;
  }
  update(pending, enabled = this.enabled) {
    this.pending = pending;
    this.enabled = enabled;
    if (!pending || !enabled) {
      if (this.timer !== null) this.cancel(this.timer);
      this.timer = null;
      this.stop();
    } else if (this.timer === null) this.tick();
  }
  tick() {
    if (!this.pending || !this.enabled) return;
    this.beep();
    this.timer = this.schedule(() => {
      this.timer = null;
      this.tick();
    }, 1000);
  }
}
if (typeof module !== "undefined") module.exports = SosAlarm;

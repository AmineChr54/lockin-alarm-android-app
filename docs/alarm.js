// The alarm of this page: the same rules as the app's state machine, with time sped up.
export const SNOOZES = [5, 3, 1];
const MAX_ROUNDS = 3;

export class Alarm extends EventTarget {
  phase = 'idle'; // idle, ringing, snoozed, waiting, asking, done
  used = 0; // snoozes
  round = 0; // missed awake checks
  minutes = 0; // how late it got
  message = '';
  until = 0;

  #set(phase, message = '') { this.phase = phase; this.message = message; this.dispatchEvent(new Event('change')); }
  get on() { return this.phase === 'ringing' || this.phase === 'snoozed'; }
  get snoozesLeft() { return this.round > 0 ? 0 : SNOOZES.length - this.used; }
  get left() { return Math.max(0, Math.ceil((this.until - performance.now()) / 1000)); }

  ring() { if (this.phase === 'idle') this.#set('ringing'); }

  snooze() {
    if (this.phase !== 'ringing' || this.snoozesLeft === 0) return;
    const min = SNOOZES[this.used++];
    this.minutes += min;
    this.until = performance.now() + min * 1000; // a minute is a second here
    this.#set('snoozed');
  }

  scan(right) {
    if (!this.on) return false;
    if (!right) { if (this.phase === 'ringing') this.#set('ringing', 'Wrong tag. Find the right one!'); return false; }
    this.until = 0;
    this.#set(this.round < MAX_ROUNDS ? 'waiting' : 'done');
    return true;
  }

  /** The awake check comes into view. */
  ask() {
    if (this.phase !== 'waiting') return;
    this.until = performance.now() + 30000;
    this.#set('asking');
  }

  awake() { if (this.phase === 'asking') this.#set('done'); }

  tick(now) {
    if (!this.until || now < this.until) return;
    if (this.phase === 'snoozed') { this.until = 0; this.#set('ringing'); }
    else if (this.phase === 'asking') { this.until = 0; this.round++; this.#set('ringing'); }
  }
}

/** The app's own tone (design/gen_tone.py), synthesized. Silent until switched on. */
export class Tone {
  on = false; #ctx = null; #timer = 0;
  static PATTERN = [[880, .16], [0, .06], [1175, .16], [0, .06], [880, .16], [0, .06], [1175, .3], [0, .5]];
  static LENGTH = Tone.PATTERN.reduce((s, [, d]) => s + d, 0);

  update(ringing) {
    const run = this.on && ringing && !document.hidden;
    if (run && !this.#timer) { this.#ctx ??= new AudioContext(); this.#ctx.resume(); this.#loop(); this.#timer = setInterval(() => this.#loop(), Tone.LENGTH * 1000); }
    if (!run && this.#timer) { clearInterval(this.#timer); this.#timer = 0; this.#ctx.suspend(); }
  }

  #loop() {
    const c = this.#ctx; let t = c.currentTime + .03;
    for (const [f, d] of Tone.PATTERN) {
      if (f) for (const [mul, vol] of [[1, .09], [3, .03]]) {
        const o = c.createOscillator(), g = c.createGain();
        o.frequency.value = f * mul;
        g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(vol, t + .007); g.gain.setValueAtTime(vol, t + d - .007); g.gain.linearRampToValueAtTime(0, t + d);
        o.connect(g).connect(c.destination); o.start(t); o.stop(t + d);
      }
      t += d;
    }
  }
}

/** Is the tone sounding at this moment of its loop? Drives the shaking too. */
export function beep(seconds) {
  let t = seconds % Tone.LENGTH;
  for (const [f, d] of Tone.PATTERN) { if (t < d) return f > 0; t -= d; }
  return false;
}

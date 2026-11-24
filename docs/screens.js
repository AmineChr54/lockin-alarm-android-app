// What the 3D phone shows: real screenshots, and the ringing screens drawn live on a canvas like the app draws them.
const W = 540, H = 1200;
const SERIF = '"Instrument Serif", Georgia, serif', SANS = 'Inter, system-ui, sans-serif';
const C = { bg: '#1c1d20', card: '#26272b', text: '#f3f1ec', muted: '#a3a4aa', coral: '#f26b4f', bad: '#ff8a80', ink: '#17181b' };

export class Screens {
  canvas = Object.assign(document.createElement('canvas'), { width: W, height: H });
  #c = this.canvas.getContext('2d'); #img = {}; #key = '';

  async load() {
    await Promise.all([
      ...['alarms', 'edit', 'edit2', 'tags', 'stats', 'slow', 'ring2'].map(async n => {
        const i = new Image(); i.src = `img/${n}.webp`;
        try { await i.decode(); this.#img[n] = i; } catch { /* the screen stays plain */ }
      }),
      ...['400 40px', 'italic 400 40px'].map(f => document.fonts.load(`${f} "Instrument Serif"`)),
      document.fonts.load('900 40px Inter'), document.fonts.load('500 20px Inter'),
    ]).catch(() => {});
  }

  /** Draws [view] if it changed. Returns true if the texture needs an upload. */
  draw(view) {
    const key = JSON.stringify(view);
    if (key === this.#key) return false;
    this.#key = key;
    const c = this.#c;
    c.setTransform(1, 0, 0, 1, 0, 0); c.globalAlpha = 1; c.textAlign = 'center'; c.textBaseline = 'alphabetic';
    c.fillStyle = C.bg; c.fillRect(0, 0, W, H);
    this['_' + view.name]?.(c, view) ?? this.#shot(view.name);
    return true;
  }

  #shot(n) { const i = this.#img[n]; if (i) this.#c.drawImage(i, 0, 0, W, H); return true; }

  #serif(text, y, size, color = C.text, x = W / 2) {
    // "Still *up*?": the starred part in italic, like in the app
    const c = this.#c, parts = text.split('*').map((t, i) => [t, `${i % 2 ? 'italic ' : ''}400 ${size}px ${SERIF}`]);
    const w = parts.reduce((s, [t, f]) => { c.font = f; return s + c.measureText(t).width; }, 0);
    let at = x - w / 2; c.fillStyle = color; c.textAlign = 'left';
    for (const [t, f] of parts) { c.font = f; c.fillText(t, at, y); at += c.measureText(t).width; }
    c.textAlign = 'center';
  }
  #sans(text, y, size = 15, color = C.muted, weight = 400, x = W / 2) { const c = this.#c; c.font = `${weight} ${size}px ${SANS}`; c.fillStyle = color; c.fillText(text, x, y); }
  #box(x, y, w, h, r, fill, stroke) { const c = this.#c; c.beginPath(); c.roundRect(x, y, w, h, r); if (fill) { c.fillStyle = fill; c.fill(); } if (stroke) { c.strokeStyle = stroke; c.lineWidth = 1.5; c.stroke(); } }
  #status(t, color = C.text) { const c = this.#c; c.textAlign = 'left'; this.#sans(t, 44, 18, color, 500, 44); c.textAlign = 'center'; }

  _check(c, v) {
    c.fillStyle = '#0b0c10'; c.fillRect(0, 0, W, H);
    this.#status('23:00');
    this.#serif('23:00', 250, 148, '#d9d6d0');
    this.#sans('Tuesday', 300, 22, '#8c8a8a');
    const rows = ['NFC', 'Notifications', 'Full-screen alarm', 'Do Not Disturb access', 'Battery 100%', 'Alarm volume', 'Tag'], n = Math.min(rows.length, v.n);
    this.#box(28, 420, W - 56, 130 + rows.length * 62, 40, C.card);
    c.textAlign = 'left';
    this.#sans('Lock In Alarm · now', 468, 18, C.muted, 400, 62);
    this.#sans(n === rows.length ? 'All set for 07:00' : 'Checking…', 506, 25, C.text, 600, 62);
    rows.forEach((r, i) => {
      const ok = i < n, y = 566 + i * 62;
      c.globalAlpha = ok ? 1 : .35; this.#sans(r, y, 22, C.text, 400, 62);
      if (ok) { c.strokeStyle = C.coral; c.lineWidth = 3; c.lineCap = 'round'; c.lineJoin = 'round'; c.beginPath(); c.moveTo(W - 92, y - 8); c.lineTo(W - 84, y); c.lineTo(W - 68, y - 16); c.stroke(); }
      c.globalAlpha = 1;
    });
    c.textAlign = 'center';
    return true;
  }

  _locked(c, v) {
    this.#shot('edit');
    c.fillStyle = 'rgba(16,17,20,.62)'; c.fillRect(0, 0, W, H);
    this.#box(30, 470, W - 60, 270, 46, C.card);
    this.#serif('Locked', 560, 70);
    this.#sans(v.why, 612, 23, C.muted);
    this.#sans('rings in 3 h, until then only stricter', 690, 20, C.coral, 500);
    return true;
  }

  _sunrise(c, v) {
    const mix = (a, b) => `rgb(${a.map((x, i) => Math.round(x + (b[i] - x) * v.p))})`;
    const g = c.createLinearGradient(0, 0, 0, H); g.addColorStop(0, mix([11, 11, 22], [255, 233, 184])); g.addColorStop(1, mix([42, 15, 10], [255, 154, 92]));
    c.fillStyle = g; c.fillRect(0, 0, W, H);
    const fg = v.p > .45 ? C.ink : '#cfa58f';
    this.#serif(v.time, 590, 150, fg); this.#serif('Rings at *07:00*', 660, 50, fg);
    this.#sans("Already up? Scan your tag and it won't ring.", 712, 22, fg);
    return true;
  }

  _ring(c, v) {
    c.fillStyle = '#000'; c.fillRect(0, 0, W, H);
    if (v.flash) { const g = c.createRadialGradient(W / 2, 620, 20, W / 2, 620, 640); g.addColorStop(0, 'rgba(242,107,79,.55)'); g.addColorStop(1, 'rgba(242,107,79,0)'); c.fillStyle = g; c.fillRect(0, 0, W, H); }
    this.#serif(v.time, 220, 156, '#fff'); this.#sans('Weekdays', 270, 24, '#e6e4df');
    c.font = `900 108px ${SANS}`; c.letterSpacing = '6px'; c.fillStyle = '#fff'; c.fillText('LOCK IN', W / 2 + 3, 850); c.letterSpacing = '0px';
    this.#serif('Scan your *tag* to stop it', 912, 46, '#e6e4df');
    if (v.message) this.#sans(v.message, 972, 23, C.bad, 500);
    const t = v.left ? `Snooze ${v.min} min · ${v.left} left` : v.round ? 'You fell back asleep. No snoozes' : 'No snoozes left';
    c.font = `500 22px ${SANS}`; const w = c.measureText(t).width + 60;
    this.#box(W / 2 - w / 2, 1020, w, 62, 31, null, 'rgba(255,255,255,.5)'); this.#sans(t, 1059, 22, v.left ? '#fff' : C.muted, 500);
    return true;
  }

  _snoozed(c, v) {
    this.#status(v.time); this.#serif('Snoozed', 560, 112); this.#sans(`rings again in ${v.left} min`, 620, 24, C.muted); return true;
  }
  _morning(c, v) {
    c.fillStyle = C.coral; c.fillRect(0, 0, W, H);
    this.#status(v.time, C.ink); this.#serif('Good *morning!*', 580, 80, C.ink);
    this.#sans(v.wait ? "I'll check on you in 7 min" : 'Go get the day!', 640, 25, C.ink, 500); return true;
  }
  _ask(c, v) {
    this.#status(v.time); this.#serif('Still *up?*', 470, 128); this.#serif(String(v.left), 680, 190, C.coral);
    this.#sans('seconds, then it rings again without snoozes', 752, 22, C.muted);
    this.#box(40, 830, W - 80, 104, 52, C.coral); this.#serif("I'm *up*", 896, 46, C.ink); return true;
  }
}

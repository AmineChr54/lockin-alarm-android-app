import { Alarm, SNOOZES, Tone, beep } from './alarm.js';
import { Screens } from './screens.js';

const $ = id => document.getElementById(id);
const calm = matchMedia('(prefers-reduced-motion: reduce)').matches;
const alarm = new Alarm(), tone = new Tone(), screens = new Screens();
const steps = [...document.querySelectorAll('[data-step]')].map(el => ({ el, n: +el.dataset.step, min: +el.dataset.time.slice(0, 2) * 60 + +el.dataset.time.slice(3) }));
for (let i = 1; i < steps.length; i++) while (steps[i].min < steps[i - 1].min) steps[i].min += 1440; // past midnight
const title = document.title;
let stage = null, step = 0, minutes = steps[0].min, shot = 'tags', lastOver = null, demoOn = 0, armed = false;

// ---------- where on the page ----------
function measure() {
  const mid = innerHeight / 2;
  let i = 0, f = 0;
  const c = steps.map(({ el, n }) => {
    const r = el.getBoundingClientRect();
    // the pinned demo counts as one place for as long as it is pinned
    if (n === 4) return { a: r.top + innerHeight * .5, b: r.bottom - innerHeight * .5 };
    if (n === 6) return { a: r.top + Math.min(r.height, innerHeight) * .5, b: r.bottom - innerHeight * .45 };
    // on a narrow screen the text sits at the bottom of its step
    const m = n && innerWidth < 821 ? r.bottom - innerHeight * .5 : r.top + Math.min(r.height / 2, innerHeight * .75);
    return { a: m, b: m };
  });
  for (let k = 0; k < c.length; k++) if (c[k].a <= mid) i = k;
  if (c[i].b < mid && i + 1 < c.length) f = (mid - c[i].b) / Math.max(1, c[i + 1].a - c[i].b);
  step = Math.min(steps.length - 1, i + Math.min(1, Math.max(0, f)));
  if (scrollY <= 2) step = 0;
  minutes = steps[i].min + (i + 1 < steps.length ? (steps[i + 1].min - steps[i].min) * Math.min(1, Math.max(0, f)) : 0);
}
const hhmm = m => `${String(Math.floor(m / 60) % 24).padStart(2, '0')}:${String(Math.floor(m) % 60).padStart(2, '0')}`;
const late = () => hhmm(420 + alarm.minutes);

// ---------- what the phone shows ----------
function view(now) {
  const lit = beep(now);
  switch (alarm.phase) {
    case 'ringing': return { name: 'ring', time: late(), left: alarm.snoozesLeft, min: SNOOZES[alarm.used], round: alarm.round, message: alarm.message, flash: lit && !calm };
    case 'snoozed': return { name: 'snoozed', time: late(), left: alarm.left };
    case 'asking': return { name: 'ask', time: hhmm(427 + alarm.minutes), left: alarm.left };
  }
  if (alarm.phase === 'waiting' || alarm.phase === 'done') {
    if (step > 5.5) return { name: shot };
    if (step > 3.4) return { name: 'morning', time: alarm.phase === 'done' ? hhmm(428 + alarm.minutes) : late(), wait: alarm.phase === 'waiting' };
  }
  if (step < .5) return { name: 'alarms' };
  if (step < 1.5) return { name: 'check', n: Math.floor(Math.max(0, (step - .62) / .4) * 8) };
  if (step < 2.5) return { name: 'locked', why: ['Turn it off? Not now.', 'Ring later? Not now.', 'More snooze? Not now.'][calm ? 0 : Math.floor(now / 2.2) % 3] };
  const p = Math.min(1, Math.max(0, (step - 2.5) / .95));
  return { name: 'sunrise', p: +p.toFixed(2), time: hhmm(410 + p * 9.9) };
}

// ---------- reactions ----------
function changed() {
  const { phase } = alarm, up = phase === 'waiting' || phase === 'asking' || phase === 'done';
  document.body.classList.toggle('day', up);
  document.body.classList.toggle('ringing', phase === 'ringing');
  document.querySelector('meta[name=theme-color]').content = up ? '#f7ead9' : '#0a0b10';
  document.title = phase === 'ringing' ? `⏰ ${late()} still ringing` : title;
  $('snooze').hidden = $('carry').hidden = up;
  $('snooze').disabled = phase !== 'ringing' || !alarm.snoozesLeft;
  $('snooze').textContent = phase === 'snoozed' ? 'Snoozed' : alarm.snoozesLeft ? `Snooze ${SNOOZES[alarm.used]} min · ${alarm.snoozesLeft} left` : 'No snoozes left';
  $('awake').hidden = phase !== 'asking';
  $('demo-title').innerHTML = up ? 'Good <em>morning.</em>' : alarm.round ? 'You fell back <em>asleep.</em>' : 'Try to turn it <em>off.</em>';
  $('demo-text').textContent =
    up ? "That's the whole idea. You're standing in the bathroom now, so you might as well stay up." :
    phase === 'snoozed' ? `Snoozed. A minute is a second here, so enjoy it. The next one is ${alarm.snoozesLeft ? 'shorter' : 'not coming'}.` :
    alarm.message ? 'Wrong tag. It wants the one you paired: the copper one.' :
    alarm.round ? 'No answer, so it rings again, without snoozes. Back to the mirror.' :
    !stage ? 'Carry it to the tag. Nothing else will stop it, not even scrolling away.' :
    'Drag the phone onto the copper tag. Nothing else will stop it, not even scrolling away.';
  $('bye').innerHTML = up ? 'Go get the <em>day.</em>' : 'It is still <em>ringing.</em>';
  tone.update(phase === 'ringing');
}
alarm.addEventListener('change', changed);

function scan(right) {
  if (!alarm.scan(right)) return;
  stage?.found(performance.now() / 1000);
  navigator.vibrate?.(60);
  if (stage) stage.held = null;
  $('grab').classList.remove('on');
}

$('snooze').onclick = () => alarm.snooze();
$('awake').onclick = () => alarm.awake();
$('carry').onclick = () => { if (!stage) return scan(true); stage.carry = 1; };
$('sound').onclick = e => {
  tone.on = !tone.on; e.target.setAttribute('aria-pressed', tone.on); e.target.textContent = tone.on ? 'Sound on' : 'Sound off';
  tone.update(alarm.phase === 'ringing');
};
document.addEventListener('visibilitychange', () => tone.update(alarm.phase === 'ringing'));
$('copy').onclick = async e => {
  try { await navigator.clipboard.writeText($('cmd').textContent); e.target.textContent = 'Copied'; }
  catch { getSelection().selectAllChildren($('cmd')); e.target.textContent = 'Press Ctrl+C'; }
  setTimeout(() => { e.target.textContent = 'Copy'; }, 1800);
};

const list = $('list');
for (const li of list.children) {
  const pick = () => { shot = li.dataset.shot; for (const o of list.children) o.classList.toggle('on', o === li); };
  li.addEventListener('pointerenter', e => { if (e.pointerType === 'mouse') { list.classList.add('hover'); pick(); } });
  new IntersectionObserver(([e]) => { if (e.isIntersecting && !list.classList.contains('hover')) pick(); }, { rootMargin: '-45% 0px -45% 0px' }).observe(li);
}
list.addEventListener('pointerleave', () => list.classList.remove('hover'));

// ---------- dragging the phone ----------
const grab = $('grab'); let off = null;
grab.addEventListener('pointerdown', e => {
  if (!stage || !alarm.on || e.button) return;
  grab.setPointerCapture(e.pointerId); grab.classList.add('on'); stage.carry = 0;
  off = stage.pos.clone().sub(stage.toWorld(e.clientX, e.clientY)); stage.held = stage.pos.clone(); lastOver = null;
});
grab.addEventListener('pointermove', e => { if (stage?.held) stage.held = stage.toWorld(e.clientX, e.clientY).add(off).setZ(0); });
for (const t of ['pointerup', 'pointercancel']) grab.addEventListener(t, () => { if (stage) stage.held = null; grab.classList.remove('on'); });
addEventListener('pointermove', e => stage?.mouse.set(e.clientX / innerWidth * 2 - 1, 1 - e.clientY / innerHeight * 2), { passive: true });

// ---------- the loop ----------
let last = performance.now();
function frame(ms) {
  requestAnimationFrame(frame);
  const now = ms / 1000, dt = Math.min(.05, (ms - last) / 1000); last = ms;
  measure();
  alarm.tick(performance.now());
  if (step > 3.75 && alarm.phase === 'idle') alarm.ring();
  if (step > 4.8 && step < 5.6) alarm.ask();
  $('clock').textContent = hhmm(minutes + (minutes >= 1860 ? alarm.minutes : 0));
  const sheet = document.querySelector('.sheet').getBoundingClientRect();
  document.body.classList.toggle('onsheet', sheet.top < 34 && sheet.bottom > 34);
  const r = $('off').getBoundingClientRect(), inDemo = r.top < innerHeight * .55 && r.bottom > innerHeight * .55;
  const away = alarm.phase === 'ringing' && !inDemo;
  if ($('still').hidden === away) {
    $('still').hidden = !away;
    $('still-text').textContent = r.top > 0 ? 'Still ringing. The tag is down there.' : 'Still ringing. The tag is back there.';
  }
  demoOn = inDemo ? 1 : 0;
  if (!stage) return;
  if (screens.draw(view(now))) stage.texture.needsUpdate = true;
  stage.frame(now, dt, { step, ringing: alarm.phase === 'ringing', beep: beep(now), sun: document.body.classList.contains('day') ? 1 : 0, demo: demoOn });

  if (alarm.on && (stage.held || stage.carry)) {
    const o = stage.over();
    if (o !== lastOver) { lastOver = o; if (o !== null && !(stage.carry && o === false)) scan(o); }
  }
  if (!alarm.on) stage.carry = 0;
  const b = stage.rect(), live = inDemo && alarm.on, top = document.querySelector('.pin').getBoundingClientRect().top;
  grab.style.cssText = live ? `transform:translate(${b.x}px,${b.y - top}px);width:${b.w}px;height:${b.h}px` : 'display:none';
  for (const [el, g, s] of [[$('t-good'), stage.tag, stage.tscale], [$('t-bad'), stage.bad, stage.badScale]]) {
    const [x, y] = stage.px(g.position), d = s * 1.22 * stage.h / (stage.hh * 2);
    el.hidden = !live;
    el.style.transform = `translate(${x}px,${y + d + 14 - top}px) translateX(-50%)`; el.style.opacity = live && alarm.phase === 'ringing' && !stage.held && !stage.carry ? 1 : 0;
  }
}

async function start() {
  if (armed) return; armed = true;
  const loaded = screens.load();
  try {
    const { Stage } = await import('./scene.js');
    await loaded;
    stage = new Stage($('gl'), screens.canvas, calm);
    addEventListener('resize', () => stage.resize());
    if (location.hostname === 'localhost') window.__demo = { alarm, stage, get step() { return step; } }; // for the screenshots
  } catch (e) { console.warn('No 3D here:', e); document.body.classList.add('flat'); }
  changed();
  document.body.classList.add('ready');
  requestAnimationFrame(frame);
}
start();

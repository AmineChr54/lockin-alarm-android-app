// The night, the phone and the tag. Everything is modelled in code; no model files.
import {
  ACESFilmicToneMapping, AdditiveBlending, BufferAttribute, BufferGeometry, CanvasTexture, CircleGeometry, Color, Curve, CylinderGeometry, DirectionalLight,
  DoubleSide, ExtrudeGeometry, Group, MathUtils, Mesh, MeshBasicMaterial, MeshPhysicalMaterial, MeshStandardMaterial, PMREMGenerator, PerspectiveCamera,
  PlaneGeometry, RingGeometry, Scene, ShaderMaterial, Shape, ShapeGeometry, Sprite, SpriteMaterial, SRGBColorSpace, TubeGeometry, Vector2, Vector3, WebGLRenderer,
} from './vendor/three.js';

const PHONE = { w: 1.5, h: 3.24, r: .25, d: .13 };
const damp = MathUtils.damp;

function rounded(w, h, r) {
  const s = new Shape(), x = -w / 2, y = -h / 2;
  s.moveTo(x + r, y); s.lineTo(x + w - r, y); s.absarc(x + w - r, y + r, r, -Math.PI / 2, 0); s.lineTo(x + w, y + h - r); s.absarc(x + w - r, y + h - r, r, 0, Math.PI / 2);
  s.lineTo(x + r, y + h); s.absarc(x + r, y + h - r, r, Math.PI / 2, Math.PI); s.lineTo(x, y + r); s.absarc(x + r, y + r, r, Math.PI, Math.PI * 1.5);
  return s;
}

class Spiral extends Curve {
  constructor(turns, r0, r1) { super(); Object.assign(this, { turns, r0, r1 }); }
  getPoint(t, out = new Vector3()) { const a = Math.PI * 2 * this.turns * t, r = this.r0 + (this.r1 - this.r0) * t; return out.set(Math.cos(a) * r, Math.sin(a) * r, 0); }
}

function makePhone(screenTexture) {
  const g = new Group(), { w, h, r, d } = PHONE;
  const body = new ExtrudeGeometry(rounded(w - .06, h - .06, r - .03), { depth: d - .06, bevelEnabled: true, bevelSize: .03, bevelThickness: .03, bevelSegments: 6, curveSegments: 24 });
  body.translate(0, 0, -(d - .06) / 2);
  g.add(new Mesh(body, new MeshPhysicalMaterial({ color: 0x24262c, metalness: .85, roughness: .34, clearcoat: .5, clearcoatRoughness: .3 })));
  const front = d / 2 + .0015;
  const bezel = new Mesh(new ShapeGeometry(rounded(w - .03, h - .03, r - .015), 24), new MeshStandardMaterial({ color: 0x050506, roughness: .2, metalness: .1 }));
  bezel.position.z = front; g.add(bezel);
  const sw = w - .12, sh = sw / .45, geo = new ShapeGeometry(rounded(sw, sh, r - .07), 24), p = geo.attributes.position, uv = new Float32Array(p.count * 2);
  for (let i = 0; i < p.count; i++) { uv[i * 2] = p.getX(i) / sw + .5; uv[i * 2 + 1] = p.getY(i) / sh + .5; }
  geo.setAttribute('uv', new BufferAttribute(uv, 2));
  const screen = new Mesh(geo, new MeshBasicMaterial({ map: screenTexture, toneMapped: false }));
  screen.position.z = front + .002; g.add(screen);
  const glass = new Mesh(new ShapeGeometry(rounded(w - .03, h - .03, r - .015), 24), new MeshPhysicalMaterial({ color: 0xffffff, transparent: true, opacity: .055, roughness: .06, metalness: 0, clearcoat: 1, depthWrite: false }));
  glass.position.z = front + .006; g.add(glass);
  const cam = new Mesh(new CircleGeometry(.032, 24), new MeshBasicMaterial({ color: 0x000000 })); cam.position.set(0, sh / 2 - .085, front + .004); g.add(cam);
  // side keys, and two lenses on the back
  const key = new MeshStandardMaterial({ color: 0x34363d, metalness: .9, roughness: .35 });
  for (const [y, len] of [[.55, .3], [.02, .56]]) { const k = new Mesh(new CylinderGeometry(.022, .022, len, 12), key); k.position.set(w / 2 + .004, y, 0); g.add(k); }
  const lens = new MeshPhysicalMaterial({ color: 0x08090c, roughness: .08, metalness: .4, clearcoat: 1 });
  for (const y of [1.16, .74]) { const l = new Mesh(new CylinderGeometry(.17, .17, .05, 40), lens); l.rotation.x = Math.PI / 2; l.position.set(.42, y, -d / 2 - .02); g.add(l); }
  return g;
}

function makeTag(metal, film) {
  const g = new Group();
  const copper = new MeshStandardMaterial({ color: metal, metalness: 1, roughness: .27, emissive: metal, emissiveIntensity: .05 });
  const disc = new Mesh(new CircleGeometry(1.22, 96), new MeshPhysicalMaterial({ color: film, transparent: true, opacity: .34, roughness: .5, metalness: 0, side: DoubleSide, depthWrite: false }));
  disc.position.z = -.03; g.add(disc);
  const edge = new Mesh(new RingGeometry(1.2, 1.225, 128), new MeshBasicMaterial({ color: metal, transparent: true, opacity: .55, side: DoubleSide })); g.add(edge);
  g.add(new Mesh(new TubeGeometry(new Spiral(9, .56, 1.1), 900, .0185, 8), copper));
  const bar = (x0, y0, x1, y1, w = .035) => { const l = Math.hypot(x1 - x0, y1 - y0), m = new Mesh(new PlaneGeometry(l, w), copper); m.position.set((x0 + x1) / 2, (y0 + y1) / 2, .024); m.rotation.z = Math.atan2(y1 - y0, x1 - x0); g.add(m); };
  bar(.14, 0, .56, 0); bar(-.14, 0, -.36, 0); bar(-.36, 0, -.36, .3); bar(-.36, .3, -1.02, .42, .05);
  const chip = new Mesh(new ExtrudeGeometry(rounded(.26, .26, .03), { depth: .03, bevelEnabled: false }), new MeshStandardMaterial({ color: 0x15161a, roughness: .35, metalness: .4 })); g.add(chip);
  for (const x of [-.14, .14]) { const pad = new Mesh(new PlaneGeometry(.06, .12), copper); pad.position.set(x, 0, .035); g.add(pad); }
  return Object.assign(g, { copper, disc, edge });
}

function glowTexture() {
  const c = Object.assign(document.createElement('canvas'), { width: 256, height: 256 }), x = c.getContext('2d'), g = x.createRadialGradient(128, 128, 0, 128, 128, 128);
  for (const [s, a] of [[0, 1], [.25, .62], [.5, .22], [.75, .06], [1, 0]]) g.addColorStop(s, `rgba(255,255,255,${a})`);
  x.fillStyle = g; x.fillRect(0, 0, 256, 256);
  return new CanvasTexture(c);
}

const SKY = {
  vertexShader: 'varying vec2 v; void main(){ v = uv; gl_Position = vec4(position.xy, 1., 1.); }',
  fragmentShader: `
    varying vec2 v; uniform float day, time, aspect; uniform vec2 sun;
    float hash(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
    vec3 ramp(vec3 a, vec3 b, vec3 c){ return day < .5 ? mix(a, b, day * 2.) : mix(b, c, day * 2. - 1.); }
    void main(){
      vec3 top = ramp(vec3(.026,.030,.050), vec3(.105,.080,.175), vec3(.985,.915,.820));
      vec3 mid = ramp(vec3(.050,.050,.070), vec3(.370,.160,.230), vec3(1.0,.870,.730));
      vec3 low = ramp(vec3(.105,.078,.080), vec3(.930,.410,.290), vec3(1.0,.735,.540));
      float y = v.y + .02 * sin(v.x * 3. + time * .1);
      vec3 col = mix(mix(low, mid, smoothstep(0., .5, y)), top, smoothstep(.32, 1., y));
      vec2 d = (v - sun) * vec2(aspect, 1.);
      col += mix(vec3(.10,.055,.035), vec3(.06,.04,.0), day) * exp(-dot(d, d) * 3.2);
      vec2 g = v * vec2(aspect, 1.) * 90.; vec2 id = floor(g); float h = hash(id);
      float star = step(.985, h) * smoothstep(.28, .0, length(fract(g) - .5 - (vec2(hash(id + 7.), hash(id + 3.)) - .5) * .5));
      col += star * (.35 + .35 * sin(time * (1. + h * 3.) + h * 60.)) * (1. - smoothstep(.0, .42, day)) * smoothstep(.1, .7, v.y);
      col += (hash(gl_FragCoord.xy + fract(time)) - .5) / 180.;
      gl_FragColor = vec4(col, 1.);
    }`,
};

// One entry per step of the page. x/y are fractions of the visible half width/height, so it fits any screen.
const WIDE = [
  { p: [.60, -.08], rot: [-.06, -.52, .10], s: 1.2, t: [.58, .12], ts: 1.85, day: 0 },
  { p: [.46, .0], rot: [0, -.34, .03], s: 1.36, t: [.90, .55], ts: 1.0, day: 0 },
  { p: [.50, .0], rot: [.04, .42, -.04], s: 1.36, t: [.98, .62], ts: .85, day: .04 },
  { p: [.46, .0], rot: [-.02, -.26, .02], s: 1.36, t: [.46, -1.0], ts: 1.5, day: .3 },
  { p: [.10, -.02], rot: [0, -.16, 0], s: 1.04, t: [.74, .34], ts: .52, day: .2 },
  { p: [.50, .0], rot: [0, -.3, .02], s: 1.36, t: [.50, .05], ts: 1.9, day: .2 },
  { p: [.68, .0], rot: [0, -.32, .02], s: 1.24, t: [.80, .5], ts: 1.4, day: .2 },
  { p: [.5, 2.3], rot: [0, -.6, .1], s: 1.1, t: [.5, 1.7], ts: 1.3, day: .2 },
];
const TALL = [
  { p: [.16, .30], rot: [-.06, -.42, .08], s: .92, t: [-.1, .44], ts: 1.15, day: 0 },
  { p: [0, .26], rot: [0, -.24, .03], s: .98, t: [.8, .75], ts: .7, day: 0 },
  { p: [0, .26], rot: [.04, .3, -.03], s: .98, t: [-.8, .75], ts: .6, day: .04 },
  { p: [0, .26], rot: [-.02, -.2, .02], s: .98, t: [0, .40], ts: 1.25, day: .3 },
  { p: [-.46, .20], rot: [0, .12, 0], s: .66, t: [.52, .52], ts: .40, day: .2 },
  { p: [0, .28], rot: [0, -.2, .02], s: .94, t: [0, .3], ts: 1.2, day: .2 },
  { p: [0, 2.4], rot: [0, -.3, .02], s: .9, t: [.62, 1.0], ts: .5, day: .2 },
  { p: [0, 2.6], rot: [0, -.6, .1], s: .9, t: [0, 1.7], ts: .8, day: .2 },
];
const BAD = { wide: [.74, -.40, .40], tall: [.56, -.02, .30] };

export class Stage {
  constructor(canvas, screenCanvas, calm) {
    this.calm = calm;
    const r = this.renderer = new WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
    r.toneMapping = ACESFilmicToneMapping; r.toneMappingExposure = 1.05;
    this.scene = new Scene(); this.camera = new PerspectiveCamera(30, 1, .1, 60); this.camera.position.z = 12;
    this.texture = new CanvasTexture(screenCanvas); this.texture.colorSpace = SRGBColorSpace; this.texture.anisotropy = 8;

    this.sky = new ShaderMaterial({ ...SKY, depthTest: false, depthWrite: false, uniforms: { day: { value: 0 }, time: { value: 0 }, aspect: { value: 1 }, sun: { value: new Vector2(.5, .5) } } });
    const tri = new BufferGeometry();
    tri.setAttribute('position', new BufferAttribute(new Float32Array([-1, -1, 0, 3, -1, 0, -1, 3, 0]), 3)); tri.setAttribute('uv', new BufferAttribute(new Float32Array([0, 0, 2, 0, 0, 2]), 2));
    const sky = new Mesh(tri, this.sky); sky.frustumCulled = false; sky.renderOrder = -10; this.scene.add(sky);

    this.scene.environment = this.#studio();
    this.key = new DirectionalLight(0xffe3cc, 1.6); this.key.position.set(-4, 5, 6); this.scene.add(this.key);
    this.rim = new DirectionalLight(0x8fa8ff, 1.2); this.rim.position.set(6, 1, -3); this.scene.add(this.rim);

    this.phone = makePhone(this.texture); this.scene.add(this.phone);
    this.tag = makeTag(0xd98b5a, 0x3a2a22); this.scene.add(this.tag);
    this.bad = makeTag(0x9aa4b4, 0x23262e); this.scene.add(this.bad); this.bad.scale.setScalar(.001);
    this.glow = new Sprite(new SpriteMaterial({ map: glowTexture(), color: 0xffb173, transparent: true, opacity: 0, depthWrite: false })); this.glow.position.z = -2.6; this.glow.renderOrder = -5; this.scene.add(this.glow);
    this.face = new Mesh(new CircleGeometry(1.22, 96), new MeshBasicMaterial({ color: 0xff8a55, transparent: true, opacity: 0, toneMapped: false })); this.tag.add(this.face); this.face.position.z = -.02;
    this.waves = Array.from({ length: 4 }, () => { const m = new Mesh(new RingGeometry(.97, 1, 96), new MeshBasicMaterial({ color: 0xffb98e, transparent: true, opacity: 0, side: DoubleSide, blending: AdditiveBlending, depthWrite: false })); this.scene.add(m); return m; });

    this.pos = new Vector3(3, -6, 0); this.rot = new Vector3(0, -1.6, .3); this.scale = 1; this.tpos = new Vector3(0, 0, -2); this.tscale = .6; this.badScale = 0;
    this.day = 0; this.sun = 0; this.waveAt = -9; this.held = null; this.carry = 0; this.mouse = new Vector2(); this.look = new Vector2(); this.drag = new Vector3();
    this.resize();
  }

  #studio() {
    const s = new Scene(), box = (w, h, x, y, z, v, c = 0xffffff) => { const m = new Mesh(new PlaneGeometry(w, h), new MeshBasicMaterial({ color: new Color(c).multiplyScalar(v), side: DoubleSide })); m.position.set(x, y, z); m.lookAt(0, 0, 0); s.add(m); };
    s.background = new Color(0x0b0c11);
    box(6, 2.4, -5, 6, 5, 5, 0xffe9d8); box(2, 9, 8, 1, 2, 4, 0xbfd0ff); box(9, 1.2, 0, -7, 3, 1.6, 0xff9468); box(1.2, 7, -8, 0, 1, 3, 0xffd9bd); box(5, 5, 2, 3, 10, .7);
    const pm = new PMREMGenerator(this.renderer), t = pm.fromScene(s, .03).texture; pm.dispose();
    return t;
  }

  resize() {
    const w = innerWidth, h = this.renderer.domElement.clientHeight || innerHeight;
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, w < 821 ? 2 : 1.75)); this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h; this.camera.updateProjectionMatrix();
    this.hh = Math.tan(MathUtils.degToRad(15)) * 12; this.hw = this.hh * this.camera.aspect; this.w = w; this.h = h; this.tall = w < 821;
    this.sky.uniforms.aspect.value = w / h;
  }

  at(xy, z = 0) { const k = (12 - z) / 12; return new Vector3(xy[0] * this.hw * k, xy[1] * this.hh * k, z); }
  px(v) { const p = v.clone().project(this.camera); return [(p.x + 1) / 2 * this.w, (1 - p.y) / 2 * this.h]; }
  toWorld(x, y) { return new Vector3((x / this.w * 2 - 1) * this.hw, (1 - y / this.h * 2) * this.hh, 0); }
  /** The phone's rectangle on the page, for the grab area. */
  rect() { const [x, y] = this.px(this.phone.position), k = this.h / (this.hh * 2), w = PHONE.w * this.scale * k, h = PHONE.h * this.scale * k; return { x: x - w / 2, y: y - h / 2, w, h }; }

  pose(step) {
    const K = this.tall ? TALL : WIDE, i = Math.min(K.length - 1, Math.floor(step)), n = Math.min(K.length - 1, i + 1), f = MathUtils.smoothstep(step - i, .12, .88), a = K[i], b = K[n], m = (x, y) => x + (y - x) * f;
    return { p: a.p.map((v, k) => m(v, b.p[k])), rot: a.rot.map((v, k) => m(v, b.rot[k])), s: m(a.s, b.s), t: a.t.map((v, k) => m(v, b.t[k])), ts: m(a.ts, b.ts), day: m(a.day, b.day) };
  }

  /** s: { step, ringing, beep, sun, demo (0..1 how much the demo is on) } */
  frame(now, dt, s) {
    const k = this.pose(s.step), q = this.calm ? 60 : 5.5, z = -1.6 * (1 - s.demo);
    // short screens: never let the phone grow out of the frame
    const fit = Math.min(1, this.hw / (this.tall ? 1.2 : 2.9));
    let target = this.at(k.p);
    const tag = this.at(k.t, z), ts = k.ts * fit;
    if (this.held) target = this.held.clone();
    if (this.carry) target = this.tpos.clone().setZ(0);
    this.drag.subVectors(target, this.pos);
    const speed = this.held ? 16 : this.carry ? 3.4 : q;
    this.pos.set(damp(this.pos.x, target.x, speed, dt), damp(this.pos.y, target.y, speed, dt), damp(this.pos.z, this.held || this.carry ? .5 : 0, q, dt));
    this.look.set(damp(this.look.x, this.mouse.x, 3, dt), damp(this.look.y, this.mouse.y, 3, dt));
    const lean = this.calm ? 0 : 1;
    this.rot.set(damp(this.rot.x, k.rot[0] - this.drag.y * .12 * lean, q, dt), damp(this.rot.y, k.rot[1] + this.drag.x * .14 * lean, q, dt), damp(this.rot.z, k.rot[2], q, dt));
    this.scale = damp(this.scale, k.s * fit * (this.held || this.carry ? .78 : 1), q, dt);
    const shake = s.ringing && s.beep && !this.calm ? 1 : 0;
    this.phone.position.copy(this.pos); this.phone.position.x += shake * Math.sin(now * 92) * .018; this.phone.position.y += (this.calm ? 0 : Math.sin(now * .9) * .035) + shake * Math.cos(now * 77) * .01;
    this.phone.rotation.set(this.rot.x - this.look.y * .10 * lean, this.rot.y + this.look.x * .16 * lean, this.rot.z + shake * Math.sin(now * 84) * .022);
    this.phone.scale.setScalar(this.scale);

    this.sun = damp(this.sun, s.sun, this.calm ? 60 : 1.5, dt); this.day = damp(this.day, s.sun ? 1 : k.day, this.calm ? 60 : s.sun ? 1.5 : 3, dt);
    const grow = this.sun * (this.tall ? .5 : .9) * Math.sin(Math.min(1, this.sun) * Math.PI / 2);
    this.tpos.set(damp(this.tpos.x, tag.x, q, dt), damp(this.tpos.y, tag.y, q, dt), damp(this.tpos.z, z - this.sun * 2.2, q, dt)); this.tscale = damp(this.tscale, ts * (1 + grow), q, dt);
    this.tag.position.copy(this.tpos); this.tag.scale.setScalar(this.tscale);
    this.tag.rotation.set(-.12 + Math.sin(now * .31) * .1 + this.look.y * .12, .22 + Math.sin(now * .23) * .2 - this.look.x * .16, this.calm ? 0 : now * .05);
    this.tag.copper.emissiveIntensity = .05 + this.sun * 1.3 + (s.demo > .5 && s.ringing ? .16 + .12 * Math.sin(now * 4) : 0);
    this.face.material.opacity = this.sun; this.tag.disc.material.opacity = .34 * (1 - this.sun); this.tag.edge.material.opacity = .55 * (1 - this.sun);
    this.glow.position.set(this.tpos.x, this.tpos.y, this.tpos.z - .6); this.glow.scale.setScalar(this.tscale * (5.4 + this.sun * 2)); this.glow.material.opacity = .13 + this.sun * .62; this.glow.material.color.set(this.sun > .5 ? 0xffc98f : 0xe08a58);

    const B = this.tall ? BAD.tall : BAD.wide; this.badScale = damp(this.badScale, s.demo > .5 && s.ringing ? B[2] * fit : .0001, q, dt);
    this.bad.position.copy(this.at(B)); this.bad.scale.setScalar(this.badScale); this.bad.rotation.set(.1, -.25 + Math.sin(now * .4) * .12, now * -.06);

    // rings of sound while it rings, one big wave when the tag is found
    const found = now - this.waveAt;
    this.waves.forEach((m, i) => {
      const done = found < 2.6, t = done ? found / 1.7 - i * .16 : (now * .62 + i / 4) % 1, on = t > 0 && t < 1;
      m.material.opacity = !on ? 0 : done ? (1 - t) * .75 : s.ringing && s.step > 3.5 && !this.calm ? (1 - t) * .16 : 0;
      m.position.copy(done ? this.tpos : this.pos); m.position.z -= done ? 0 : .25;
      m.scale.setScalar(Math.max(.001, done ? this.tscale * (1 + t * 9) : this.scale * (1.2 + t * 1.9)));
    });

    const u = this.sky.uniforms, sp = this.tag.position.clone().project(this.camera);
    u.day.value = this.day; u.time.value = now; u.sun.value.set(sp.x * .5 + .5, sp.y * .5 + .5);
    this.key.intensity = 1.6 + this.day * 1.4; this.scene.environmentIntensity = .75 + this.day * .65;
    this.renderer.render(this.scene, this.camera);
  }

  /** Which tag the phone is on: true = the right one, false = the other, null = none. */
  over() {
    const near = (t, s) => Math.hypot(this.pos.x - t.x, this.pos.y - t.y) < Math.max(.5, s * 1.1) + this.scale * .3;
    if (near(this.tpos, this.tscale)) return true;
    return near(this.bad.position, this.badScale) && this.badScale > .05 ? false : null;
  }
  found(now) { this.waveAt = now; }
}

import * as THREE from 'three';
import { createScene } from './scene3d.js';
import { forceSimulation, forceLink, forceManyBody, forceCollide } from 'd3-force-3d';

const BLOB_URL = '/spaces/album-graph/blob';
const ATLAS_URL = '/spaces/album-graph/atlas/atlas-0.webp';
const WORKER_URL = '/js/album-graph/layout-worker.js';

const K = {
  w_act: 0.10, thr: 0.25, degmean: 4, degSigma: 1.5,
  spoke: 0.1, hubFrac: 0.1, hubdeg: 8,
  dist: 40, charge: -80, collide: 30, size: 15,
  nn: 15, mindist: 0.1,
};

const TOP_K = 32;

const $ = id => document.getElementById(id);
const statusEl = $('graph-status');
const hintsEl = $('graph-hints');
const infoEl = $('graph-info');
const selectEl = $('graph-select');
const radioEl = $('graph-radio');
const canvas = $('view');

function setStatus(t) { statusEl.textContent = t; }

async function fetchVerified(url) {
  const resp = await fetch(url);
  if (!resp.ok) throw new Error(`${url}: HTTP ${resp.status}`);
  const declared = +resp.headers.get('Content-Length');
  const buf = new Uint8Array(await resp.arrayBuffer());
  if (declared && buf.length !== declared)
    throw new Error(`${url}: truncated — got ${buf.length} of ${declared} bytes`);
  return buf;
}

async function gunzip(u8) {
  const stream = new Blob([u8]).stream().pipeThrough(new DecompressionStream('gzip'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

function crc32(u8) {
  if (!crc32.table) {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      t[n] = c;
    }
    crc32.table = t;
  }
  let crc = -1;
  for (let i = 0; i < u8.length; i++) crc = (crc >>> 8) ^ crc32.table[(crc ^ u8[i]) & 0xFF];
  return (crc ^ -1) >>> 0;
}

function parseBlob(u8) {
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  const magic = i => String.fromCharCode(u8[i], u8[i+1], u8[i+2], u8[i+3]);
  if (magic(0) !== 'ESSG') throw new Error('not an ESSG blob');
  let p = 4;
  const version = dv.getUint32(p, true); p += 4;
  const flags = dv.getUint32(p, true); p += 4;
  const build_ts = Number(dv.getBigUint64(p, true)); p += 8;
  const n = dv.getUint32(p, true); p += 4;
  const ncls = dv.getUint32(p, true); p += 4;
  const ndim = dv.getUint32(p, true); p += 4;
  if (version !== 2) throw new Error(`blob version ${version}, want 2`);
  const dec = new TextDecoder();
  const str = () => { const l = dv.getUint32(p, true); p += 4;
                      const s = dec.decode(u8.subarray(p, p+l)); p += l; return s; };
  function readF16(count) {
    const out = new Float32Array(count);
    for (let i = 0; i < count; i++, p += 2) {
      const h = dv.getUint16(p, true);
      const s = (h & 0x8000) >> 15, e = (h & 0x7C00) >> 10, f = h & 0x03FF;
      let v;
      if (e === 0) v = f / 1024 * Math.pow(2, -14);
      else if (e === 0x1F) v = f ? NaN : Infinity;
      else v = (1 + f / 1024) * Math.pow(2, e - 15);
      out[i] = s ? -v : v;
    }
    return out;
  }
  const class_names = Array.from({length: ncls}, str);
  const albums = [];
  for (let i = 0; i < n; i++) {
    const idx = dv.getUint32(p, true); p += 4;
    const artist = str(), album = str();
    const track_count = dv.getUint32(p, true); p += 4;
    const duration = dv.getFloat32(p, true); p += 4;
    const id_hash = dv.getBigUint64(p, true); p += 8;
    const parent_shares = readF16(ncls);
    if (idx !== i) throw new Error(`album index ${idx} != ${i}`);
    albums.push({ artist, album, track_count, duration, id_hash, parent_shares });
  }
  const act_vecs = readF16(n * ncls);
  const emb_vecs = readF16(n * ndim);
  const sim_act = readF16(n * n);
  const sim_emb = readF16(n * n);
  const sim_blend = readF16(n * n);
  const topk_ids = new Uint32Array(n * TOP_K);
  for (let i = 0; i < n * TOP_K; i++, p += 4) topk_ids[i] = dv.getUint32(p, true);
  const topk_sims = readF16(n * TOP_K);
  const images = { n_sheets: dv.getUint16(p, true), sheet_dim: dv.getUint16(p+2, true),
                   tile: dv.getUint16(p+4, true) };
  p += 8;
  images.tiles = [];
  for (let i = 0; i < n; i++) {
    images.tiles.push([dv.getUint16(p,true), dv.getUint16(p+2,true), dv.getUint16(p+4,true)]);
    p += 6;
  }
  images.sha256 = [];
  for (let i = 0; i < images.n_sheets; i++) {
    images.sha256.push(Array.from(u8.subarray(p, p+32)).map(b => b.toString(16).padStart(2,'0')).join(''));
    p += 32;
  }
  const storedCrc = dv.getUint32(p, true); p += 4;
  if (magic(p) !== 'ESSG') throw new Error('blob footer magic missing — truncated artifact?');
  if (crc32(u8.subarray(0, p - 4)) !== storedCrc)
    throw new Error('blob crc mismatch — artifact corrupt/truncated');
  return { version, flags, build_ts, n, ncls, ndim, class_names, albums,
           act_vecs, emb_vecs, sim_act, sim_emb, sim_blend, topk_ids, topk_sims, images };
}

const worker = new Worker(WORKER_URL);
const pendingQ = [];
worker.onmessage = ev => {
  const m = ev.data;
  if (m.type === 'progress') { setStatus(`layout: ${m.stage} ${Math.round(m.pct)}%`); return; }
  if (m.type === 'error') { setStatus('layout error: ' + m.error); console.error('worker:', m.error); return; }
  const p = pendingQ.shift();
  if (p) p.resolve(m);
};
function ask(msg, transfer = []) {
  return new Promise(resolve => {
    pendingQ.push({ resolve });
    worker.postMessage(msg, transfer);
  });
}

let blob, scene, sim;
let nodes = [], edges = [], edgeList = [], byId = new Map();
let hovered = null, selected = null, dragNode = null, focusAlbum = null;
let posArr = null;
let atlasImg;

const RBASE = '/spaces/album-graph/radio';
const SAME_ALBUM_CHANCE = 1 / 9;
const WALK_R0 = 40;
const WALK_RSTEP = 40;
const WALK_RMAX = 480;
const WALK_TARGET = 12;
const WALK_FALLBACK = 4;
const WALK_MOMENTUM = 0.65;
const WALK_BIAS = 5.5;
let walkVel = null;
const IDLE_MS = 10000;
const RPM = 0.8;
const RAMP_MS = 500;
const radio = {
  authed: false, user: null,
  album: null, node: null, songIdx: -1,
  audio: null, loading: false,
};
let lastCam = 0;
let lastTick = 0;
let orbitStart = 0;

function seedRng(seed) {
  let a = seed >>> 0;
  return function() {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function b64FromF32(f32) {
  const u8 = new Uint8Array(f32.buffer, f32.byteOffset, f32.byteLength);
  let s = '';
  for (let i = 0; i < u8.length; i += 0x8000)
    s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(s);
}
function f32FromB64(b64) {
  const bin = atob(b64);
  const u8 = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
  return new Float32Array(u8.buffer);
}
function cacheKey() {
  return `tagger3d:${blob.build_ts}:${K.w_act}:${K.nn}:${K.mindist}`;
}

async function ensurePositions() {
  const key = cacheKey();
  try {
    const hit = localStorage.getItem(key);
    if (hit) {
      const { p } = JSON.parse(hit);
      const pos = f32FromB64(p);
      if (pos.length === blob.n * 3) {
        setStatus('layout: cached');
        return pos;
      }
    }
  } catch { }
  const act = new Float32Array(blob.act_vecs);
  const emb = new Float32Array(blob.emb_vecs);
  const seed = blob.build_ts >>> 0;
  const res = await ask({ type: 'umap', n: blob.n, act, emb,
                          w_act: K.w_act, nNeighbors: K.nn,
                          minDist: K.mindist, seed }, [act.buffer, emb.buffer]);
  if (res.type !== 'umap-done') throw new Error('umap failed');
  if (res.fallback) setStatus('layout: UMAP unavailable, random init (relaxing)');
  try { localStorage.setItem(key, JSON.stringify({ p: b64FromF32(res.positions), ts: Date.now() })); }
  catch { }
  return res.positions;
}

async function requestLayout() {
  setStatus('layout: computing…');
  const pos = await ensurePositions();
  for (let i = 0; i < blob.n; i++) {
    const nd = nodes[i];
    if (!nd) continue;
    nd.x = pos[i*3] * 200; nd.y = pos[i*3+1] * 200; nd.z = pos[i*3+2] * 200;
    nd.vx = nd.vy = nd.vz = 0;
  }
  sim.alpha(1).restart();
  setStatus('layout ready');
}

async function requestEdges() {
  const simAct = new Float32Array(blob.sim_act);
  const simEmb = new Float32Array(blob.sim_emb);
  const res = await ask({ type: 'edges', n: blob.n, simAct, simEmb,
                          w_act: K.w_act, thr: K.thr,
                          degMean: K.degmean, degSigma: K.degSigma,
                          spokeFrac: K.spoke, hubFrac: K.hubFrac, hubDeg: K.hubdeg,
                          seed: blob.build_ts >>> 0 }, [simAct.buffer, simEmb.buffer]);
  if (res.type !== 'edges-done') throw new Error('edges failed');
  edgeList = res.edges;
  edges = edgeList.map(e => ({ source: e.i, target: e.j, sim: e.s, bridge: !!e.bridge }));
  scene.setGraph(new Int32Array(blob.n).map((_, i) => i), edges);
  scene.setPositions(posArr);
  scene.updateEdges(posArr);
  sim.nodes(nodes);
  sim.force('link').links(edges);
  sim.alpha(0.7).restart();
  return res;
}

function initGraph() {
  const fit = () => {
    const r = canvas.getBoundingClientRect();
    canvas.width = Math.max(r.width, 50); canvas.height = Math.max(r.height, 50);
  };
  fit(); addEventListener('resize', fit);
  const tex = new THREE.Texture(atlasImg);
  scene = createScene(canvas, {
    atlasTexture: tex,
    sheetDim: blob.images.sheet_dim || 8192,
    tile: blob.images.tile,
    tiles: blob.images.tiles,
    n: blob.n,
  });
  scene.setSizeScale(K.size);
  posArr = new Float32Array(blob.n * 3);
  const rng = seedRng(blob.build_ts >>> 0);
  for (let i = 0; i < blob.n; i++) {
    posArr[i*3] = (rng()-0.5) * 800; posArr[i*3+1] = (rng()-0.5) * 800; posArr[i*3+2] = (rng()-0.5) * 800;
  }
  nodes = blob.albums.map((a, i) => ({
    id: i, artist: a.artist, album: a.album, duration: a.duration,
    track_count: a.track_count, id_hash: String(a.id_hash),
    x: posArr[i*3], y: posArr[i*3+1], z: posArr[i*3+2], vx: 0, vy: 0, vz: 0,
  }));
  byId = new Map(nodes.map(nd => [nd.id, nd]));
  sim = forceSimulation(nodes).numDimensions(3)
    .force('link', forceLink(edges).id(d => d.id)
      .distance(l => K.dist * (1.15 - l.sim))
      .strength(l => 0.15 + 0.7 * l.sim))
    .force('charge', forceManyBody().strength(() => K.charge))
    .force('collide', forceCollide().radius(() => K.collide))
    .alphaDecay(0.02);
  wirePointer();
  requestAnimationFrame(tick);
}

function wirePointer() {
  let camGesture = false;
  let downAt = null;
  canvas.addEventListener('contextmenu', ev => ev.preventDefault());
  canvas.addEventListener('pointerdown', () => { lastCam = performance.now(); }, true);
  canvas.addEventListener('wheel', () => { lastCam = performance.now(); }, { capture: true, passive: true });
  canvas.parentElement.addEventListener('pointerdown', ev => {
    if (ev.target !== canvas) return;
    lastCam = performance.now();
    if (!blob || !sim) return;
    const rect = canvas.getBoundingClientRect();
    const hit = scene.pick(ev.clientX - rect.left, ev.clientY - rect.top);
    if (ev.button === 2 && hit != null) {
      dragNode = byId.get(hit);
      if (!dragNode) return;
      ev.stopImmediatePropagation();
      ev.preventDefault();
      scene.controls.enabled = false;
      canvas.setPointerCapture(ev.pointerId);
      dragNode.fx = dragNode.x; dragNode.fy = dragNode.y; dragNode.fz = dragNode.z;
      sim.alphaTarget(0.3).restart();
      canvas.classList.add('dragging');
      camGesture = false;
    } else if (ev.button === 0 && hit != null) {
      ev.stopImmediatePropagation();
      selectAlbum(byId.get(hit));
      camGesture = false;
    } else {
      if (ev.button === 0) downAt = { x: ev.clientX, y: ev.clientY };
      camGesture = true;
    }
  }, true);

  canvas.addEventListener('pointermove', ev => {
    if (dragNode) {
      lastCam = performance.now();
      const rect = canvas.getBoundingClientRect();
      const p = scene.planeDragPoint(ev.clientX - rect.left, ev.clientY - rect.top, dragNode.id);
      if (p) {
        dragNode.fx = p.x; dragNode.fy = p.y; dragNode.fz = p.z;
        dragNode.x = p.x; dragNode.y = p.y; dragNode.z = p.z;
      }
      ev.preventDefault();
      return;
    }
    if (camGesture || selected) return;
    const rect = canvas.getBoundingClientRect();
    const hit = scene.pick(ev.clientX - rect.left, ev.clientY - rect.top);
    setHovered(hit == null ? null : byId.get(hit));
  });

  const endDrag = ev => {
    if (dragNode) {
      dragNode.fx = dragNode.fy = dragNode.fz = null;
      dragNode = null;
      sim.alphaTarget(0);
      scene.controls.enabled = true;
      canvas.classList.remove('dragging');
    } else if (downAt && ev.button === 0) {
      const dx = ev.clientX - downAt.x, dy = ev.clientY - downAt.y;
      if (dx * dx + dy * dy < 36) closeSelect();
    }
    downAt = null;
    camGesture = false;
  };
  canvas.addEventListener('pointerup', endDrag, true);
  canvas.addEventListener('pointercancel', endDrag, true);
}

const HINTS_BASE = 'LMB orbit · RMB pan';
const HINTS_HOVER = 'RMB drag · LMB select · F re-center';

function styleName(name) {
  const k = name.lastIndexOf('---');
  return k < 0 ? name : name.slice(k + 3);
}

function rankedStyles(i, count) {
  const acts = [];
  for (let k = 0; k < blob.ncls; k++)
    acts.push([styleName(blob.class_names[k]), blob.act_vecs[i*blob.ncls+k]]);
  acts.sort((a, b) => b[1] - a[1]);
  return acts.slice(0, count).map(([name], r) => `${r + 1}. ${name}`);
}

function nodeLinks(i) {
  return edgeList
    .filter(e => e.i === i || e.j === i)
    .map(e => {
      const a = blob.albums[e.i === i ? e.j : e.i];
      return `${a.artist} — ${a.album}`;
    });
}

function fmtDur(d) {
  const m = Math.round(d / 60);
  return m >= 60 ? `${Math.floor(m / 60)} hr ${m % 60} min` : `${m} min`;
}

function applyEmphasis() {
  const h = hovered ? hovered.id : null;
  const s = selected ? selected.id : null;
  scene.setEmphasis(h, s);
  scene.setSelected(s);
}

function setHovered(nd) {
  if (nd === hovered) return;
  hovered = nd;
  applyEmphasis();
  hintsEl.textContent = nd ? HINTS_HOVER : HINTS_BASE;
  if (nd && !selected) {
    focusAlbum = nd.id;
    const i = nd.id;
    infoEl.innerHTML = `<b>${nd.artist} — ${nd.album}</b><br>` +
      `${nd.track_count} tracks, ${fmtDur(nd.duration)}<br>` +
      `Styles: ${rankedStyles(i, 5).join(' ')}<br>` +
      `Links:<br>${nodeLinks(i).join('<br>')}`;
    infoEl.hidden = false;
  } else {
    infoEl.hidden = true;
  }
}

function setSelection(nd) {
  selected = nd;
  hovered = null;
  focusAlbum = nd.id;
  const i = nd.id;
  selectEl.innerHTML = `<b>${nd.artist} — ${nd.album}</b><br>` +
    `${nd.track_count} tracks, ${fmtDur(nd.duration)}<br><br>` +
    `Styles:<br>${rankedStyles(i, 10).join('<br>')}<br><br>` +
    `Links:<br>${nodeLinks(i).join('<br>')}`;
  selectEl.hidden = false;
  infoEl.hidden = true;
  applyEmphasis();
}

function selectAlbum(nd) {
  setSelection(nd);
  const wasPlaying = radio.audio && !radio.audio.paused;
  if (wasPlaying) radio.audio.pause();
  if (radio.authed) {
    radioLoadAlbum(nd.id).then(data => {
      if (data && wasPlaying) startSong();
    });
  }
  renderRadio();
}

function closeSelect() {
  if (!selected) return;
  selected = null;
  hovered = null;
  hintsEl.textContent = HINTS_BASE;
  selectEl.hidden = true;
  radioStop();
  radioEl.hidden = true;
  applyEmphasis();
}

async function radioLoadAlbum(nodeId) {
  const nd = byId.get(nodeId);
  if (!nd) return null;
  radio.loading = true;
  try {
    const resp = await fetch(`${RBASE}/album?id_hash=${nd.id_hash}` +
      `&artist=${encodeURIComponent(nd.artist)}&album=${encodeURIComponent(nd.album)}`);
    if (resp.status === 401) {
      radio.authed = false;
      renderRadio();
      return null;
    }
    if (!resp.ok) throw new Error(`radio album: HTTP ${resp.status}`);
    radio.album = await resp.json();
    radio.node = nodeId;
    const songs = radio.album.songs;
    radio.songIdx = songs.length
      ? Math.floor(Math.random() * songs.length) : -1;
    renderRadio();
    return radio.album;
  } catch (e) {
    console.error(e);
    return null;
  } finally {
    radio.loading = false;
  }
}

function renderRadio() {
  if (!selected) { radioEl.hidden = true; return; }
  radioEl.hidden = false;
  if (!radio.authed) {
    radioEl.innerHTML = `<b>radio</b><br>` +
      `<form id="r-login">` +
      `<input id="r-user" placeholder="username" autocomplete="username">` +
      `<input id="r-pass" type="password" placeholder="password" autocomplete="current-password">` +
      `<button type="submit">log in</button></form>` +
      `<div class="disclaimer">logging in uses a session cookie for player access</div>`;
    return;
  }
  if (!radio.album) {
    radioEl.innerHTML = `<b>radio</b><br>loading…`;
    return;
  }
  const cov = radio.album.coverArt;
  radioEl.innerHTML = (cov ? `<img id="r-cover" alt="" src="${RBASE}/cover?id=${encodeURIComponent(cov)}">` : '') +
    `<div class="mq"><span id="r-track"></span></div>` +
    `<div class="mq"><span id="r-album">${esc(radio.album.name)}</span></div>` +
    `<div class="mq small"><span id="r-artist">${esc(radio.album.artist)}</span></div>` +
    `<div class="rbtns"><button id="r-play">play</button><button id="r-skip">skip</button></div>`;
  updatePlayerUI();
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
}

function setupMarquees() {
  for (const row of radioEl.querySelectorAll('.mq')) {
    const span = row.firstElementChild;
    const over = span.scrollWidth - row.clientWidth;
    if (over > 4) {
      row.style.setProperty('--mq-over', -(over + 8) + 'px');
      row.style.setProperty('--mq-dur', Math.max(6, (over + 8) / 22) + 's');
    } else {
      row.style.removeProperty('--mq-over');
      row.style.removeProperty('--mq-dur');
    }
  }
}

function updatePlayerUI() {
  const s = radio.album && radio.album.songs[radio.songIdx];
  const trackEl = $('r-track'), playBtn = $('r-play');
  if (trackEl && s) trackEl.textContent = s.title;
  if (playBtn) playBtn.textContent = radio.playing ? 'pause' : 'play';
  setupMarquees();
}

function startSong() {
  if (!radio.album || !radio.album.songs.length) return;
  if (radio.songIdx < 0) radio.songIdx = 0;
  const s = radio.album.songs[radio.songIdx];
  radio.audio.src = `${RBASE}/stream?id=${encodeURIComponent(s.id)}`;
  radio.audio.play().catch(e => console.error('play:', e.message));
  updatePlayerUI();
}

function radioStop() {
  if (!radio.audio) return;
  radio.audio.pause();
  radio.audio.removeAttribute('src');
  radio.audio.load();
  radio.album = null;
  radio.node = null;
  radio.songIdx = -1;
  radio.playing = false;
}

function pickWeighted(cands) {
  let total = 0;
  for (const c of cands) total += c.w;
  let r = Math.random() * total;
  for (const c of cands) {
    r -= c.w;
    if (r <= 0) return c.j;
  }
  return cands[cands.length - 1].j;
}

async function walkNext() {
  if (!radio.album || radio.node == null) return;
  const songs = radio.album.songs;
  if (Math.random() < SAME_ALBUM_CHANCE && songs.length > 1) {
    let idx;
    do { idx = Math.floor(Math.random() * songs.length); }
    while (idx === radio.songIdx);
    radio.songIdx = idx;
    startSong();
    return;
  }
  const cur = radio.node;
  const cx = posArr[cur*3], cy = posArr[cur*3+1], cz = posArr[cur*3+2];
  const vhat = walkVel;
  const bias = c => {
    if (!vhat) return 1;
    const d = Math.sqrt(c.d2) + 1e-6;
    const dot = (c.dx*vhat[0] + c.dy*vhat[1] + c.dz*vhat[2]) / d;
    return Math.pow((dot + 1) / 2, WALK_BIAS);
  };
  let cands = [];
  for (let r = WALK_R0; r <= WALK_RMAX; r += WALK_RSTEP) {
    cands = [];
    const r2 = r * r;
    for (let j = 0; j < blob.n; j++) {
      if (j === cur) continue;
      const dx = posArr[j*3] - cx, dy = posArr[j*3+1] - cy, dz = posArr[j*3+2] - cz;
      const d2 = dx*dx + dy*dy + dz*dz;
      if (d2 <= r2) cands.push({ j, d2, dx, dy, dz, w: 0 });
    }
    if (cands.length >= WALK_TARGET) break;
  }
  for (const c of cands) c.w = bias(c) / (Math.sqrt(c.d2) + 1e-6);
  if (!cands.length) {
    const all = [];
    for (let j = 0; j < blob.n; j++) {
      if (j === cur) continue;
      const dx = posArr[j*3] - cx, dy = posArr[j*3+1] - cy, dz = posArr[j*3+2] - cz;
      all.push({ j, d2: dx*dx + dy*dy + dz*dz, dx, dy, dz });
    }
    all.sort((a, b) => a.d2 - b.d2);
    const fwd = vhat ? all.filter(c =>
      (c.dx*vhat[0] + c.dy*vhat[1] + c.dz*vhat[2]) /
      (Math.sqrt(c.d2) + 1e-6) > 0) : all;
    for (const c of (fwd.length ? fwd : all).slice(0, WALK_FALLBACK))
      cands.push({ j: c.j, d2: c.d2, dx: c.dx, dy: c.dy, dz: c.dz,
                   w: 1 / (Math.sqrt(c.d2) + 1e-6) });
  }
  const j = pickWeighted(cands);
  const nx = posArr[j*3] - cx, ny = posArr[j*3+1] - cy, nz = posArr[j*3+2] - cz;
  const nl = Math.sqrt(nx*nx + ny*ny + nz*nz) + 1e-6;
  const step = [nx/nl, ny/nl, nz/nl];
  if (walkVel) {
    const a = WALK_MOMENTUM;
    let vx = a*walkVel[0] + (1-a)*step[0];
    let vy = a*walkVel[1] + (1-a)*step[1];
    let vz = a*walkVel[2] + (1-a)*step[2];
    const vl = Math.sqrt(vx*vx + vy*vy + vz*vz) + 1e-6;
    walkVel = [vx/vl, vy/vl, vz/vl];
  } else {
    walkVel = step;
  }
  setSelection(byId.get(j));
  refocusOn(j);
  const data = await radioLoadAlbum(j);
  if (data && data.songs.length) startSong();
}

function initRadio() {
  radio.audio = new Audio();
  radio.audio.preload = 'auto';
  radio.audio.addEventListener('play', () => {
    radio.playing = true;
    updatePlayerUI();
  });
  radio.audio.addEventListener('pause', () => {
    radio.playing = false;
    updatePlayerUI();
  });
  radio.audio.addEventListener('ended', async () => {
    const s = radio.album && radio.album.songs[radio.songIdx];
    if (s) {
      try {
        await fetch(`${RBASE}/scrobble?id=${encodeURIComponent(s.id)}`, { method: 'POST' });
      } catch { }
    }
    walkNext();
  });
  radio.audio.addEventListener('error', () => {
    if (radio.album && radio.songIdx >= 0) walkNext();
  });
  fetch(`${RBASE}/status`).then(r => {
    if (r.ok) return r.json();
    throw new Error('no session');
  }).then(d => {
    radio.authed = true;
    radio.user = d.user;
    if (selected) renderRadio();
  }).catch(() => {
    radio.authed = false;
  });

  radioEl.addEventListener('submit', async ev => {
    if (ev.target.id !== 'r-login') return;
    ev.preventDefault();
    const user = $('r-user').value, pass = $('r-pass').value;
    const resp = await fetch(`${RBASE}/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ username: user, password: pass }),
    });
    if (resp.ok) {
      radio.authed = true;
      radio.user = user;
      renderRadio();
      if (selected) selectAlbum(selected);
    } else {
      const d = await resp.json().catch(() => ({}));
      const dis = radioEl.querySelector('.disclaimer');
      if (dis) dis.textContent = d.error || 'login failed';
    }
  });
  radioEl.addEventListener('click', ev => {
    if (ev.target.id === 'r-play') {
      if (!radio.audio.src) startSong();
      else if (radio.audio.paused) radio.audio.play();
      else radio.audio.pause();
    }
    if (ev.target.id === 'r-skip') walkNext();
  });
}

function rotateCamera(theta) {
  const cam = scene.camera, target = scene.controls.target;
  const off = new THREE.Vector3().subVectors(cam.position, target);
  off.applyAxisAngle(new THREE.Vector3(0, 1, 0), theta);
  cam.position.copy(target).add(off);
}

function refocusOn(nodeId) {
  if (nodeId == null || !byId.has(nodeId)) return;
  const i = nodeId;
  const newTarget = new THREE.Vector3(posArr[i*3], posArr[i*3+1], posArr[i*3+2]);
  const t0 = scene.controls.target, cam = scene.camera;
  const dir = new THREE.Vector3().subVectors(cam.position, t0);
  const dist = dir.length();
  const newCam = newTarget.clone().add(dir.setLength(dist));
  const fromT = t0.clone(), fromC = cam.position.clone();
  const start = performance.now();
  const anim = now => {
    const u = Math.min((now - start) / 500, 1);
    const e = 1 - Math.pow(1 - u, 3);
    scene.controls.target.lerpVectors(fromT, newTarget, e);
    cam.position.lerpVectors(fromC, newCam, e);
    if (u < 1) requestAnimationFrame(anim);
  };
  requestAnimationFrame(anim);
}

addEventListener('keydown', ev => {
  if (ev.key === 'Escape') { closeSelect(); return; }
  if (ev.key !== 'f' && ev.key !== 'F') return;
  const ae = document.activeElement;
  if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA' || ae.isContentEditable)) return;
  if (focusAlbum == null || !byId.has(focusAlbum)) return;
  ev.preventDefault();
  lastCam = performance.now();
  refocusOn(focusAlbum);
});

function tick() {
  const now = performance.now();
  const dt = Math.min((now - lastTick) / 1000, 0.1);
  lastTick = now;
  for (let i = 0; i < nodes.length; i++) {
    const nd = nodes[i];
    posArr[nd.id*3] = nd.x || 0; posArr[nd.id*3+1] = nd.y || 0; posArr[nd.id*3+2] = nd.z || 0;
  }
  scene.setPositions(posArr);
  scene.updateEdges(posArr);
  if (radio.playing && now - lastCam > IDLE_MS) {
    if (!orbitStart) orbitStart = now;
    const ramp = Math.min((now - orbitStart) / RAMP_MS, 1);
    rotateCamera(Math.PI * 2 / 60 * RPM * ramp * dt);
  } else orbitStart = 0;
  requestAnimationFrame(tick);
}

async function loadArtifacts() {
  setStatus('fetching blob…');
  const raw = await fetchVerified(BLOB_URL);
  blob = parseBlob(await gunzip(raw));
  setStatus(`blob ok: ${blob.n} albums — fetching atlas…`);
  atlasImg = new Image();
  atlasImg.src = ATLAS_URL;
  await atlasImg.decode();
  setStatus(`atlas ok (${atlasImg.width}x${atlasImg.height}) — preparing scene…`);
}

loadArtifacts()
  .then(() => {
    initGraph();
    initRadio();
    lastCam = performance.now();
    requestEdges().then(() => {
      requestLayout().then(() => {
        setStatus(`${blob.n} albums · ${edges.length} edges`);
        hintsEl.textContent = HINTS_BASE;
        hintsEl.hidden = false;
      });
    });
  })
  .catch(e => { setStatus('FAILED: ' + e.message); console.error(e); });

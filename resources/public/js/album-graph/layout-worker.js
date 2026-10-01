'use strict';

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function rankScale(m, n) {
  const cnt = (n * (n - 1)) / 2;
  const vals = new Float32Array(cnt);
  let c = 0;
  for (let i = 0; i < n; i++) {
    const row = i * n;
    for (let j = i + 1; j < n; j++) vals[c++] = m[row + j];
  }
  const order = new Uint32Array(cnt);
  for (let k = 0; k < cnt; k++) order[k] = k;
  order.sort((a, b) => vals[a] - vals[b] || a - b);
  const pct = new Float32Array(cnt);
  const denom = cnt > 1 ? cnt - 1 : 1;
  for (let r = 0; r < cnt; r++) pct[order[r]] = r / denom;
  const out = new Float32Array(n * n);
  c = 0;
  for (let i = 0; i < n; i++) {
    const row = i * n;
    for (let j = i + 1; j < n; j++) {
      const v = pct[c++];
      out[row + j] = v;
      out[j * n + i] = v;
    }
  }
  return out;
}

function blendChannels(simAct, simEmb, n, w_act) {
  const ra = rankScale(simAct, n);
  const re = rankScale(simEmb, n);
  const out = new Float32Array(n * n);
  const wa = w_act, we = 1 - w_act;
  for (let k = 0; k < n * n; k++) out[k] = wa * ra[k] + we * re[k];
  return out;
}

function computeTargets(n, spokeFrac, hubFrac, hubDeg, degMean, degSigma, seed) {
  const rng = mulberry32(seed);
  const target = new Int32Array(n);
  const spokenCut = spokeFrac;
  const hubCut = spokeFrac + hubFrac;
  for (let i = 0; i < n; i++) {
    const r = rng();
    if (r < spokenCut) {
      target[i] = 1;
    } else if (r < hubCut) {
      target[i] = hubDeg;
    } else {
      const u1 = rng() || 1e-12;
      const u2 = rng();
      const z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
      let d = Math.round(degMean + degSigma * z);
      if (d < 2) d = 2;
      if (d > hubDeg) d = hubDeg;
      target[i] = d;
    }
  }
  return target;
}

function buildPool(blend, n, topC, thr) {
  const seen = new Uint8Array(n * n);
  const pI = [];
  const pJ = [];
  const pS = [];
  const bj = new Int32Array(topC);
  const bs = new Float32Array(topC);
  for (let i = 0; i < n; i++) {
    let cnt = 0;
    const row = i * n;
    for (let j = 0; j < n; j++) {
      if (j === i) continue;
      const s = blend[row + j];
      if (s < thr) continue;
      if (cnt < topC) {
        let k = cnt;
        while (k > 0 && bs[k - 1] < s) { bj[k] = bj[k - 1]; bs[k] = bs[k - 1]; k--; }
        bj[k] = j; bs[k] = s; cnt++;
      } else if (s > bs[topC - 1]) {
        let k = topC - 1;
        while (k > 0 && bs[k - 1] < s) { bj[k] = bj[k - 1]; bs[k] = bs[k - 1]; k--; }
        bj[k] = j; bs[k] = s;
      }
    }
    for (let k = 0; k < cnt; k++) {
      const j = bj[k];
      const a = i < j ? i : j;
      const b = i < j ? j : i;
      const key = a * n + b;
      if (seen[key]) continue;
      seen[key] = 1;
      pI.push(a); pJ.push(b); pS.push(bs[k]);
    }
  }
  return { pI: pI, pJ: pJ, pS: pS, count: pS.length };
}

function selectEdges(pool, n, target) {
  const count = pool.count;
  const pI = pool.pI, pJ = pool.pJ, pS = pool.pS;
  const deg = new Int32Array(n);
  const selI = [], selJ = [], selS = [];

  const inc = new Array(n);
  for (let e = 0; e < count; e++) {
    const i = pI[e], j = pJ[e];
    if (target[i] === 1) { (inc[i] || (inc[i] = [])).push(e << 1); }
    if (target[j] === 1) { (inc[j] || (inc[j] = [])).push((e << 1) | 1); }
  }
  for (let i = 0; i < n; i++) {
    if (inc[i]) inc[i].sort((a, b) => pS[b >> 1] - pS[a >> 1]);
  }
  const spokeOrder = [];
  for (let i = 0; i < n; i++) if (target[i] === 1 && inc[i]) spokeOrder.push(i);
  spokeOrder.sort((a, b) => pS[inc[b][0] >> 1] - pS[inc[a][0] >> 1]);
  for (let si = 0; si < spokeOrder.length; si++) {
    const i = spokeOrder[si];
    if (deg[i] >= target[i]) continue;
    const list = inc[i];
    for (let c = 0; c < list.length; c++) {
      const tag = list[c], e = tag >> 1, other = (tag & 1) ? pI[e] : pJ[e];
      if (deg[other] < target[other]) {
        deg[i]++; deg[other]++;
        selI.push(i); selJ.push(other); selS.push(pS[e]);
        break;
      }
    }
  }

  const order = new Uint32Array(count);
  for (let k = 0; k < count; k++) order[k] = k;
  order.sort((a, b) => pS[b] - pS[a] || a - b);
  for (let o = 0; o < count; o++) {
    const e = order[o];
    const i = pI[e], j = pJ[e];
    if (deg[i] < target[i] && deg[j] < target[j]) {
      deg[i]++; deg[j]++;
      selI.push(i); selJ.push(j); selS.push(pS[e]);
    }
  }
  return { selI: selI, selJ: selJ, selS: selS, deg: deg };
}

function ufFind(parent, x) {
  while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x]; }
  return x;
}
function ufUnion(parent, a, b) {
  const ra = ufFind(parent, a), rb = ufFind(parent, b);
  if (ra === rb) return false;
  parent[rb] = ra;
  return true;
}

function repairConnectivity(blend, pool, n, selI, selJ) {
  const parent = new Int32Array(n);
  for (let i = 0; i < n; i++) parent[i] = i;
  for (let e = 0; e < selI.length; e++) ufUnion(parent, selI[e], selJ[e]);
  let comps = 0;
  for (let i = 0; i < n; i++) if (ufFind(parent, i) === i) comps++;
  const nComponentsBefore = comps;

  const bI = [], bJ = [], bS = [];
  const pI = pool.pI, pJ = pool.pJ, pS = pool.pS, pn = pool.count;

  while (comps > 1) {
    let bi = -1, bj = -1, bs = -Infinity;
    for (let e = 0; e < pn; e++) {
      if (ufFind(parent, pI[e]) !== ufFind(parent, pJ[e]) && pS[e] > bs) {
        bs = pS[e]; bi = pI[e]; bj = pJ[e];
      }
    }
    if (bi < 0) {
      const root = new Int32Array(n);
      for (let i = 0; i < n; i++) root[i] = ufFind(parent, i);
      for (let i = 0; i < n; i++) {
        const ri = root[i], row = i * n;
        for (let j = i + 1; j < n; j++) {
          if (root[j] !== ri) {
            const s = blend[row + j];
            if (s > bs) { bs = s; bi = i; bj = j; }
          }
        }
      }
    }
    if (bi < 0) break;
    bI.push(bi); bJ.push(bj); bS.push(bs);
    ufUnion(parent, bi, bj);
    comps--;
  }
  return { bI: bI, bJ: bJ, bS: bS, nComponentsBefore: nComponentsBefore };
}

function computeEdges(opts) {
  const n = opts.n;
  const simAct = opts.simAct, simEmb = opts.simEmb;
  const w_act = opts.w_act;
  const thr = opts.thr;
  const degMean = opts.degMean, degSigma = opts.degSigma;
  const spokeFrac = opts.spokeFrac, hubFrac = opts.hubFrac, hubDeg = opts.hubDeg;
  const seed = opts.seed >>> 0;
  const report = typeof opts.onProgress === 'function' ? opts.onProgress : function () {};
  const t0 = Date.now();

  report('edges-rank', 0);
  const blender = blendChannels(simAct, simEmb, n, w_act);
  report('edges-rank', 40);

  const target = computeTargets(n, spokeFrac, hubFrac, hubDeg, degMean, degSigma, seed);
  const topC = Math.max(hubDeg, 12);
  const pool = buildPool(blender, n, topC, thr);
  report('edges-pool', 60);

  const sel = selectEdges(pool, n, target);
  report('edges-select', 80);

  const rep = repairConnectivity(blender, pool, n, sel.selI, sel.selJ);
  report('edges-repair', 95);

  const m = sel.selI.length + rep.bI.length;
  const edges = new Array(m);
  for (let e = 0; e < sel.selI.length; e++) {
    edges[e] = { i: sel.selI[e], j: sel.selJ[e], s: sel.selS[e], bridge: false };
  }
  for (let e = 0; e < rep.bI.length; e++) {
    edges[sel.selI.length + e] = { i: rep.bI[e], j: rep.bJ[e], s: rep.bS[e], bridge: true };
  }
  edges.sort((a, b) => b.s - a.s || a.i - b.i || a.j - b.j);

  const deg = sel.deg;
  let degMin = Infinity, degMax = 0, sum = 0;
  for (let e = 0; e < rep.bI.length; e++) { deg[rep.bI[e]]++; deg[rep.bJ[e]]++; }
  const hist = {};
  for (let i = 0; i < n; i++) {
    const d = deg[i];
    if (d < degMin) degMin = d;
    if (d > degMax) degMax = d;
    sum += d;
    hist[d] = (hist[d] || 0) + 1;
  }

  report('edges-done', 100);
  return {
    edges: edges,
    stats: {
      nEdges: m,
      bridges: rep.bI.length,
      degHist: hist,
      nComponentsBefore: rep.nComponentsBefore,
      degMin: n ? degMin : 0,
      degMax: degMax,
      degMean: n ? sum / n : 0,
      ms: Date.now() - t0
    }
  };
}

function prepareUmapInput(act, emb, n, w_act) {
  const ad = act.length / n;
  const ed = emb.length / n;
  const dim = ad + ed;
  const sa = Math.sqrt(w_act), se = Math.sqrt(1 - w_act);
  const X = new Array(n);
  for (let i = 0; i < n; i++) {
    const row = new Float32Array(dim);
    const ao = i * ad, eo = i * ed;
    for (let k = 0; k < ad; k++) row[k] = sa * act[ao + k];
    for (let k = 0; k < ed; k++) row[ad + k] = se * emb[eo + k];
    X[i] = row;
  }
  return { X: X, n: n, dim: dim };
}

function cosineDistance(x, y) {
  let dot = 0, nx = 0, ny = 0;
  for (let k = 0; k < x.length; k++) { const a = x[k], b = y[k]; dot += a * b; nx += a * a; ny += b * b; }
  const d = Math.sqrt(nx) * Math.sqrt(ny);
  return d > 1e-12 ? 1 - dot / d : 1;
}

function centerNormalizePositions(pos, n) {
  if (n <= 0) return pos;
  let cx = 0, cy = 0, cz = 0;
  for (let i = 0; i < n; i++) { cx += pos[i * 3]; cy += pos[i * 3 + 1]; cz += pos[i * 3 + 2]; }
  cx /= n; cy /= n; cz /= n;
  let s = 0;
  for (let i = 0; i < n; i++) {
    const x = pos[i * 3] - cx, y = pos[i * 3 + 1] - cy, z = pos[i * 3 + 2] - cz;
    pos[i * 3] = x; pos[i * 3 + 1] = y; pos[i * 3 + 2] = z;
    s += x * x + y * y + z * z;
  }
  const rms = Math.sqrt(s / n);
  if (rms > 1e-12) { const k = 1 / rms; for (let i = 0; i < n * 3; i++) pos[i] *= k; }
  return pos;
}

function fallbackSphere(n, seed) {
  const rng = mulberry32(seed >>> 0);
  const pos = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) {
    const u = rng() * 2 - 1;
    const th = rng() * 2 * Math.PI;
    const r = Math.sqrt(Math.max(0, 1 - u * u));
    pos[i * 3] = r * Math.cos(th);
    pos[i * 3 + 1] = r * Math.sin(th);
    pos[i * 3 + 2] = u;
  }
  return centerNormalizePositions(pos, n);
}

async function handleMessage(msg, ctx) {
  ctx = ctx || {};
  const post = function (stage, pct) { if (ctx.postProgress) ctx.postProgress(stage, pct); };
  const emit = ctx.emit || function () {};

  if (!msg) { emit({ type: 'error', error: 'null message' }); return; }

  if (msg.type === 'umap') {
    const n = msg.n;
    let positions;
    try {
      const prep = prepareUmapInput(msg.act, msg.emb, n, msg.w_act);
      post('umap-load', 0);
      let lib = null;
      try { lib = ctx.getUMAP ? ctx.getUMAP() : null; } catch (e) { lib = null; }
      if (!lib) throw new Error('umap library unavailable');
      const Cls = (typeof lib === 'function') ? lib : (lib.UMAP || null);
      if (typeof Cls !== 'function') throw new Error('UMAP class missing');
      post('umap-fit', 5);
      const umap = new Cls({
        nComponents: 3,
        nNeighbors: msg.nNeighbors || 15,
        minDist: (msg.minDist != null) ? msg.minDist : 0.1,
        distanceFn: cosineDistance,
        random: mulberry32((msg.seed >>> 0) || 1)
      });
      let out = umap.fit(prep.X);
      if (out && typeof out.then === 'function') out = await out;
      if (!out || out.length !== n) throw new Error('bad embedding length');
      positions = new Float32Array(n * 3);
      for (let i = 0; i < n; i++) {
        const r = out[i];
        if (!r || r.length < 3 || !isFinite(r[0]) || !isFinite(r[1]) || !isFinite(r[2]))
          throw new Error('non-numeric embedding row ' + i);
        positions[i * 3] = r[0]; positions[i * 3 + 1] = r[1]; positions[i * 3 + 2] = r[2];
      }
      post('umap-post', 95);
      positions = centerNormalizePositions(positions, n);
      emit({ type: 'umap-done', positions: positions }, [positions.buffer]);
    } catch (err) {
      const fb = fallbackSphere(n, (msg.seed >>> 0) || 1);
      emit({ type: 'umap-done', positions: fb, fallback: true,
             error: String((err && err.message) || err) }, [fb.buffer]);
    }
    return;
  }

  if (msg.type === 'edges') {
    const res = computeEdges({
      n: msg.n, simAct: msg.simAct, simEmb: msg.simEmb, w_act: msg.w_act, thr: msg.thr,
      degMean: msg.degMean, degSigma: msg.degSigma, spokeFrac: msg.spokeFrac,
      hubFrac: msg.hubFrac, hubDeg: msg.hubDeg, seed: msg.seed,
      onProgress: function (stage, pct) { post(stage, pct); }
    });
    emit({ type: 'edges-done', edges: res.edges, stats: res.stats });
    return;
  }

  emit({ type: 'error', error: 'unknown op ' + (msg && msg.type) });
}

if (typeof importScripts === 'function') {
  var UMAP_LIB_URL = '/js/album-graph/vendor/umap-js.min.js';
  var _umapLib = null;
  var _umapTried = false;
  function getUMAP() {
    if (_umapTried) return _umapLib;
    _umapTried = true;
    try {
      importScripts(UMAP_LIB_URL);
      _umapLib = (typeof UMAP !== 'undefined') ? UMAP : (self.UMAP || null);
    } catch (e) {
      _umapLib = null;
    }
    return _umapLib;
  }
  self.onmessage = function (e) {
    handleMessage(e.data, {
      getUMAP: getUMAP,
      postProgress: function (stage, pct) { self.postMessage({ type: 'progress', stage: stage, pct: pct }); },
      emit: function (obj, transfer) { self.postMessage(obj, transfer || []); }
    });
  };
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    mulberry32: mulberry32, rankScale: rankScale, blendChannels: blendChannels,
    computeTargets: computeTargets, buildPool: buildPool, selectEdges: selectEdges,
    ufFind: ufFind, ufUnion: ufUnion, repairConnectivity: repairConnectivity,
    computeEdges: computeEdges, prepareUmapInput: prepareUmapInput,
    cosineDistance: cosineDistance, centerNormalizePositions: centerNormalizePositions,
    fallbackSphere: fallbackSphere, handleMessage: handleMessage
  };
}

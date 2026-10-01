import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const FOG_COLOR = 0xf3f3f3;
const FOG_DENSITY = 0.00025;
const FOG_NEAR = 300;
const EDGE_OPACITY = 0.45;
const DIM_ALPHA = 0.25;
const SEL_BORDER = 0.06;
const SEL_BUMP = 1.15;

const NODE_VERT = `
  attribute vec2 aUvOffset;
  attribute vec2 aUvScale;
  attribute float aAlpha;
  attribute vec3 aColor;
  attribute float aSel;

  varying vec2  vUv;
  varying float vAlpha;
  varying vec3  vColor;
  varying float vFogDepth;
  varying vec2  vQuad;
  varying float vSel;

  void main() {
    vUv    = aUvOffset + uv * aUvScale;
    vAlpha = aAlpha;
    vColor = aColor;
    vQuad  = uv;
    vSel   = aSel;

    vec3  instPos = instanceMatrix[3].xyz;
    float sx = length(instanceMatrix[0].xyz);
    float sy = length(instanceMatrix[1].xyz);

    if (sx <= 0.0 || sy <= 0.0) {
      gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
      return;
    }

    vec4 viewPos = modelViewMatrix * vec4(instPos, 1.0);
    viewPos.xy += position.xy * vec2(sx, sy);
    vFogDepth = length(viewPos.xyz);
    gl_Position = projectionMatrix * viewPos;
  }
`;

const NODE_FRAG = `
  uniform sampler2D uAtlas;
  uniform vec3  uFogColor;
  uniform float uFogDensity;
  uniform float uFogNear;
  uniform vec3  uSelColor;

  varying vec2  vUv;
  varying float vAlpha;
  varying vec3  vColor;
  varying float vFogDepth;
  varying vec2  vQuad;
  varying float vSel;

  void main() {
    vec4 tex = texture2D(uAtlas, vUv);
    if (tex.a < 0.02) discard;

    vec3 col = tex.rgb * vColor;

    float depth = max(vFogDepth - uFogNear, 0.0);
    float f = uFogDensity * depth;
    f = 1.0 - exp(-f * f);
    col = mix(col, uFogColor, clamp(f, 0.0, 1.0));

    col = mix(col, uFogColor, 1.0 - vAlpha);

    if (vSel > 0.5) {
      float d = min(min(vQuad.x, 1.0 - vQuad.x), min(vQuad.y, 1.0 - vQuad.y));
      col = mix(col, uSelColor, step(d, ${SEL_BORDER}));
    }

    gl_FragColor = vec4(col, 1.0);

    #include <tonemapping_fragment>
    #include <colorspace_fragment>
  }
`;

export function createScene(canvas, opts) {
  opts = opts || {};
  const atlasTexture = opts.atlasTexture;
  const sheetDim = opts.sheetDim || 8192;
  const tile = opts.tile || 128;
  const tiles = opts.tiles || [];

  if (atlasTexture && atlasTexture.colorSpace !== THREE.SRGBColorSpace) {
    atlasTexture.colorSpace = THREE.SRGBColorSpace;
    atlasTexture.needsUpdate = true;
  }

  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
  renderer.setClearColor(FOG_COLOR, 1);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(FOG_COLOR);
  scene.fog = new THREE.FogExp2(FOG_COLOR, FOG_DENSITY);

  const camera = new THREE.PerspectiveCamera(55, 1, 1, 40000);
  camera.position.set(0, 0, 600);

  const controls = new OrbitControls(camera, canvas);
  controls.enableDamping = true;
  controls.dampingFactor = 0.08;
  controls.enableZoom = false;
  canvas.addEventListener('wheel', ev => {
    ev.preventDefault();
    ev.stopImmediatePropagation();
    const factor = Math.exp(ev.deltaY * 0.0012);
    _v3.copy(camera.position).sub(controls.target);
    let dist = _v3.length() * factor;
    dist = Math.max(controls.minDistance, Math.min(controls.maxDistance, dist));
    _v3.setLength(dist);
    camera.position.copy(controls.target).add(_v3);
    camera.lookAt(controls.target);
  }, { capture: true, passive: false });
  controls.minDistance = 40;
  controls.maxDistance = 6000;
  controls.target.set(0, 0, 0);

  const _m4 = new THREE.Matrix4();
  const _v3 = new THREE.Vector3();
  const _centre = new THREE.Vector3();
  const _normal = new THREE.Vector3();
  const _ndc = new THREE.Vector2();
  const _plane = new THREE.Plane();
  const _ray = new THREE.Raycaster();

  let nodesMesh = null;
  let nodeGeo = null;
  let nodeMat = null;
  let lineSegments = null;
  let lineGeo = null;
  let lineMat = null;
  let linePosAttr = null;
  let linePositions = null;
  let alphaAttr = null;
  let selAttr = null;
  let colorAttr = null;
  let _edges = [];
  let _positions = null;
  let _n = 0;
  let _size = 12;
  let _hidden = null;
  let _selId = null;
  let _idToIndex = new Map();
  let _nodeIndexToId = null;

  function rebuildNodes(n) {
    if (nodesMesh) {
      scene.remove(nodesMesh);
      nodeGeo.dispose();
      nodeMat.dispose();
      nodesMesh = null;
    }

    _n = n;

    nodeGeo = new THREE.PlaneGeometry(1, 1);
    const uvScaleX = tile / sheetDim;
    const uvScaleY = tile / sheetDim;

    const uvOffset = new Float32Array(n * 2);
    const uvScale = new Float32Array(n * 2);
    const alpha = new Float32Array(n);
    const sel = new Float32Array(n);
    const color = new Float32Array(n * 3);

    for (let i = 0; i < n; i++) {
      const t = tiles[i] || [0, 0, 0];
      const tx = t[1] | 0, ty = t[2] | 0;
      uvOffset[i * 2] = (tx * tile) / sheetDim;
      uvOffset[i * 2 + 1] = 1 - (ty * tile + tile) / sheetDim;
      uvScale[i * 2] = uvScaleX;
      uvScale[i * 2 + 1] = uvScaleY;
      alpha[i] = 1;
      color[i * 3] = color[i * 3 + 1] = color[i * 3 + 2] = 1;
    }

    nodeGeo.setAttribute('aUvOffset', new THREE.InstancedBufferAttribute(uvOffset, 2));
    nodeGeo.setAttribute('aUvScale', new THREE.InstancedBufferAttribute(uvScale, 2));
    alphaAttr = new THREE.InstancedBufferAttribute(alpha, 1);
    nodeGeo.setAttribute('aAlpha', alphaAttr);
    selAttr = new THREE.InstancedBufferAttribute(sel, 1);
    nodeGeo.setAttribute('aSel', selAttr);
    colorAttr = new THREE.InstancedBufferAttribute(color, 3);
    nodeGeo.setAttribute('aColor', colorAttr);

    nodeMat = new THREE.ShaderMaterial({
      uniforms: {
        uAtlas: { value: atlasTexture || null },
        uFogColor: { value: new THREE.Color(FOG_COLOR) },
        uFogDensity: { value: FOG_DENSITY },
        uFogNear: { value: FOG_NEAR },
        uSelColor: { value: new THREE.Color(0x027c5c) },
      },
      vertexShader: NODE_VERT,
      fragmentShader: NODE_FRAG,
      transparent: false,
      depthWrite: true,
      depthTest: true,
    });

    nodesMesh = new THREE.InstancedMesh(nodeGeo, nodeMat, Math.max(n, 1));
    nodesMesh.count = n;
    nodesMesh.frustumCulled = false;
    scene.add(nodesMesh);

    for (let i = 0; i < _n; i++) {
      _m4.makeScale(0, 0, 0);
      nodesMesh.setMatrixAt(i, _m4);
    }
    nodesMesh.instanceMatrix.needsUpdate = true;
  }

  function rebuildEdges(edges) {
    if (lineSegments) {
      scene.remove(lineSegments);
      lineGeo.dispose();
      lineMat.dispose();
      lineSegments = null;
    }

    _edges = edges || [];
    const E = _edges.length;

    linePositions = new Float32Array(E * 6);
    const colors = new Float32Array(E * 6);

    for (let e = 0; e < E; e++) {
      const ed = _edges[e];
      const sim = ed.sim == null ? 1 : Math.max(0, Math.min(1, ed.sim));
      const ink = 0.04 + 0.30 * (1 - sim);
      const o = e * 6;
      if (ed.bridge) {
        const r = 0.01 * ink / 0.3, g = 0.49 - 0.2 * (1 - sim), b = 0.36 - 0.15 * (1 - sim);
        colors[o] = 0.01; colors[o + 1] = 0.49; colors[o + 2] = 0.36;
        colors[o + 3] = 0.01; colors[o + 4] = 0.49; colors[o + 5] = 0.36;
      } else {
        const c = Math.max(0.05, ink);
        colors[o] = c; colors[o + 1] = c; colors[o + 2] = c;
        colors[o + 3] = c; colors[o + 4] = c; colors[o + 5] = c;
      }
    }

    lineGeo = new THREE.BufferGeometry();
    linePosAttr = new THREE.BufferAttribute(linePositions, 3);
    linePosAttr.setUsage(THREE.DynamicDrawUsage);
    lineGeo.setAttribute('position', linePosAttr);
    lineGeo.setAttribute('color', new THREE.BufferAttribute(colors, 3));

    lineMat = new THREE.LineBasicMaterial({
      vertexColors: true,
      transparent: true,
      opacity: EDGE_OPACITY,
      fog: true,
    });

    lineSegments = new THREE.LineSegments(lineGeo, lineMat);
    lineSegments.frustumCulled = false;
    lineSegments.renderOrder = 0;
    scene.add(lineSegments);
  }

  function setGraph(nodeIndexToId, edges) {
    _nodeIndexToId = nodeIndexToId;
    _idToIndex = new Map();
    const n = nodeIndexToId ? nodeIndexToId.length : 0;
    if (nodeIndexToId) {
      for (let i = 0; i < n; i++) _idToIndex.set(nodeIndexToId[i], i);
    }
    _hidden = null;
    _positions = null;
    rebuildNodes(n);
    rebuildEdges(edges);
  }

  function setPositions(pos) {
    _positions = pos;
    if (!nodesMesh) return;
    const s = _size;
    const selIdx = (_selId != null && _idToIndex.has(_selId)) ? _idToIndex.get(_selId) : -1;
    for (let i = 0; i < _n; i++) {
      const o = i * 3;
      let sc = (_hidden && _hidden.has(i)) ? 0 : s;
      if (i === selIdx) sc *= SEL_BUMP;
      _m4.makeScale(sc, sc, sc);
      _m4.setPosition(pos[o], pos[o + 1], pos[o + 2]);
      nodesMesh.setMatrixAt(i, _m4);
    }
    nodesMesh.instanceMatrix.needsUpdate = true;
  }

  function updateEdges(pos) {
    _positions = pos;
    if (!linePosAttr) return;
    const E = _edges.length;
    const lp = linePositions;
    const maxIdx = _n * 3;
    for (let e = 0; e < E; e++) {
      const ed = _edges[e];
      const sId = (typeof ed.source === 'object' && ed.source !== null) ? ed.source.id : ed.source;
      const tId = (typeof ed.target === 'object' && ed.target !== null) ? ed.target.id : ed.target;
      const sIdx = _idToIndex.has(sId) ? _idToIndex.get(sId) : sId;
      const tIdx = _idToIndex.has(tId) ? _idToIndex.get(tId) : tId;
      const si = sIdx * 3;
      const ti = tIdx * 3;
      const o = e * 6;
      if (si < 0 || ti < 0 || si + 2 >= maxIdx || ti + 2 >= maxIdx) {
        lp[o] = lp[o + 1] = lp[o + 2] = lp[o + 3] = lp[o + 4] = lp[o + 5] = 0;
        continue;
      }
      lp[o] = pos[si]; lp[o + 1] = pos[si + 1]; lp[o + 2] = pos[si + 2];
      lp[o + 3] = pos[ti]; lp[o + 4] = pos[ti + 1]; lp[o + 5] = pos[ti + 2];
    }
    linePosAttr.needsUpdate = true;
  }

  function setEmphasis(hoveredId, selectedId) {
    if (!alphaAttr) return;
    const hi = hoveredId != null && _idToIndex.has(hoveredId) ? _idToIndex.get(hoveredId) : -1;
    const si = selectedId != null && _idToIndex.has(selectedId) ? _idToIndex.get(selectedId) : -1;
    if (hi < 0 && si < 0) {
      for (let i = 0; i < _n; i++) alphaAttr.array[i] = 1;
    } else {
      for (let i = 0; i < _n; i++) alphaAttr.array[i] = (i === hi || i === si) ? 1 : DIM_ALPHA;
    }
    alphaAttr.needsUpdate = true;
  }

  function setSelected(selectedId) {
    _selId = selectedId;
    if (selAttr) {
      const si = selectedId != null && _idToIndex.has(selectedId) ? _idToIndex.get(selectedId) : -1;
      for (let i = 0; i < _n; i++) selAttr.array[i] = (i === si) ? 1 : 0;
      selAttr.needsUpdate = true;
    }
    if (_positions) setPositions(_positions);
  }

  function hide(ids) {
    if (ids == null) {
      _hidden = null;
    } else {
      const s = new Set();
      for (const id of ids) {
        if (_idToIndex.has(id)) s.add(_idToIndex.get(id));
      }
      _hidden = s;
    }
    if (_positions) setPositions(_positions);
  }

  function setSizeScale(s) {
    _size = s;
    if (_positions) setPositions(_positions);
  }

  function pointerNdc(sx, sy) {
    const rect = canvas.getBoundingClientRect();
    const w = rect.width || canvas.clientWidth || 1;
    const h = rect.height || canvas.clientHeight || 1;
    _ndc.set((sx / w) * 2 - 1, -((sy / h) * 2 - 1));
    return _ndc;
  }

  function pick(sx, sy) {
    if (!nodesMesh || _n === 0 || !_positions) return null;
    const rect = canvas.getBoundingClientRect();
    const w = rect.width || 1, h = rect.height || 1;
    camera.updateMatrixWorld();
    camera.matrixWorldInverse.copy(camera.matrixWorld).invert();
    const tanHalf = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2));
    const selIdx = (_selId != null && _idToIndex.has(_selId)) ? _idToIndex.get(_selId) : -1;
    let best = null, bestDepth = Infinity;
    for (let i = 0; i < _n; i++) {
      if (_hidden && _hidden.has(i)) continue;
      const o = i * 3;
      _v3.set(_positions[o], _positions[o + 1], _positions[o + 2])
        .applyMatrix4(camera.matrixWorldInverse);
      if (_v3.z > -0.1) continue;
      const depth = -_v3.z;
      const bump = (i === selIdx) ? SEL_BUMP : 1;
      const rPx = (_size * 0.5) * bump * (h * 0.5) / (tanHalf * depth) + 3;
      _centre.set(_positions[o], _positions[o + 1], _positions[o + 2]).project(camera);
      const pxX = (_centre.x * 0.5 + 0.5) * w;
      const pxY = (-_centre.y * 0.5 + 0.5) * h;
      const dx = pxX - sx, dy = pxY - sy;
      const d2 = dx * dx + dy * dy;
      if (d2 <= rPx * rPx && depth < bestDepth) { bestDepth = depth; best = i; }
    }
    return best;
  }

  function planeDragPoint(sx, sy, nodeIndex) {
    pointerNdc(sx, sy);
    _ray.setFromCamera(_ndc, camera);

    const pos = _positions;
    if (pos && nodeIndex >= 0 && nodeIndex * 3 + 2 < pos.length) {
      _centre.set(pos[nodeIndex * 3], pos[nodeIndex * 3 + 1], pos[nodeIndex * 3 + 2]);
    } else {
      _centre.set(0, 0, 0);
    }

    camera.getWorldDirection(_normal).normalize();
    _plane.setFromNormalAndCoplanarPoint(_normal, _centre);

    const hit = _ray.ray.intersectPlane(_plane, _v3);
    if (!hit) return { x: _centre.x, y: _centre.y, z: _centre.z };
    return { x: hit.x, y: hit.y, z: hit.z };
  }

  function resize() {
    const w = canvas.clientWidth || 1;
    const h = canvas.clientHeight || 1;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }

  let ro = null;
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(resize);
    ro.observe(canvas);
  } else {
    window.addEventListener('resize', resize);
  }
  resize();

  function render() {
    controls.update();
    renderer.render(scene, camera);
  }
  renderer.setAnimationLoop(render);

  function dispose() {
    renderer.setAnimationLoop(null);
    if (ro) ro.disconnect();
    else window.removeEventListener('resize', resize);
    controls.dispose();
    if (nodesMesh) { scene.remove(nodesMesh); nodeGeo.dispose(); nodeMat.dispose(); nodesMesh = null; }
    if (lineSegments) { scene.remove(lineSegments); lineGeo.dispose(); lineMat.dispose(); lineSegments = null; }
    renderer.dispose();
  }

  return {
    setGraph,
    setPositions,
    updateEdges,
    setEmphasis,
    setSelected,
    hide,
    setSizeScale,
    pick,
    planeDragPoint,
    controls,
    camera,
    dispose,
  };
}

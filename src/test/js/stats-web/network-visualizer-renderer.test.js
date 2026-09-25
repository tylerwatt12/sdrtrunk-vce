'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const feature = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/features/network-visualizer'));

async function loadModule() {
  const configSource = fs.readFileSync(path.join(feature, 'config.js'), 'utf8');
  const configModule = await import(`data:text/javascript;base64,${Buffer.from(configSource).toString('base64')}`);
  const rendererSource = fs.readFileSync(path.join(feature, 'renderer.js'), 'utf8').replace(
    "import { BALANCED_CONFIG } from './config.js';",
    `const BALANCED_CONFIG = ${JSON.stringify(configModule.BALANCED_CONFIG)};`);
  return import(`data:text/javascript;base64,${Buffer.from(rendererSource).toString('base64')}`);
}

class FakeEventTarget {
  constructor() {
    this.listeners = new Map();
  }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(listener);
  }

  removeEventListener(type, listener) {
    this.listeners.get(type)?.delete(listener);
  }

  dispatchEvent(event) {
    this.listeners.get(event?.type)?.forEach((listener) => listener(event));
  }

  listenerCount() {
    return [...this.listeners.values()].reduce((total, listeners) => total + listeners.size, 0);
  }
}

class FakeElement extends FakeEventTarget {
  constructor(ownerDocument = null) {
    super();
    this.ownerDocument = ownerDocument;
    this.parentNode = null;
    this.children = [];
    this.dataset = {};
    this.style = {};
    this.attributes = new Map();
    this.clientWidth = 640;
    this.clientHeight = 360;
    this.hidden = false;
    this.className = '';
    this.textContent = '';
  }

  append(...children) {
    children.forEach((child) => {
      child.remove();
      child.parentNode = this;
      this.children.push(child);
    });
  }

  remove() {
    if (!this.parentNode) return;
    const index = this.parentNode.children.indexOf(this);
    if (index >= 0) this.parentNode.children.splice(index, 1);
    this.parentNode = null;
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }
}

class FakeDocument extends FakeEventTarget {
  constructor() {
    super();
    this.hidden = false;
    this.fullscreenElement = null;
    this.documentElement = new FakeElement(this);
  }

  createElement() {
    return new FakeElement(this);
  }
}

function fakeRendererLibrary(documentValue, lifecycle) {
  class Vector3 {
    constructor(x = 0, y = 0, z = 0) {
      this.set(x, y, z);
    }

    set(x = 0, y = 0, z = 0) {
      this.x = x;
      this.y = y;
      this.z = z;
      return this;
    }

    copy(value) {
      return this.set(value?.x, value?.y, value?.z);
    }

    sub(value) {
      this.x -= value?.x || 0;
      this.y -= value?.y || 0;
      this.z -= value?.z || 0;
      return this;
    }

    lengthSq() {
      return this.x * this.x + this.y * this.y + this.z * this.z;
    }

    normalize() {
      const length = Math.sqrt(this.lengthSq()) || 1;
      this.x /= length;
      this.y /= length;
      this.z /= length;
      return this;
    }

    distanceTo(value) {
      return Math.hypot(this.x - value.x, this.y - value.y, this.z - value.z);
    }

    project() {
      return this;
    }
  }

  class Object3D {
    constructor() {
      this.children = [];
      this.userData = {};
      this.position = new Vector3();
      this.scale = { value: 1, setScalar: (value) => { this.scale.value = value; } };
      this.visible = true;
    }

    add(...children) {
      this.children.push(...children);
    }
  }

  class Geometry {
    constructor() {
      this.disposeCalls = 0;
      lifecycle.geometries.push(this);
    }

    dispose() {
      this.disposeCalls += 1;
    }
  }

  class BufferGeometry extends Geometry {
    constructor() {
      super();
      this.attributes = {};
    }

    setAttribute(name, attribute) {
      this.attributes[name] = attribute;
      return this;
    }

    computeBoundingSphere() {}
  }

  class Float32BufferAttribute {
    constructor(array) {
      this.array = array;
      this.needsUpdate = false;
    }

    setUsage() {
      return this;
    }
  }

  class Material {
    constructor(settings = {}) {
      Object.assign(this, settings);
      this.color = { set: () => {} };
      this.disposeCalls = 0;
      lifecycle.materials.push(this);
    }

    dispose() {
      this.disposeCalls += 1;
    }
  }

  class LineDashedMaterial extends Material {
    constructor(settings) {
      super(settings);
      this.isLineDashedMaterial = true;
    }
  }

  class Mesh extends Object3D {
    constructor(geometry, material) {
      super();
      this.geometry = geometry;
      this.material = material;
    }
  }

  class Line extends Mesh {
    computeLineDistances() {}
  }

  const geometryTypes = {
    IcosahedronGeometry: class extends Geometry {},
    CylinderGeometry: class extends Geometry {},
    BoxGeometry: class extends Geometry {},
    TorusGeometry: class extends Geometry {},
    SphereGeometry: class extends Geometry {}
  };

  function ForceGraph3D() {
    return (surface) => {
      const controls = new FakeEventTarget();
      controls.target = new Vector3();
      controls.dispose = () => { lifecycle.controlDisposals += 1; };
      const canvas = new FakeElement(documentValue);
      surface.append(canvas);
      const webglRenderer = {
        domElement: canvas,
        info: { memory: { geometries: 0, textures: 0 } },
        dispose: () => { lifecycle.rendererDisposals += 1; },
        forceContextLoss: () => { lifecycle.forcedContextLosses += 1; }
      };
      const camera = { position: new Vector3(0, 0, 300), up: new Vector3(0, 1, 0) };
      const state = { data: { nodes: [], links: [] }, callbacks: {}, destroyed: false };
      lifecycle.graphStates.push(state);
      const graph = {};
      const chain = [
        'showNavInfo', 'backgroundColor', 'nodeId', 'nodeVal', 'nodeThreeObjectExtend', 'linkSource',
        'linkTarget', 'linkThreeObjectExtend', 'linkCurvature', 'linkDirectionalParticles',
        'linkDirectionalParticleWidth', 'linkDirectionalParticleSpeed', 'linkDirectionalParticleColor',
        'onNodeClick', 'onBackgroundClick', 'onNodeDrag', 'onNodeDragEnd', 'onEngineTick', 'cooldownTicks',
        'cooldownTime', 'width', 'height'
      ];
      chain.forEach((name) => {
        graph[name] = (value) => {
          state.callbacks[name] = value;
          return graph;
        };
      });
      graph.nodeThreeObject = (value) => { state.callbacks.nodeThreeObject = value; return graph; };
      graph.linkThreeObject = (value) => { state.callbacks.linkThreeObject = value; return graph; };
      graph.linkPositionUpdate = (value) => { state.callbacks.linkPositionUpdate = value; return graph; };
      graph.graphData = (value) => {
        if (value === undefined) return state.data;
        state.data = value;
        value.nodes.forEach((node) => state.callbacks.nodeThreeObject?.(node));
        value.links.forEach((link) => state.callbacks.linkThreeObject?.(link));
        return graph;
      };
      graph.d3Force = () => graph;
      graph.numDimensions = () => graph;
      graph.scene = () => ({});
      graph.controls = () => controls;
      graph.renderer = () => webglRenderer;
      graph.camera = () => camera;
      graph.graph2ScreenCoords = (x, y) => ({ x, y });
      graph.cameraPosition = () => graph;
      graph.zoomToFit = () => graph;
      graph.refresh = () => graph;
      graph.pauseAnimation = () => graph;
      graph.resumeAnimation = () => graph;
      graph.emitParticle = () => graph;
      graph._destructor = () => {
        if (state.destroyed) return;
        state.destroyed = true;
        lifecycle.graphDestructors += 1;
        controls.dispose();
        webglRenderer.dispose();
      };
      lifecycle.controls.push(controls);
      lifecycle.canvases.push(canvas);
      return graph;
    };
  }

  return {
    ForceGraph3D,
    Vector3,
    Group: Object3D,
    Mesh,
    Line,
    BufferGeometry,
    Float32BufferAttribute,
    MeshBasicMaterial: Material,
    MeshLambertMaterial: Material,
    LineBasicMaterial: Material,
    LineDashedMaterial,
    DynamicDrawUsage: 1,
    BackSide: 1,
    ...geometryTypes
  };
}

async function verifyRendererDisposal(createNetworkVisualizerRenderer) {
  const originals = new Map();
  const install = (name, value) => {
    originals.set(name, Object.prototype.hasOwnProperty.call(globalThis, name) ? globalThis[name] : undefined);
    globalThis[name] = value;
  };
  const restore = () => originals.forEach((value, name) => {
    if (value === undefined) delete globalThis[name];
    else globalThis[name] = value;
  });
  const documentValue = new FakeDocument();
  const windowValue = new FakeEventTarget();
  const mediaQuery = new FakeEventTarget();
  mediaQuery.matches = false;
  const lifecycle = { geometries: [], materials: [], controls: [], canvases: [], graphDestructors: 0,
    rendererDisposals: 0, forcedContextLosses: 0, controlDisposals: 0,
    resizeObservers: 0, resizeDisconnects: 0, mutationObservers: 0, mutationDisconnects: 0,
    graphStates: [] };
  const animationFrames = new Map();
  let nextFrame = 1;
  class FakeResizeObserver {
    constructor() { lifecycle.resizeObservers += 1; }
    observe() {}
    disconnect() { lifecycle.resizeDisconnects += 1; }
  }
  class FakeMutationObserver {
    constructor() { lifecycle.mutationObservers += 1; }
    observe() {}
    disconnect() { lifecycle.mutationDisconnects += 1; }
  }
  install('Element', FakeElement);
  install('window', windowValue);
  install('matchMedia', () => mediaQuery);
  install('getComputedStyle', () => ({ getPropertyValue: () => '' }));
  install('ResizeObserver', FakeResizeObserver);
  install('MutationObserver', FakeMutationObserver);
  install('requestAnimationFrame', (callback) => {
    const id = nextFrame;
    nextFrame += 1;
    animationFrames.set(id, callback);
    return id;
  });
  install('cancelAnimationFrame', (id) => animationFrames.delete(id));

  try {
    const host = new FakeElement(documentValue);
    const library = fakeRendererLibrary(documentValue, lifecycle);
    for (let cycle = 1; cycle <= 3; cycle += 1) {
      const signal = new FakeEventTarget();
      signal.aborted = false;
      const renderer = await createNetworkVisualizerRenderer({ host, library, signal });
      renderer.setGraphData({ generation: cycle, nodes: [
        { key: 'system', type: 'universe', x: 0, y: 0, z: 7 },
        { key: 'group', type: 'group', x: 20, y: 0, z: 5 },
        { key: 'radio', type: 'radio', x: 35, y: 0, z: 9 }
      ], links: [
        { key: 'tx', source: 'radio', target: 'group', type: 'tx', active: true, particleCount: 1 }
      ], labels: ['system', 'group', 'radio'], effects: [
        { id: `effect-${cycle}`, type: 'tx_pulse', nodeKey: 'radio', sourceKey: 'radio', targetKey: 'group',
          createdAtMs: Date.now(), expiresAtMs: Date.now() + 1_000 }
      ] });
      assert.equal(renderer.diagnostics().animatedEffects, 1);
      documentValue.hidden = true;
      documentValue.dispatchEvent({ type: 'visibilitychange' });
      assert.equal(renderer.diagnostics().animatedEffects, 0);
      documentValue.hidden = false;
      documentValue.dispatchEvent({ type: 'visibilitychange' });
      renderer.setFrozen(true);
      renderer.setGraphData({ generation: cycle, nodes: [
        { key: 'system', type: 'universe', x: 0, y: 0, z: 7 },
        { key: 'group', type: 'group', x: 45, y: -10, z: 11 },
        { key: 'radio', type: 'radio', x: 60, y: -10, z: 15 }
      ], links: [
        { key: 'tx', source: 'radio', target: 'group', type: 'tx', active: true, particleCount: 1 }
      ], labels: ['system', 'group', 'radio'], effects: [] });
      const frozenNodes = lifecycle.graphStates.at(-1).data.nodes;
      assert.equal(frozenNodes.find((node) => node.key === 'group').x, 45);
      assert.equal(frozenNodes.find((node) => node.key === 'radio').x, 60);
      renderer.setMode('flat', { duration: 0 });
      assert(frozenNodes.every((node) => node.z === 0));
      renderer.pulse('tx');
      assert(host.children.length > 0);
      renderer.dispose();
      renderer.dispose();
      assert.deepEqual(renderer.diagnostics(), {
        available: false,
        disposed: true,
        layoutOwner: 'external',
        mode: 'flat',
        frozen: true,
        nodes: 0,
        links: 0,
        labels: 0,
        steadyParticles: 0,
        pendingParticles: 0,
        animatedEffects: 0,
        contextLost: false,
        paused: false,
        rendererMemory: null,
        limits: {}
      });
      assert.equal(host.children.length, 0);
      assert.equal(documentValue.listenerCount(), 0);
      assert.equal(mediaQuery.listenerCount(), 0);
      assert.equal(windowValue.listenerCount(), 0);
      assert.equal(signal.listenerCount(), 0);
      assert.equal(lifecycle.controls.at(-1).listenerCount(), 0);
      assert.equal(lifecycle.canvases.at(-1).listenerCount(), 0);
      assert.equal(animationFrames.size, 0);
    }
    assert.equal(lifecycle.graphDestructors, 3);
    assert.equal(lifecycle.rendererDisposals, 3);
    assert.equal(lifecycle.forcedContextLosses, 3);
    assert.equal(lifecycle.controlDisposals, 3);
    assert.equal(lifecycle.resizeObservers, 3);
    assert.equal(lifecycle.resizeDisconnects, 3);
    assert.equal(lifecycle.mutationObservers, 3);
    assert.equal(lifecycle.mutationDisconnects, 3);
    assert(lifecycle.geometries.length > 0);
    assert(lifecycle.materials.length > 0);
    assert(lifecycle.geometries.every((resource) => resource.disposeCalls > 0));
    assert(lifecycle.materials.every((resource) => resource.disposeCalls > 0));
  } finally {
    restore();
  }
}

async function main() {
  const {
    allocateParticles,
    boundedGraphData,
    chooseLabelPlacements,
    labelEligible,
    labelZoomTier,
    linkIsDashed,
    linkVisualState,
    nodeGeometryKind,
    nodeVisualState,
    createNetworkVisualizerRenderer
  } = await loadModule();

  const bounded = boundedGraphData({
    nodes: [
      { key: 'system', type: 'universe' },
      { key: 'group', type: 'group' },
      { key: 'radio', type: 'radio' },
      { key: 'radio', type: 'radio' }
    ],
    links: [
      { key: 'valid', source: 'radio', target: 'group', type: 'tx' },
      { key: 'over-link-budget', source: 'system', target: 'group', type: 'activity' },
      { key: 'missing-endpoint', source: 'missing', target: 'group', type: 'activity' }
    ],
    labels: ['radio', 'missing', { key: 'group' }],
    effects: [{ id: 'one' }, { id: 'two' }]
  }, { nodes: 3, links: 1, effects: 1 });
  assert.deepEqual(bounded.nodes.map((node) => node.key), ['system', 'group', 'radio']);
  assert.deepEqual(bounded.links.map((link) => link.key), ['valid']);
  assert.deepEqual(bounded.labels, ['radio', 'group']);
  assert.deepEqual(bounded.effects.map((effect) => effect.id), ['one']);
  assert.deepEqual(bounded.rendererLimits, {
    inputNodes: 4,
    inputLinks: 3,
    inputEffects: 2,
    suppressedNodes: 1,
    suppressedLinks: 2,
    suppressedEffects: 1
  });

  const labels = chooseLabelPlacements([
    { key: 'lower-priority', x: 20, y: 30, width: 80, height: 20, priority: 1 },
    { key: 'active', x: 20, y: 30, width: 80, height: 20, priority: 20 },
    { key: 'separate', x: 180, y: 30, width: 80, height: 20, priority: 2 },
    { key: 'offscreen', x: 500, y: 30, width: 80, height: 20, priority: 100 }
  ], { width: 320, height: 180 }, 2);
  assert.deepEqual(labels.map((label) => label.key), ['active', 'separate']);

  assert.equal(labelZoomTier(1_000, 500), 'overview');
  assert.equal(labelZoomTier(250, 500), 'mid');
  assert.equal(labelZoomTier(100, 500), 'close');
  assert.equal(labelZoomTier(1_000, 500, true), 'close');
  assert.equal(labelEligible({ type: 'universe' }, 'overview'), true);
  assert.equal(labelEligible({ type: 'group' }, 'overview'), false);
  assert.equal(labelEligible({ type: 'group' }, 'mid'), true);
  assert.equal(labelEligible({ type: 'radio' }, 'mid'), false);
  assert.equal(labelEligible({ type: 'radio', active: true }, 'overview'), true);
  assert.equal(labelEligible({ type: 'radio', selected: true }, 'overview'), true);

  const particles = allocateParticles([
    { key: 'idle', type: 'tx', active: false },
    { key: 'tx', type: 'tx', active: true, particleCount: 3 },
    { key: 'migration', type: 'migration', particleCount: 2 },
    { key: 'activity', type: 'activity', active: true }
  ], 4);
  assert.deepEqual([...particles], [['tx', 3], ['migration', 1]]);
  assert.equal([...particles.values()].reduce((sum, count) => sum + count, 0), 4);

  assert.equal(nodeVisualState({ type: 'radio', active: true, quiet: true }), 'active');
  assert.equal(nodeVisualState({ type: 'group', pending: true }), 'pending');
  assert.equal(nodeVisualState({ type: 'radio' }, { type: 'affiliation_arrival' }), 'arrival');
  assert.equal(nodeVisualState({ type: 'radio', quiet: true }), 'quiet');
  assert.equal(nodeVisualState({ type: 'radio', afterglow: true, quiet: true }), 'afterglow');
  assert.equal(nodeGeometryKind({ type: 'universe', kind: 'channel' }), 'conventional-universe');
  assert.equal(nodeGeometryKind({ type: 'group', kind: 'channel' }), 'conventional-group');
  assert.equal(nodeGeometryKind({ type: 'group', kind: 'talkgroup' }), 'group');
  assert.equal(linkVisualState({ type: 'tx', active: false }), 'activity');
  assert.equal(linkVisualState({ type: 'affiliation', faded: true }), 'faded');
  assert.equal(linkVisualState({ type: 'tx', active: true, faded: true }), 'active');
  assert.equal(linkIsDashed({ type: 'affiliation' }), false);
  assert.equal(linkIsDashed({ type: 'activity' }), true);
  assert.equal(linkIsDashed({ type: 'tx' }), true);
  assert.equal(linkIsDashed({ type: 'tx', affiliation: true }), false);
  assert.equal(linkIsDashed({ type: 'migration' }), false);

  await verifyRendererDisposal(createNetworkVisualizerRenderer);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

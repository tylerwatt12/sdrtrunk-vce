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
      this.z /= 300;
      return this;
    }
  }

  class Object3D {
    constructor() {
      this.children = [];
      this.userData = {};
      this.position = new Vector3();
      this.rotation = { x: 0, y: 0, z: 0 };
      this.scale = { value: 1, setScalar: (value) => { this.scale.value = value; } };
      this.visible = true;
    }

    add(...children) {
      children.forEach((child) => { child.parent = this; });
      this.children.push(...children);
    }

    remove(child) {
      const index = this.children.indexOf(child);
      if (index >= 0) this.children.splice(index, 1);
      if (child?.parent === this) child.parent = null;
    }
  }

  class Geometry {
    constructor(...args) {
      this.args = args;
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
      this.color = {
        value: settings.color,
        set: (value) => { this.color.value = value; }
      };
      this.disposeCalls = 0;
      lifecycle.materials.push(this);
    }

    dispose() {
      this.disposeCalls += 1;
    }
  }

  class FogExp2 {
    constructor(color, density) {
      this.color = { value: color, set: (value) => { this.color.value = value; } };
      this.density = density;
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

  class LineGeometry extends Geometry {
    constructor() {
      super();
      this.positions = null;
      this.attributes = {};
      this.setPositionsCalls = 0;
      this.boundingBoxComputations = 0;
      this.boundingSphereComputations = 0;
    }

    setPositions(positions) {
      this.positions = positions;
      this.setPositionsCalls += 1;
      const segmentArray = new Float32Array(Math.max(0, positions.length - 3) * 2);
      const data = { array: segmentArray, needsUpdate: false, usage: null,
        setUsage(value) { this.usage = value; return this; } };
      this.attributes.instanceStart = { data };
      this.attributes.instanceEnd = { data };
      return this;
    }

    computeBoundingBox() {
      this.boundingBoxComputations += 1;
    }

    computeBoundingSphere() {
      this.boundingSphereComputations += 1;
    }
  }

  class LineMaterial extends Material {
    constructor(settings = {}) {
      super(settings);
      this.resolution = {
        width: 0,
        height: 0,
        set: (width, height) => {
          this.resolution.width = width;
          this.resolution.height = height;
        }
      };
    }
  }

  class Line2 extends Mesh {
    constructor(geometry, material) {
      super(geometry, material);
      this.lineDistanceComputations = 0;
    }

    computeLineDistances() {
      this.lineDistanceComputations += 1;
      const segments = this.geometry.attributes.instanceStart.data.array.length / 6;
      const data = { array: new Float32Array(segments * 2), needsUpdate: false, usage: null,
        setUsage(value) { this.usage = value; return this; } };
      this.geometry.attributes.instanceDistanceStart = { data };
      this.geometry.attributes.instanceDistanceEnd = { data };
    }
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
      controls.mouseButtons = {};
      controls.touches = {};
      controls.updateCalls = 0;
      controls.update = () => { controls.updateCalls += 1; };
      controls.dispose = () => { lifecycle.controlDisposals += 1; };
      const canvas = new FakeElement(documentValue);
      surface.append(canvas);
      const webglRenderer = {
        domElement: canvas,
        info: { memory: { geometries: 0, textures: 0 } },
        dispose: () => { lifecycle.rendererDisposals += 1; },
        forceContextLoss: () => { lifecycle.forcedContextLosses += 1; }
      };
      const camera = {
        position: new Vector3(0, 0, 300),
        up: new Vector3(0, 1, 0),
        fov: 60,
        lookAtCalls: [],
        lookAt(value) {
          this.lookAtCalls.push({ x: value.x, y: value.y, z: value.z });
        }
      };
      const state = {
        data: { nodes: [], links: [] },
        callbacks: {},
        destroyed: false,
        nodeObjects: new Map(),
        linkObjects: new Map(),
        graphDataCalls: 0,
        recursivelyDisposed: [],
        emittedParticles: [],
        enableNodeDragCalls: [],
        nodeDragEnabled: false,
        camera,
        scene: {}
      };
      lifecycle.graphStates.push(state);
      const graph = {};
      const chain = [
        'showNavInfo', 'backgroundColor', 'nodeId', 'nodeVal', 'nodeThreeObjectExtend', 'linkSource',
        'linkTarget', 'linkThreeObjectExtend', 'linkCurvature', 'linkDirectionalParticles',
        'linkDirectionalParticleWidth', 'linkDirectionalParticleSpeed', 'linkDirectionalParticleColor',
        'linkDirectionalParticleThreeObject',
        'onNodeClick', 'onBackgroundClick', 'onEngineTick', 'cooldownTicks',
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
      const recursivelyDispose = (object) => {
        if (!object) return;
        [...(object.children || [])].forEach(recursivelyDispose);
        object.geometry?.dispose?.();
        if (Array.isArray(object.material)) object.material.forEach((material) => material?.dispose?.());
        else object.material?.dispose?.();
      };
      graph.graphData = (value) => {
        if (value === undefined) return state.data;
        state.graphDataCalls += 1;
        const nextNodeKeys = new Set(value.nodes.map((node) => String(node?.key || node?.id || '')));
        const nextLinkKeys = new Set(value.links.map((link) => String(link?.key || link?.id || '')));
        for (const [key, object] of state.nodeObjects) {
          if (nextNodeKeys.has(key)) continue;
          recursivelyDispose(object);
          state.nodeObjects.delete(key);
          state.recursivelyDisposed.push(`node:${key}`);
        }
        for (const [key, object] of state.linkObjects) {
          if (nextLinkKeys.has(key)) continue;
          recursivelyDispose(object);
          state.linkObjects.delete(key);
          state.recursivelyDisposed.push(`link:${key}`);
        }
        state.data = value;
        value.nodes.forEach((node) => {
          const object = state.callbacks.nodeThreeObject?.(node);
          if (object) state.nodeObjects.set(node.key, object);
        });
        value.links.forEach((link) => {
          const object = state.callbacks.linkThreeObject?.(link);
          if (object) state.linkObjects.set(link.key, object);
        });
        return graph;
      };
      graph.d3Force = () => graph;
      graph.numDimensions = () => graph;
      graph.scene = () => state.scene;
      graph.controls = () => controls;
      graph.renderer = () => webglRenderer;
      graph.camera = () => camera;
      graph.graph2ScreenCoords = (x, y) => ({ x, y });
      graph.cameraPosition = () => graph;
      graph.zoomToFit = () => graph;
      graph.enableNodeDrag = (value) => {
        state.nodeDragEnabled = Boolean(value);
        state.enableNodeDragCalls.push(state.nodeDragEnabled);
        return graph;
      };
      graph.refresh = () => graph;
      graph.pauseAnimation = () => graph;
      graph.resumeAnimation = () => graph;
      graph.emitParticle = (link) => {
        const accessor = state.callbacks.linkDirectionalParticleThreeObject;
        const particle = typeof accessor === 'function' ? accessor(link) : accessor;
        if (particle) {
          if (!link.__singleHopPhotonsObj) link.__singleHopPhotonsObj = new Object3D();
          link.__singleHopPhotonsObj.add(particle);
          state.emittedParticles.push(particle);
        }
        return graph;
      };
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
    Line2,
    LineGeometry,
    LineMaterial,
    BufferGeometry,
    Float32BufferAttribute,
    MeshBasicMaterial: Material,
    MeshLambertMaterial: Material,
    LineBasicMaterial: Material,
    LineDashedMaterial,
    MOUSE: { ROTATE: 0, DOLLY: 1, PAN: 2 },
    TOUCH: { ROTATE: 0, PAN: 1, DOLLY_PAN: 2, DOLLY_ROTATE: 3 },
    DynamicDrawUsage: 1,
    BackSide: 1,
    FogExp2,
    ...geometryTypes
  };
}

async function main() {
  const {
    allocateParticles,
    boundedGraphData,
    chooseLabelPlacements,
    createNetworkVisualizerRenderer,
    depthPresentation,
    linkIsDashed,
    linkVisualState,
    nodeDepthClear,
    nodeGeometryKind,
    nodeVisualState,
    scopeFogDensity
  } = await loadModule();

  const bounded = boundedGraphData({
    nodes: [{ key: 'system', type: 'universe' }, { key: 'group', type: 'group' },
      { key: 'radio', type: 'radio' }, { key: 'aggregate', type: 'aggregate' }],
    links: [{ key: 'tx', source: 'radio', target: 'group' },
      { key: 'invalid', source: 'missing', target: 'group' }],
    labels: ['system', 'group', 'radio'], effects: [{ id: 'one' }, { id: 'two' }]
  }, { nodes: 4, links: 1, effects: 1 });
  assert.deepEqual(bounded.nodes.map((node) => node.key), ['system', 'group', 'radio', 'aggregate']);
  assert.deepEqual(bounded.links.map((link) => link.key), ['tx']);
  assert.equal(bounded.rendererLimits.suppressedLinks, 1);
  assert.deepEqual(chooseLabelPlacements([
    { key: 'first', x: 20, y: 20, priority: 2 },
    { key: 'overlap-is-allowed', x: 20, y: 20, priority: 1 }
  ], { width: 320, height: 180 }, 2).map((label) => label.key), ['first', 'overlap-is-allowed']);
  assert.deepEqual([...allocateParticles([
    { key: 'tx', type: 'tx', active: true, particleCount: 3 },
    { key: 'migration', type: 'migration', particleCount: 2 }
  ], 4)], [['tx', 3], ['migration', 1]]);
  assert.equal(nodeGeometryKind({ type: 'universe' }), 'universe');
  assert.equal(nodeGeometryKind({ type: 'group' }), 'group');
  assert.equal(nodeGeometryKind({ type: 'radio' }), 'radio');
  assert.equal(nodeGeometryKind({ type: 'aggregate' }), 'aggregate');
  assert.equal(nodeVisualState({ type: 'radio', active: true }), 'active');
  assert.equal(nodeVisualState({ type: 'radio', active: true, signalAction: 'emergency' }), 'emergency');
  assert.equal(nodeVisualState({ type: 'universe', kind: 'radio_system', active: true,
    signalAction: 'emergency' }), 'universe', 'overview system shells must remain visually stable');
  assert.equal(nodeDepthClear({ type: 'radio', active: true }), true);
  assert.equal(nodeDepthClear({ type: 'radio', signalAction: 'denial' }), true);
  assert.equal(linkVisualState({ type: 'tx', active: true }), 'active');
  assert.equal(linkIsDashed({ type: 'tx' }), true);
  const nearDepth = depthPresentation({ distance: 10, projectedZ: -0.5, density: 0.005 });
  const farDepth = depthPresentation({ distance: 250, projectedZ: 0.7, density: 0.005 });
  const liveDepth = depthPresentation({ distance: 250, projectedZ: 0.7, density: 0.005, active: true });
  assert(nearDepth.opacity > farDepth.opacity);
  assert(nearDepth.blurPx < farDepth.blurPx);
  assert(nearDepth.zIndex > farDepth.zIndex);
  assert.deepEqual({ haze: liveDepth.haze, opacity: liveDepth.opacity, blurPx: liveDepth.blurPx },
    { haze: 0, opacity: 1, blurPx: 0 });
  assert(scopeFogDensity({ level: 'group' }) > scopeFogDensity({ level: 'system' }));
  assert(scopeFogDensity({ level: 'system' }) > scopeFogDensity({ level: 'overview' }));

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
  const scheduledTimeouts = new Map();
  let nextFrame = 1;
  let nextTimeout = 1;
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
    const id = nextFrame++;
    animationFrames.set(id, callback);
    return id;
  });
  install('cancelAnimationFrame', (id) => animationFrames.delete(id));
  install('setTimeout', (callback, delay = 0, ...args) => {
    const id = nextTimeout++;
    scheduledTimeouts.set(id, { callback, delay, args });
    return id;
  });
  install('clearTimeout', (id) => scheduledTimeouts.delete(id));

  try {
    const host = new FakeElement(documentValue);
    const library = fakeRendererLibrary(documentValue, lifecycle);
    const signal = new FakeEventTarget();
    signal.aborted = false;
    const renderer = await createNetworkVisualizerRenderer({ host, library, signal, autoFitOnFirstData: false });
    const graphState = lifecycle.graphStates.at(-1);
    const controls = lifecycle.controls.at(-1);
    assert.equal(renderer.available, true);
    assert.equal(renderer.diagnostics().mode, '3d');
    assert.equal(controls.enableRotate, true);
    assert.equal(controls.zoomToCursor, true);
    assert.equal(controls.mouseButtons.LEFT, library.MOUSE.ROTATE);

    const document = { generation: 1, nodes: [
      { key: 'system', type: 'universe', kind: 'radio_system', label: 'System', x: 0, y: 0, z: 0,
        renderRadius: 300, scopeLevel: 'system', labelVisible: false, selected: true },
      { key: 'group', type: 'group', label: 'Group', x: 30, y: 0, z: 8, active: true },
      { key: 'radio', type: 'radio', label: 'Radio', x: 48, y: 5, z: 15, active: true },
      { key: 'aggregate', type: 'aggregate', label: '+20', x: 50, y: -8, z: -12 }
    ], links: [
      { key: 'tx', source: 'radio', target: 'group', type: 'tx', active: true, particleCount: 1 }
    ], labels: ['system', 'group', 'radio', 'aggregate'], effects: [] };
    renderer.setGraphData(document);
    await Promise.resolve();
    for (const [id, callback] of [...animationFrames]) {
      animationFrames.delete(id);
      callback(performance.now());
    }
    const system = graphState.nodeObjects.get('system').children[0];
    const group = graphState.nodeObjects.get('group').children[0];
    const radio = graphState.nodeObjects.get('radio').children[0];
    const aggregate = graphState.nodeObjects.get('aggregate').children[0];
    assert(system.geometry instanceof library.SphereGeometry);
    assert(group.geometry instanceof library.BoxGeometry);
    assert(radio.geometry instanceof library.CylinderGeometry);
    assert.deepEqual(radio.geometry.args.slice(0, 4), [0, 1, 1.7, 3]);
    assert(aggregate.geometry instanceof library.IcosahedronGeometry);
    assert([system, group, radio, aggregate].every((mesh) => mesh.material.wireframe === true));
    assert.equal(graphState.linkObjects.get('tx') instanceof library.Line2, true);
    assert.equal(graphState.linkObjects.get('tx').material.dashed, true);
    assert.equal(graphState.scene.fog instanceof library.FogExp2, true);
    assert.equal(group.material.fog, false, 'active talkgroups should remain clear through fog');
    assert.equal(radio.material.fog, false, 'active source radios should remain clear through fog');
    assert.equal(graphState.linkObjects.get('tx').material.fog, false);
    assert.deepEqual(graphState.enableNodeDragCalls, [false]);
    assert.equal(graphState.callbacks.onNodeDrag, undefined);
    assert.equal(graphState.callbacks.onNodeDragEnd, undefined);
    assert.equal(renderer.diagnostics().labels, 3);
    assert.equal(renderer.diagnostics().autoRotateEffective, true);

    document.nodes[0].scopeLevel = 'overview';
    renderer.setGraphData(document);
    renderer.setNavigationScope({ level: 'system', universeKey: 'system' });
    graphState.camera.position.set(0, 0, 300);
    controls.target.set(0, 0, 0);
    controls.dispatchEvent({ type: 'change' });
    assert.equal(graphState.camera.position.z, 300,
      'camera containment must wait for the expanded system graph instead of using the compact overview radius');
    assert.equal(controls.maxDistance, Infinity);
    document.nodes[0].scopeLevel = 'system';
    renderer.setGraphData(document);
    assert.equal(graphState.scene.fog.density, scopeFogDensity({ level: 'system' }));
    assert.equal(renderer.frameScope({ duration: 720 }), true);
    controls.dispatchEvent({ type: 'start' });
    const interruptedPose = renderer.diagnostics().camera;
    assert(Math.hypot(interruptedPose.position.x, interruptedPose.position.y,
      interruptedPose.position.z) <= 234.001,
    'interrupting system entry must not leave the camera at its outside overview pose');
    assert(controls.maxDistance < 300);
    controls.dispatchEvent({ type: 'end' });
    assert.equal(renderer.frameScope({ duration: 0 }), true);
    const systemObject = graphState.nodeObjects.get('system');
    assert.equal(systemObject.scale.value, 300);
    assert.equal(system.material.side, library.BackSide);
    assert.equal(system.material.fog, false);
    assert.equal(systemObject.children[1].visible, false,
      'the selected outline must not duplicate the full interior shell');
    const stableSystemMaterial = system.material;
    document.nodes[0].active = true;
    document.nodes[0].signalAction = 'emergency';
    renderer.refresh();
    assert.equal(system.material, stableSystemMaterial,
      'child Grant and signaling state must not restyle the enclosing system shell');
    const insidePose = renderer.diagnostics().camera;
    assert.deepEqual(insidePose.target, { x: 0, y: 0, z: 0 });
    const insideDistance = Math.hypot(insidePose.position.x, insidePose.position.y, insidePose.position.z);
    assert(insideDistance > 0 && insideDistance < 300, 'system focus should place the camera inside its sphere');
    assert(controls.maxDistance > insideDistance && controls.maxDistance < 300,
      'system orbit and zoom should remain inside the sphere');
    assert.equal(renderer.steerOrbitTarget(['group', 'radio'], 0), true);
    const aimedPose = renderer.diagnostics().camera;
    assert.deepEqual(aimedPose.position, insidePose.position,
      'nearby attention should turn the camera instead of translating the entire camera rig');
    assert.deepEqual({ x: controls.target.x, y: controls.target.y, z: controls.target.z },
      { x: 39, y: 2.5, z: 11.5 });
    document.nodes[1].x = 250;
    document.nodes[2].x = 260;
    renderer.refresh();
    assert.equal(renderer.steerOrbitTarget(['group', 'radio'], 0), true);
    const steeredPose = renderer.diagnostics().camera;
    assert(Math.hypot(steeredPose.position.x, steeredPose.position.y, steeredPose.position.z) < 300,
      'hotspot steering should keep the camera inside the focused system sphere');
    assert(Math.hypot(steeredPose.target.x, steeredPose.target.y, steeredPose.target.z) <= 204.001,
      'hotspot steering should leave enough room to orbit around a target near the system edge');
    assert.equal(renderer.steerOrbitTarget(['not-rendered'], 0, 'system'), true,
      'a suppressed hotspot should fall back to its rendered system');
    const hotspot = { x: 255, y: 2.5, z: 11.5 };
    graphState.camera.position.set(hotspot.x, hotspot.y, hotspot.z);
    controls.target.set(hotspot.x, hotspot.y, hotspot.z);
    assert.equal(renderer.steerOrbitTarget(['group', 'radio'], 0), true);
    const nondegeneratePose = renderer.diagnostics().camera;
    assert(Math.hypot(nondegeneratePose.position.x - nondegeneratePose.target.x,
      nondegeneratePose.position.y - nondegeneratePose.target.y,
      nondegeneratePose.position.z - nondegeneratePose.target.z) >= 11.999,
    'attention steering must preserve a usable orbit radius when camera and hotspot coincide');
    controls.dispatchEvent({ type: 'start' });
    graphState.camera.position.set(500, 0, 0);
    controls.target.set(400, 0, 0);
    controls.dispatchEvent({ type: 'change' });
    const constrainedPose = renderer.diagnostics().camera;
    assert(Math.hypot(constrainedPose.position.x, constrainedPose.position.y,
      constrainedPose.position.z) <= 270.001, 'manual camera motion must remain inside the system shell');
    assert(Math.hypot(constrainedPose.target.x, constrainedPose.target.y,
      constrainedPose.target.z) <= 204.001, 'manual orbit target must remain inside the system shell');
    assert.deepEqual(graphState.camera.lookAtCalls.at(-1), constrainedPose.target,
      'camera orientation must follow a target constrained during manual orbit');
    controls.dispatchEvent({ type: 'end' });

    const graphDataCalls = graphState.graphDataCalls;
    document.nodes[2].x = 64;
    document.nodes[2].selected = true;
    renderer.refresh();
    assert.equal(graphState.graphDataCalls, graphDataCalls);
    assert.equal(graphState.nodeObjects.get('radio').position.x, 64);
    assert.equal(graphState.nodeObjects.get('radio').children[1].visible, true);
    assert.equal(controls.enableRotate, true);
    renderer.setReducedMotion(true);
    assert.equal(renderer.diagnostics().autoRotateEffective, false);
    renderer.setReducedMotion(false);
    renderer.setAutoRotate(true);
    assert.equal(renderer.diagnostics().autoRotateEffective, true);

    renderer.dispose();
    renderer.dispose();
    assert.equal(host.children.length, 0);
    assert.equal(documentValue.listenerCount(), 0);
    assert.equal(mediaQuery.listenerCount(), 0);
    assert.equal(windowValue.listenerCount(), 0);
    assert.equal(signal.listenerCount(), 0);
    assert.equal(lifecycle.controls.at(-1).listenerCount(), 0);
    assert.equal(lifecycle.canvases.at(-1).listenerCount(), 0);
    assert.equal(animationFrames.size, 0);
    assert.equal(scheduledTimeouts.size, 0);
    assert(lifecycle.geometries.every((resource) => resource.disposeCalls > 0));
    assert(lifecycle.materials.every((resource) => resource.disposeCalls > 0));
  } finally {
    restore();
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

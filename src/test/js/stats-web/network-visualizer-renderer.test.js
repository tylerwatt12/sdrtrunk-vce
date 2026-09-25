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
        camera
      };
      lifecycle.graphStates.push(state);
      const graph = {};
      const chain = [
        'showNavInfo', 'backgroundColor', 'nodeId', 'nodeVal', 'nodeThreeObjectExtend', 'linkSource',
        'linkTarget', 'linkThreeObjectExtend', 'linkCurvature', 'linkDirectionalParticles',
        'linkDirectionalParticleWidth', 'linkDirectionalParticleSpeed', 'linkDirectionalParticleColor',
        'linkDirectionalParticleThreeObject',
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
      graph.scene = () => ({});
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
  const scheduledTimeouts = new Map();
  let nextTimeout = 1;
  const runTimeout = (id) => {
    const scheduled = scheduledTimeouts.get(id);
    assert(scheduled, `expected timer ${id} to remain scheduled`);
    scheduledTimeouts.delete(id);
    scheduled.callback(...scheduled.args);
  };
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
  install('setTimeout', (callback, delay = 0, ...args) => {
    const id = nextTimeout;
    nextTimeout += 1;
    scheduledTimeouts.set(id, { callback, delay, args });
    return id;
  });
  install('clearTimeout', (id) => scheduledTimeouts.delete(id));

  try {
    const host = new FakeElement(documentValue);
    const library = fakeRendererLibrary(documentValue, lifecycle);
    for (let cycle = 1; cycle <= 3; cycle += 1) {
      const signal = new FakeEventTarget();
      signal.aborted = false;
      const renderer = await createNetworkVisualizerRenderer({
        host,
        library,
        signal,
        autoFitOnFirstData: false
      });
      const graphState = lifecycle.graphStates.at(-1);
      const controls = lifecycle.controls.at(-1);
      assert.equal(renderer.diagnostics().autoRotateRequested, true);
      assert.equal(renderer.diagnostics().autoRotateEffective, false,
        'auto-rotation waits until the graph has visible content');
      assert.equal(renderer.diagnostics().arrangeMode, false);
      assert.equal(graphState.nodeDragEnabled, false);
      assert.equal(controls.enablePan, true);
      assert.equal(controls.enableRotate, true);
      assert.equal(controls.zoomToCursor, true);
      assert.equal(controls.mouseButtons.LEFT, library.MOUSE.ROTATE);
      assert.equal(controls.mouseButtons.MIDDLE, library.MOUSE.DOLLY);
      assert.equal(controls.mouseButtons.RIGHT, library.MOUSE.PAN);
      assert.equal(controls.touches.ONE, library.TOUCH.ROTATE);
      assert.equal(controls.touches.TWO, library.TOUCH.DOLLY_ROTATE);

      const initialCameraPose = renderer.getCameraPose();
      const initialGraph = { generation: cycle, nodes: [
        { key: 'system', type: 'universe', x: 0, y: 0, z: 7 },
        { key: 'group', type: 'group', x: 20, y: 0, z: 5 },
        { key: 'radio', type: 'radio', x: 35, y: 0, z: 9 }
      ], links: [
        { key: 'tx', source: 'radio', target: 'group', type: 'tx', active: true, particleCount: 1 }
      ], labels: ['system', 'group', 'radio'], effects: [
        { id: `effect-${cycle}`, type: 'destination_highlight', nodeKey: 'group', targetKey: 'group',
          createdAtMs: Date.now(), expiresAtMs: Date.now() + 1_000 }
      ] };
      renderer.setGraphData(initialGraph);
      assert.equal(renderer.diagnostics().animatedEffects, 1);
      assert.equal(renderer.diagnostics().autoRotateRequested, true);
      assert.equal(renderer.diagnostics().autoRotateEffective, false,
        'overview rotation waits until the opening camera frame is established');
      renderer.setNavigationScope({ level: 'system', universeKey: 'system' });
      renderer.setAutoRotate(true);
      assert.equal(renderer.diagnostics().autoRotateEffective, true,
        'the default rotation request becomes effective in a framed system scope');
      assert.equal(graphState.nodeObjects.get('system').scale.value, 22);
      assert.equal(graphState.nodeObjects.get('group').scale.value, 9);
      assert.equal(graphState.nodeObjects.get('radio').scale.value, 3.8);
      const destinationEmphasis = graphState.nodeObjects.get('group').children[1];
      assert.equal(destinationEmphasis.visible, true);
      assert.equal(destinationEmphasis.material.color.value, '#e6a64c',
        'destination highlighting uses amber arrival emphasis');
      assert.notEqual(destinationEmphasis.material.color.value, '#69d59f',
        'destination highlighting must not claim active transmission');
      const txLine = graphState.linkObjects.get('tx');
      assert(txLine instanceof library.Line2);
      assert(txLine.geometry instanceof library.LineGeometry);
      assert.equal(txLine.geometry.setPositionsCalls, 1,
        'moving a thick link mutates its initial interleaved buffer instead of rebuilding attributes');
      assert.equal(txLine.lineDistanceComputations, 1,
        'dash distances are allocated once and updated in place');
      assert.equal(txLine.geometry.attributes.instanceStart.data.needsUpdate, true);
      assert.equal(txLine.geometry.attributes.instanceDistanceStart.data.needsUpdate, true);
      assert.equal(txLine.material.linewidth, 4);
      assert.deepEqual({ width: txLine.material.resolution.width, height: txLine.material.resolution.height },
        { width: 640, height: 360 });

      const radioObject = graphState.nodeObjects.get('radio');
      const thickLinkGeometry = txLine.geometry;
      const pathBeforeLiveMove = Array.from(txLine.userData.visualizerPositions);
      const graphDataCallsBeforeLiveMove = graphState.graphDataCalls;
      const disposalsBeforeLiveMove = graphState.recursivelyDisposed.length;
      const liveRadio = initialGraph.nodes.find((node) => node.key === 'radio');
      Object.assign(liveRadio, { x: 52, y: 8, z: 13, vx: 0.75, vy: -0.25, vz: 0.5,
        fx: 52, fy: 8, fz: 13, selected: true });
      renderer.refresh();
      const renderedRadio = graphState.data.nodes.find((node) => node.key === 'radio');
      assert.equal(graphState.graphDataCalls, graphDataCallsBeforeLiveMove,
        'live layout refreshes do not rebuild force-graph data');
      assert.equal(graphState.recursivelyDisposed.length, disposalsBeforeLiveMove,
        'live layout refreshes do not trigger structural object disposal');
      assert.equal(graphState.nodeObjects.get('radio'), radioObject,
        'live movement retains the existing custom node object');
      assert.deepEqual({ x: radioObject.position.x, y: radioObject.position.y, z: radioObject.position.z },
        { x: 52, y: 8, z: 13 });
      assert.deepEqual({ x: renderedRadio.x, y: renderedRadio.y, z: renderedRadio.z,
        vx: renderedRadio.vx, vy: renderedRadio.vy, vz: renderedRadio.vz,
        fx: renderedRadio.fx, fy: renderedRadio.fy, fz: renderedRadio.fz },
        { x: 52, y: 8, z: 13, vx: 0.75, vy: -0.25, vz: 0.5, fx: 52, fy: 8, fz: 13 });
      assert.equal(radioObject.children[2].visible, true,
        'live source visual fields update the existing node object');
      assert.equal(graphState.linkObjects.get('tx'), txLine,
        'live movement retains the existing thick-link object');
      assert.equal(txLine.geometry, thickLinkGeometry,
        'live movement retains the thick-link geometry');
      assert.equal(txLine.geometry.setPositionsCalls, 1,
        'live movement updates the interleaved position buffer in place');
      assert.notDeepEqual(Array.from(txLine.userData.visualizerPositions), pathBeforeLiveMove);
      assert.deepEqual(Array.from(txLine.userData.visualizerPositions.slice(0, 3)), [52, 8, 13],
        'the updated thick-link path starts at the moved radio position');

      const cachedUniverseGeometry = graphState.nodeObjects.get('system').children[0].geometry;
      const cachedUniverseMaterial = graphState.nodeObjects.get('system').children[0].material;
      const cachedActiveLinkMaterial = txLine.material;
      const ownedSystemEmphasisMaterial = graphState.nodeObjects.get('system').children[1].material;
      const removedLinkGeometry = txLine.geometry;
      renderer.setGraphData({ generation: cycle, nodes: [
        { key: 'system-b', type: 'universe', x: -10, y: 4, z: 2 },
        { key: 'group-b', type: 'group', x: 8, y: 4, z: 2 },
        { key: 'radio-b', type: 'radio', x: 16, y: 4, z: 2 }
      ], links: [
        { key: 'tx-b', source: 'radio-b', target: 'group-b', type: 'tx', active: true, particleCount: 1 }
      ], labels: ['system-b', 'group-b', 'radio-b'], effects: [] });
      assert(graphState.recursivelyDisposed.includes('node:system'));
      assert(graphState.recursivelyDisposed.includes('link:tx'));
      assert.equal(removedLinkGeometry.disposeCalls, 1,
        'a removed link releases its renderer-owned geometry exactly once');
      assert.equal(ownedSystemEmphasisMaterial.disposeCalls, 1,
        'a removed node releases its node-owned emphasis material exactly once');
      assert.equal(cachedUniverseGeometry.disposeCalls, 0,
        'recursive graph cleanup cannot dispose renderer-wide cached geometry during a scope swap');
      assert.equal(cachedUniverseMaterial.disposeCalls, 0,
        'recursive graph cleanup cannot dispose renderer-wide cached node material during a scope swap');
      assert.equal(cachedActiveLinkMaterial.disposeCalls, 0,
        'recursive graph cleanup cannot dispose renderer-wide cached link material during a scope swap');
      assert.equal(graphState.nodeObjects.get('system-b').children[0].geometry, cachedUniverseGeometry);
      assert.equal(graphState.nodeObjects.get('system-b').children[0].material, cachedUniverseMaterial);
      assert.equal(graphState.linkObjects.get('tx-b').material, cachedActiveLinkMaterial);

      renderer.setGraphData({ generation: cycle, nodes: [
        { key: 'system', type: 'universe', x: 0, y: 0, z: 7 },
        { key: 'group', type: 'group', x: 20, y: 0, z: 5 },
        { key: 'radio', type: 'radio', x: 35, y: 0, z: 9 }
      ], links: [
        { key: 'tx', source: 'radio', target: 'group', type: 'tx', active: true, particleCount: 1 }
      ], labels: ['system', 'group', 'radio'], effects: [] });

      renderer.setArrange(true);
      assert.equal(renderer.diagnostics().arrangeMode, true);
      assert.equal(graphState.nodeDragEnabled, true);
      renderer.setAutoRotate(true);
      assert.equal(renderer.diagnostics().autoRotateEffective, false,
        'Arrange mode keeps camera rotation paused while nodes can be dragged');
      renderer.setArrange(false);
      assert.equal(renderer.diagnostics().arrangeMode, false);
      assert.equal(graphState.nodeDragEnabled, false);

      renderer.setAutoRotate(false);
      assert.equal(renderer.diagnostics().autoRotateRequested, false);
      assert.equal(renderer.diagnostics().autoRotateEffective, false);
      renderer.setAutoRotate(true);
      assert.equal(renderer.diagnostics().autoRotateEffective, true);
      controls.dispatchEvent({ type: 'start' });
      assert.equal(renderer.diagnostics().autoRotateEffective, false,
        'pointer interaction immediately pauses auto-rotation');
      renderer.setAutoRotate(true);
      assert.equal(renderer.diagnostics().autoRotateEffective, false,
        'enabling rotation recenters an off-axis cursor target before orbit resumes');
      renderer.frameScope({ keys: ['system'], duration: 0 });
      renderer.setAutoRotate(true);
      assert.equal(renderer.diagnostics().autoRotateEffective, true);

      renderer.setReducedMotion(true);
      assert.equal(renderer.diagnostics().autoRotateRequested, true);
      assert.equal(renderer.diagnostics().autoRotateEffective, false,
        'reduced motion suppresses rotation without losing the requested preference');
      renderer.setAutoRotate(true);
      assert.equal(renderer.diagnostics().autoRotateEffective, false);
      renderer.setReducedMotion(false);
      renderer.setAutoRotate(true);
      assert.equal(renderer.diagnostics().autoRotateEffective, true);

      renderer.frameScope({ keys: ['system'], duration: 720 });
      const cameraBeforeDynamicReduction = renderer.getCameraPose();
      renderer.setReducedMotion(true);
      const pendingFramesAfterDynamicReduction = [...animationFrames.entries()];
      pendingFramesAfterDynamicReduction.forEach(([id, callback]) => {
        animationFrames.delete(id);
        callback(performance.now() + 2_000);
      });
      assert.deepEqual(renderer.getCameraPose(), cameraBeforeDynamicReduction,
        'enabling reduced motion cancels an in-flight camera transition');
      renderer.setReducedMotion(false);

      assert.deepEqual(renderer.setNavigationScope({ level: 'system', universeKey: 'system' }), {
        level: 'system', universeKey: 'system', groupKey: ''
      });
      assert.deepEqual(renderer.diagnostics().scope, {
        level: 'system', universeKey: 'system', groupKey: ''
      });
      assert.equal(renderer.frameScope({ keys: ['system'], duration: 0 }), true);
      assert.deepEqual(renderer.getCameraPose().target, { x: 0, y: 0, z: 7 });
      assert(graphState.camera.lookAtCalls.length > 0);
      assert(controls.updateCalls > 0);
      assert.equal(renderer.restoreCameraPose(initialCameraPose, 0), true);
      assert.deepEqual(renderer.getCameraPose(), initialCameraPose);

      renderer.setMode('flat', { duration: 0 });
      assert.equal(controls.enablePan, true);
      assert.equal(controls.enableRotate, false);
      assert.equal(controls.zoomToCursor, true);
      assert.equal(controls.mouseButtons.LEFT, library.MOUSE.PAN);
      assert.equal(controls.mouseButtons.RIGHT, library.MOUSE.PAN);
      assert.equal(controls.touches.ONE, library.TOUCH.PAN);
      assert.equal(controls.touches.TWO, library.TOUCH.DOLLY_PAN);
      renderer.setMode('3d', { duration: 0 });
      assert.equal(controls.enablePan, true);
      assert.equal(controls.enableRotate, true);
      assert.equal(controls.mouseButtons.LEFT, library.MOUSE.ROTATE);
      assert.equal(controls.mouseButtons.RIGHT, library.MOUSE.PAN);
      assert.equal(controls.touches.ONE, library.TOUCH.ROTATE);
      assert.equal(controls.touches.TWO, library.TOUCH.DOLLY_ROTATE);
      renderer.frameScope({ keys: ['system'], duration: 0 });
      renderer.setAutoRotate(true);
      assert.equal(renderer.diagnostics().autoRotateEffective, true);

      documentValue.hidden = true;
      documentValue.dispatchEvent({ type: 'visibilitychange' });
      assert.equal(renderer.diagnostics().animatedEffects, 0);
      documentValue.hidden = false;
      documentValue.dispatchEvent({ type: 'visibilitychange' });
      renderer.setFrozen(true);
      renderer.setAutoRotate(true);
      assert.equal(renderer.diagnostics().autoRotateEffective, true,
        'freezing layout physics does not freeze the independent camera orbit');
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

      const pulsedLink = graphState.data.links.find((link) => link.key === 'tx');
      const timersBeforeReservation = new Set(scheduledTimeouts.keys());
      assert.equal(renderer.pulse('tx'), true);
      assert.equal(renderer.diagnostics().pendingParticles, 1);
      let reservationTimer = [...scheduledTimeouts.keys()].find((id) => !timersBeforeReservation.has(id));
      assert(reservationTimer, 'a pulse schedules a bounded check for actual particle removal');
      const reservedParticleGroup = pulsedLink.__singleHopPhotonsObj;
      const reservedParticle = reservedParticleGroup.children.at(-1);
      for (let poll = 0; poll < 3; poll += 1) {
        const timersBeforePoll = new Set(scheduledTimeouts.keys());
        runTimeout(reservationTimer);
        assert.equal(renderer.diagnostics().pendingParticles, 1,
          'the reservation remains while the emitted particle is still owned by the graph');
        reservationTimer = [...scheduledTimeouts.keys()].find((id) => !timersBeforePoll.has(id));
        assert(reservationTimer, 'an in-flight particle schedules another bounded removal check');
      }
      reservedParticleGroup.remove(reservedParticle);
      runTimeout(reservationTimer);
      assert.equal(renderer.diagnostics().pendingParticles, 0,
        'the reservation is released only after the graph removes the actual particle');

      const timersBeforeReducedMotionPulses = new Set(scheduledTimeouts.keys());
      assert.equal(renderer.pulse('tx'), true);
      assert.equal(renderer.pulse('tx'), true);
      const emittedParticles = graphState.emittedParticles.slice(-2);
      assert.equal(emittedParticles.length, 2);
      assert.notEqual(emittedParticles[0], emittedParticles[1]);
      assert.equal(emittedParticles[0].geometry, emittedParticles[1].geometry,
        'transient particles reuse one renderer-owned geometry');
      assert.equal(emittedParticles[0].material, emittedParticles[1].material,
        'transient particles reuse one renderer-owned material');
      const sharedParticleGeometry = emittedParticles[0].geometry;
      const sharedParticleMaterial = emittedParticles[0].material;
      const reducedMotionParticleGroup = pulsedLink.__singleHopPhotonsObj;
      assert(reducedMotionParticleGroup?.children.length >= 2);
      assert.equal(renderer.diagnostics().pendingParticles, 2);
      const pulseTimers = [...scheduledTimeouts.keys()].filter((id) =>
        !timersBeforeReducedMotionPulses.has(id));
      assert.equal(pulseTimers.length, 2);
      renderer.setReducedMotion(true);
      assert.equal(renderer.diagnostics().pendingParticles, 0,
        'enabling reduced motion immediately clears all in-flight reservations');
      assert.equal(reducedMotionParticleGroup.children.length, 0,
        'enabling reduced motion removes transient particle meshes already in the graph');
      assert.equal(Object.hasOwn(pulsedLink, '__singleHopPhotonsObj'), false,
        'the graph particle group is detached rather than replayed when motion is restored');
      assert(pulseTimers.every((id) => !scheduledTimeouts.has(id)),
        'enabling reduced motion cancels every particle removal poll');
      assert.equal(sharedParticleGeometry.disposeCalls, 0);
      assert.equal(sharedParticleMaterial.disposeCalls, 0);
      renderer.setReducedMotion(false);
      assert.equal(renderer.pulse('tx'), true);
      assert.equal(renderer.pulse('tx'), true);
      assert(pulsedLink.__singleHopPhotonsObj?.children.length >= 2);
      assert(host.children.length > 0);
      renderer.setNavigationScope({ level: 'overview' });
      renderer.setArrange(false);
      renderer.dispose();
      renderer.dispose();
      assert.equal(cachedUniverseGeometry.disposeCalls, 1,
        'renderer-wide cached geometry is disposed once at renderer teardown');
      assert.equal(cachedUniverseMaterial.disposeCalls, 1,
        'renderer-wide cached node material is disposed once at renderer teardown');
      assert.equal(cachedActiveLinkMaterial.disposeCalls, 1,
        'renderer-wide cached link material is disposed once at renderer teardown');
      assert.equal(sharedParticleGeometry.disposeCalls, 1);
      assert.equal(sharedParticleMaterial.disposeCalls, 1);
      assert.equal(Object.hasOwn(pulsedLink, '__singleHopPhotonsObj'), false,
        'teardown drains transient particle meshes that outlive their reservation');
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
        autoRotateRequested: true,
        autoRotateEffective: false,
        arrangeMode: false,
        scope: { level: 'overview', universeKey: '', groupKey: '' },
        camera: null,
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
      assert.equal(scheduledTimeouts.size, 0);
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
    linkWidth,
    nodeGeometryKind,
    nodeRadius,
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
  const alternateLabels = chooseLabelPlacements([
    { key: 'primary', x: 100, y: 50, width: 90, height: 20, priority: 2,
      offsets: [{ x: 10, y: 0, index: 0 }] },
    { key: 'alternate', x: 100, y: 50, width: 90, height: 20, priority: 1,
      offsets: [{ x: 10, y: 0, index: 0 }, { x: -100, y: 0, index: 1 }] }
  ], { width: 260, height: 120 }, 2);
  assert.deepEqual(alternateLabels.map(({ key, offsetIndex }) => ({ key, offsetIndex })), [
    { key: 'primary', offsetIndex: 0 }, { key: 'alternate', offsetIndex: 1 }
  ]);
  const edgeLabel = chooseLabelPlacements([
    { key: 'edge', x: 250, y: 50, width: 90, height: 20, priority: 1,
      offsets: [{ x: 10, y: 0, index: 0 }, { x: -100, y: 0, index: 1 }] }
  ], { width: 260, height: 120 }, 1);
  assert.deepEqual(edgeLabel.map(({ key, offsetIndex }) => ({ key, offsetIndex })), [
    { key: 'edge', offsetIndex: 1 }
  ], 'labels prefer a fully visible alternate anchor over a clipped preferred anchor');
  assert(edgeLabel[0].rectangle.left >= 0 && edgeLabel[0].rectangle.right <= 260 &&
    edgeLabel[0].rectangle.top >= 0 && edgeLabel[0].rectangle.bottom <= 120,
  'the chosen edge fallback is fully visible within the viewport');

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
  assert.equal(nodeVisualState({ type: 'group' }, { type: 'destination_highlight' }), 'arrival');
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
  assert.equal(nodeRadius({ type: 'universe' }), 22);
  assert.equal(nodeRadius({ type: 'group' }), 9);
  assert.equal(nodeRadius({ type: 'aggregate' }), 6);
  assert.equal(nodeRadius({ type: 'radio' }), 3.8);
  assert.equal(linkWidth({ type: 'affiliation' }), 2.25);
  assert.equal(linkWidth({ type: 'migration' }), 3);
  assert.equal(linkWidth({ type: 'tx', active: true }), 4);
  assert.equal(linkWidth({ type: 'affiliation', faded: true }), 1.25);

  await verifyRendererDisposal(createNetworkVisualizerRenderer);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

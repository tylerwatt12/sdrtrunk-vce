'use strict';

const TILE_SIZE = 256;
const TILE_HOST = 'https://tile.openstreetmap.org';
const MIN_ZOOM = 2;
const MAX_ZOOM = 17;
const MAX_ENTITIES = 256;
const MAX_TRAIL_POINTS = 10;
const ICON_SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

function clamp(value, minimum, maximum) {
  return Math.max(minimum, Math.min(maximum, value));
}

function wrap(value, limit) {
  return ((value % limit) + limit) % limit;
}

function wrappedOffset(pointX, centerX, worldWidth) {
  return wrap(pointX - centerX + worldWidth / 2, worldWidth) - worldWidth / 2;
}

function project(latitude, longitude, zoom) {
  const lat = clamp(Number(latitude), -85.05112878, 85.05112878);
  const lon = Number(longitude);
  const scale = TILE_SIZE * 2 ** zoom;
  const sine = Math.sin(lat * Math.PI / 180);
  return {
    x: (lon + 180) / 360 * scale,
    y: (0.5 - Math.log((1 + sine) / (1 - sine)) / (4 * Math.PI)) * scale
  };
}

function unproject(x, y, zoom) {
  const scale = TILE_SIZE * 2 ** zoom;
  return {
    latitude: Math.atan(Math.sinh(Math.PI * (1 - 2 * y / scale))) * 180 / Math.PI,
    longitude: wrap(x / scale * 360, 360) - 180
  };
}

function visibleTiles(center, zoom, width, height) {
  const worldTiles = 2 ** zoom;
  const left = center.x - width / 2;
  const top = center.y - height / 2;
  const firstX = Math.floor(left / TILE_SIZE);
  const lastX = Math.floor((left + width - 1) / TILE_SIZE);
  const firstY = clamp(Math.floor(top / TILE_SIZE), 0, worldTiles - 1);
  const lastY = clamp(Math.floor((top + height - 1) / TILE_SIZE), 0, worldTiles - 1);
  const tiles = [];
  for (let y = firstY; y <= lastY; y += 1) {
    for (let x = firstX; x <= lastX; x += 1) {
      tiles.push({ zoom, worldX: x, x: ((x % worldTiles) + worldTiles) % worldTiles, y,
        left: x * TILE_SIZE - left, top: y * TILE_SIZE - top });
    }
  }
  return tiles;
}

function normalizeSnapshot(value) {
  if (!value || !Array.isArray(value.entities)) throw new Error('The receiver returned an invalid map snapshot.');
  const entities = value.entities.slice(0, MAX_ENTITIES).flatMap((entity) => {
    const positions = (Array.isArray(entity?.positions) ? entity.positions : [])
      .slice(0, MAX_TRAIL_POINTS).filter((position) =>
        Number.isFinite(Number(position?.latitude)) && Math.abs(Number(position.latitude)) <= 90 &&
        Number.isFinite(Number(position?.longitude)) && Math.abs(Number(position.longitude)) <= 180)
      .map((position) => ({ latitude: Number(position.latitude), longitude: Number(position.longitude),
        timestamp_ms: Number(position.timestamp_ms) || 0 })).reverse();
    if (!positions.length || typeof entity?.id !== 'string' || !entity.id) return [];
    return [{ id: entity.id, label: String(entity.label || entity.identifier || entity.id),
      identifier: String(entity.identifier || ''), alias_list: String(entity.alias_list || ''),
      icon: ICON_SLUG.test(String(entity.icon || '')) ? entity.icon : 'no-icon',
      color: /^#[0-9a-fA-F]{6}$/.test(String(entity.color || '')) ? entity.color : null,
      heading: Number(entity.heading), speed_kph: Number(entity.speed_kph), positions }];
  });
  return { entities, generated_at_ms: Number(value.generated_at_ms) || 0,
    dropped_observations: Number(value.dropped_observations) || 0,
    evicted_entities: Number(value.evicted_entities) || 0 };
}

function fitPoints(points, width, height) {
  if (!points.length) return null;
  const xs = points.map((point) => wrap((point.longitude + 180) / 360, 1)).sort((a, b) => a - b);
  let largestGap = -1;
  let arcStart = xs[0];
  for (let index = 0; index < xs.length; index += 1) {
    const next = index + 1 < xs.length ? xs[index + 1] : xs[0] + 1;
    const gap = next - xs[index];
    if (gap > largestGap) {
      largestGap = gap;
      arcStart = wrap(next, 1);
    }
  }
  const longitudeSpan = 1 - largestGap;
  const longitudeCenter = wrap(arcStart + longitudeSpan / 2, 1);
  const ys = points.map((point) => project(point.latitude, 0, 0).y);
  const yMinimum = Math.min(...ys);
  const yMaximum = Math.max(...ys);
  for (let zoom = 14; zoom >= MIN_ZOOM; zoom -= 1) {
    const worldWidth = TILE_SIZE * 2 ** zoom;
    if ((longitudeSpan * worldWidth <= Math.max(1, width - 96) &&
        (yMaximum - yMinimum) * 2 ** zoom <= Math.max(1, height - 96)) || zoom === MIN_ZOOM) {
      return { zoom, center: { x: longitudeCenter * worldWidth,
        y: (yMinimum + yMaximum) / 2 * 2 ** zoom } };
    }
  }
  return null;
}

function createListenMap({ node, iconGlyph, fetchSnapshot, iconUrl }) {
  const root = node('div', 'listen-map-workspace data-workspace');
  const toolbar = node('div', 'listen-map-toolbar ui-toolbar');
  const status = node('span', 'listen-map-status', 'Loading locations…');
  status.setAttribute('role', 'status');
  const refresh = node('button', 'ui-button ui-button-secondary', 'Refresh');
  refresh.type = 'button';
  refresh.prepend(iconGlyph('icon-refresh'));
  const fit = node('button', 'ui-button ui-button-secondary', 'Show all');
  fit.type = 'button';
  fit.disabled = true;
  toolbar.append(status, refresh, fit);

  const layout = node('div', 'listen-map-layout');
  const mapPanel = node('section', 'section ui-section listen-map-panel');
  mapPanel.append(node('div', 'section-title ui-section-title', 'Map'));
  const viewport = node('div', 'listen-map-viewport');
  viewport.tabIndex = 0;
  viewport.setAttribute('role', 'region');
  viewport.setAttribute('aria-label', 'Location map. Drag to pan; use the zoom buttons to change scale.');
  const tilesHost = node('div', 'listen-map-tiles');
  tilesHost.setAttribute('aria-hidden', 'true');
  const trails = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  trails.classList.add('listen-map-trails');
  trails.setAttribute('aria-hidden', 'true');
  const markers = node('div', 'listen-map-markers');
  const zoomControls = node('div', 'listen-map-zoom ui-action-row');
  const zoomIn = node('button', 'ui-button ui-button-secondary ui-icon-button');
  zoomIn.type = 'button';
  zoomIn.setAttribute('aria-label', 'Zoom in');
  zoomIn.append(iconGlyph('icon-plus'));
  const zoomOut = node('button', 'ui-button ui-button-secondary ui-icon-button');
  zoomOut.type = 'button';
  zoomOut.setAttribute('aria-label', 'Zoom out');
  zoomOut.append(iconGlyph('icon-zoom-out'));
  zoomControls.append(zoomIn, zoomOut);
  const attribution = node('div', 'listen-map-attribution');
  const attributionLink = node('a', '', '© OpenStreetMap contributors');
  attributionLink.href = 'https://www.openstreetmap.org/copyright';
  attributionLink.target = '_blank';
  attributionLink.rel = 'noopener noreferrer';
  attribution.append(attributionLink);
  viewport.append(tilesHost, trails, markers, zoomControls, attribution);
  mapPanel.append(viewport);

  const sidebar = node('section', 'section ui-section listen-map-sidebar');
  sidebar.append(node('div', 'section-title ui-section-title', 'Locations'));
  const list = node('div', 'listen-map-list');
  const details = node('div', 'listen-map-details');
  sidebar.append(list, details);
  layout.append(mapPanel, sidebar);
  root.append(toolbar, layout);

  let snapshot = { entities: [], generated_at_ms: 0, dropped_observations: 0, evicted_entities: 0 };
  let selectedId = '';
  let center = project(39.5, -98.35, 4);
  let zoom = 4;
  let initialized = false;
  let closed = false;
  let inFlight = false;
  let frame = 0;
  let drag = null;
  const tileNodes = new Map();

  const dimensions = () => ({ width: Math.max(1, viewport.clientWidth), height: Math.max(1, viewport.clientHeight) });
  const screenPoint = (position) => {
    const point = project(position.latitude, position.longitude, zoom);
    const world = TILE_SIZE * 2 ** zoom;
    const offsetX = wrappedOffset(point.x, center.x, world);
    const { width, height } = dimensions();
    return { x: width / 2 + offsetX, y: height / 2 + point.y - center.y };
  };

  function draw() {
    frame = 0;
    if (closed || !viewport.isConnected) return;
    const { width, height } = dimensions();
    const current = new Set();
    for (const tile of visibleTiles(center, zoom, width, height)) {
      const key = `${tile.zoom}/${tile.worldX}/${tile.y}`;
      current.add(key);
      let image = tileNodes.get(key);
      if (!image) {
        image = document.createElement('img');
        image.alt = '';
        image.draggable = false;
        image.decoding = 'async';
        image.referrerPolicy = 'origin';
        image.src = `${TILE_HOST}/${tile.zoom}/${tile.x}/${tile.y}.png`;
        tileNodes.set(key, image);
        tilesHost.append(image);
      }
      image.style.left = `${tile.left}px`;
      image.style.top = `${tile.top}px`;
    }
    for (const [key, image] of tileNodes) {
      if (!current.has(key)) { image.remove(); tileNodes.delete(key); }
    }

    trails.setAttribute('viewBox', `0 0 ${width} ${height}`);
    trails.replaceChildren();
    const focusedMarkerId = markers.contains(document.activeElement) ? document.activeElement.dataset.entityId : null;
    markers.replaceChildren();
    for (const entity of snapshot.entities) {
      const points = entity.positions.map(screenPoint);
      if (points.length > 1) {
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', points.map((point, index) => `${index ? 'L' : 'M'}${point.x} ${point.y}`).join(' '));
        path.setAttribute('stroke', entity.color || 'var(--accent)');
        path.classList.add(entity.id === selectedId ? 'listen-map-trail-selected' : 'listen-map-trail');
        trails.append(path);
      }
      const point = points.at(-1);
      if (!point || point.x < -32 || point.y < -32 || point.x > width + 32 || point.y > height + 32) continue;
      const marker = node('button', `listen-map-marker ui-button ${entity.id === selectedId ?
        'ui-button-primary' : 'ui-button-secondary'}`);
      marker.type = 'button';
      marker.setAttribute('aria-label', `Select ${entity.label}`);
      marker.setAttribute('aria-pressed', String(entity.id === selectedId));
      marker.dataset.entityId = entity.id;
      marker.title = entity.label;
      marker.style.left = `${point.x}px`;
      marker.style.top = `${point.y}px`;
      const icon = document.createElement('img');
      icon.alt = '';
      icon.loading = 'lazy';
      icon.src = iconUrl(entity.icon);
      marker.append(icon);
      marker.addEventListener('pointerdown', (event) => event.stopPropagation());
      marker.addEventListener('click', () => select(entity.id));
      markers.append(marker);
    }
    if (focusedMarkerId) [...markers.querySelectorAll('button')]
      .find((marker) => marker.dataset.entityId === focusedMarkerId)?.focus({ preventScroll: true });
    zoomIn.disabled = zoom >= MAX_ZOOM;
    zoomOut.disabled = zoom <= MIN_ZOOM;
  }

  function scheduleDraw() {
    if (!frame && !closed) frame = window.requestAnimationFrame(draw);
  }

  function renderSelection() {
    const focusedId = list.contains(document.activeElement) ? document.activeElement.dataset.entityId : null;
    const scrollTop = list.scrollTop;
    list.replaceChildren();
    if (!snapshot.entities.length) list.append(node('div', 'empty', 'No mapped locations yet.'));
    for (const entity of snapshot.entities) {
      const button = node('button', `listen-map-list-item ui-button ${entity.id === selectedId ?
        'ui-button-primary' : 'ui-button-secondary'}`);
      button.type = 'button';
      button.setAttribute('aria-pressed', String(entity.id === selectedId));
      button.dataset.entityId = entity.id;
      const icon = document.createElement('img');
      icon.alt = '';
      icon.loading = 'lazy';
      icon.src = iconUrl(entity.icon);
      button.append(icon, node('span', '', entity.label));
      button.addEventListener('click', () => select(entity.id, true));
      list.append(button);
    }
    list.scrollTop = scrollTop;
    if (focusedId) [...list.querySelectorAll('button')]
      .find((button) => button.dataset.entityId === focusedId)?.focus({ preventScroll: true });
    details.replaceChildren();
    const selected = snapshot.entities.find((entity) => entity.id === selectedId);
    if (!selected) return;
    const latest = selected.positions.at(-1);
    const facts = node('dl', 'ui-facts');
    for (const [label, value] of [
      ['Identifier', selected.identifier || '—'], ['Alias list', selected.alias_list || '—'],
      ['Position', `${latest.latitude.toFixed(5)}, ${latest.longitude.toFixed(5)}`],
      ['Heading', Number.isFinite(selected.heading) ? `${Math.round(selected.heading)}°` : '—'],
      ['Speed', Number.isFinite(selected.speed_kph) ? `${Math.round(selected.speed_kph)} km/h` : '—'],
      ['Trail points', String(selected.positions.length)],
      ['Last seen', latest.timestamp_ms > 0 ? new Date(latest.timestamp_ms).toLocaleString() : '—']
    ]) {
      const fact = node('div', 'ui-fact');
      fact.append(node('dt', '', label), node('dd', '', value));
      facts.append(fact);
    }
    details.append(node('h3', '', selected.label), facts);
  }

  function select(id, centerOnEntity = false) {
    selectedId = id;
    if (centerOnEntity) {
      const latest = snapshot.entities.find((entity) => entity.id === id)?.positions.at(-1);
      if (latest) center = project(latest.latitude, latest.longitude, zoom);
    }
    renderSelection();
    scheduleDraw();
  }

  function fitAll() {
    const points = snapshot.entities.map((entity) => entity.positions.at(-1)).filter(Boolean);
    const { width, height } = dimensions();
    const fitted = fitPoints(points, width, height);
    if (!fitted) return;
    ({ zoom, center } = fitted);
    scheduleDraw();
  }

  async function poll() {
    if (closed || inFlight || document.hidden) return;
    inFlight = true;
    refresh.disabled = true;
    try {
      const next = normalizeSnapshot(await fetchSnapshot());
      if (closed) return;
      snapshot = next;
      if (!snapshot.entities.some((entity) => entity.id === selectedId)) {
        selectedId = snapshot.entities[0]?.id || '';
      }
      if (!initialized && snapshot.entities.length) {
        initialized = true;
        const latest = snapshot.entities[0].positions.at(-1);
        zoom = 12;
        center = project(latest.latitude, latest.longitude, zoom);
      }
      const count = snapshot.entities.length;
      const nextStatus = `${count} mapped location${count === 1 ? '' : 's'}` +
        (snapshot.dropped_observations ? ` · ${snapshot.dropped_observations} observations dropped` : '');
      if (status.textContent !== nextStatus) status.textContent = nextStatus;
      fit.disabled = !count;
      renderSelection();
      scheduleDraw();
    } catch (error) {
      if (!closed) status.textContent = error.message || 'Map locations are unavailable.';
    } finally {
      inFlight = false;
      if (!closed) refresh.disabled = false;
    }
  }

  function changeZoom(delta) {
    const next = clamp(zoom + delta, MIN_ZOOM, MAX_ZOOM);
    if (next === zoom) return;
    const geo = unproject(center.x, center.y, zoom);
    zoom = next;
    center = project(geo.latitude, geo.longitude, zoom);
    scheduleDraw();
  }

  refresh.addEventListener('click', () => void poll());
  fit.addEventListener('click', fitAll);
  zoomIn.addEventListener('click', () => changeZoom(1));
  zoomOut.addEventListener('click', () => changeZoom(-1));
  viewport.addEventListener('keydown', (event) => {
    const movements = { ArrowLeft: [80, 0], ArrowRight: [-80, 0], ArrowUp: [0, 80], ArrowDown: [0, -80] };
    if (event.key === '+' || event.key === '=') { changeZoom(1); event.preventDefault(); }
    else if (event.key === '-') { changeZoom(-1); event.preventDefault(); }
    else if (movements[event.key]) {
      center.x = wrap(center.x - movements[event.key][0], TILE_SIZE * 2 ** zoom);
      center.y = clamp(center.y - movements[event.key][1], 0, TILE_SIZE * 2 ** zoom);
      scheduleDraw();
      event.preventDefault();
    }
  });
  viewport.addEventListener('pointerdown', (event) => {
    if (event.button !== 0 || event.target.closest('button, a')) return;
    drag = { x: event.clientX, y: event.clientY, center: { ...center } };
    viewport.setPointerCapture(event.pointerId);
  });
  viewport.addEventListener('pointermove', (event) => {
    if (!drag) return;
    center = { x: wrap(drag.center.x - (event.clientX - drag.x), TILE_SIZE * 2 ** zoom),
      y: clamp(drag.center.y - (event.clientY - drag.y), 0, TILE_SIZE * 2 ** zoom) };
    scheduleDraw();
  });
  const endDrag = () => { drag = null; };
  viewport.addEventListener('pointerup', endDrag);
  viewport.addEventListener('pointercancel', endDrag);
  const resize = typeof ResizeObserver === 'function' ? new ResizeObserver(scheduleDraw) : null;
  resize?.observe(viewport);
  if (!resize) window.addEventListener('resize', scheduleDraw);
  const onVisibility = () => { if (!document.hidden) void poll(); };
  document.addEventListener('visibilitychange', onVisibility);
  const timer = window.setInterval(() => void poll(), 5000);
  window.requestAnimationFrame(() => { scheduleDraw(); void poll(); });

  return { element: root, close() {
    closed = true;
    window.clearInterval(timer);
    if (frame) window.cancelAnimationFrame(frame);
    resize?.disconnect();
    if (!resize) window.removeEventListener('resize', scheduleDraw);
    document.removeEventListener('visibilitychange', onVisibility);
    tileNodes.clear();
  } };
}

export { project, unproject, visibleTiles, normalizeSnapshot, fitPoints, wrappedOffset, createListenMap };

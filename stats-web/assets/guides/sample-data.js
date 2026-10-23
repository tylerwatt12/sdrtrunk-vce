// Tour fixtures belong to a temporary presentation scope. Pages keep their real
// state and opt in by reading a named region while rendering that part of the UI.
export function createGuideSampleData() {
  const regions = new Map();
  const scopes = [];
  const protectedRoots = new Map();
  const blockedEvents = ['click', 'auxclick', 'dblclick', 'contextmenu', 'beforeinput', 'input', 'change', 'submit',
    'pointerdown', 'pointerup', 'pointermove', 'mousedown', 'mouseup', 'mousemove',
    'touchstart', 'touchmove', 'touchend', 'dragstart', 'dragover', 'drop', 'wheel', 'keydown', 'keyup'];
  const captureOptions = { capture: true, passive: false };
  const validId = id => typeof id === 'string' && id.trim().length > 0;
  const current = id => {
    for (let index = scopes.length - 1; index >= 0; index -= 1) {
      if (scopes[index].has(id)) return scopes[index];
    }
    return null;
  };
  const protect = (registration, active) => {
    const { root } = registration;
    if (!root || registration.protected === active) return;
    registration.protected = active;
    let protection = protectedRoots.get(root);
    if (active) {
      if (!protection) {
        const block = event => {
          if ((event.type === 'keydown' || event.type === 'keyup') &&
            (event.key === 'Tab' || event.key === 'Escape')) return;
          event.preventDefault();
          event.stopImmediatePropagation();
        };
        protection = { owners: new Set(), marker: root.getAttribute('data-guide-sample'), block };
        protectedRoots.set(root, protection);
        blockedEvents.forEach(type => root.addEventListener(type, block, captureOptions));
        root.setAttribute('data-guide-sample', '');
      }
      protection.owners.add(registration);
    } else if (protection) {
      protection.owners.delete(registration);
      if (!protection.owners.size) {
        blockedEvents.forEach(type => root.removeEventListener(type, protection.block, captureOptions));
        if (protection.marker === null) root.removeAttribute('data-guide-sample');
        else root.setAttribute('data-guide-sample', protection.marker);
        protectedRoots.delete(root);
      }
    }
  };
  const notify = ids => {
    const failures = [];
    // Protect every affected region before any renderer can expose its fixtures.
    const changed = [...ids].flatMap(id => [...(regions.get(id) || [])]);
    for (const registration of changed) protect(registration, Boolean(current(registration.id)));
    // Snapshot registrations so an adapter may unregister itself while rendering.
    for (const registration of changed) {
      if (!regions.get(registration.id)?.has(registration)) continue;
      try { registration.onChange(); } catch (error) { failures.push(error); }
    }
    if (failures.length) throw failures[0];
  };
  const remove = scope => {
    const index = scopes.indexOf(scope);
    if (index < 0) return;
    const changed = [...scope.keys()].filter(id => current(id) === scope);
    scopes.splice(index, 1);
    // State is already restored even if an adapter fails while repainting it.
    notify(changed);
  };

  return {
    register(id, onChange, { root } = {}) {
      if (!validId(id) || typeof onChange !== 'function') {
        throw new TypeError('A guide sample region needs an ID and a rendering callback.');
      }
      if (root && !['addEventListener', 'removeEventListener', 'getAttribute', 'setAttribute', 'removeAttribute']
        .every(method => typeof root[method] === 'function')) {
        throw new TypeError('A guide sample root must be a UI element.');
      }
      const callbacks = regions.get(id) || new Set();
      // A registration has its own identity, even when a caller reuses a callback.
      const registration = { id, onChange, root, protected: false };
      callbacks.add(registration);
      regions.set(id, callbacks);
      const unregister = () => {
        callbacks.delete(registration);
        protect(registration, false);
        if (!callbacks.size && regions.get(id) === callbacks) regions.delete(id);
      };
      if (current(id)) {
        try {
          protect(registration, true);
          onChange();
        } catch (error) { unregister(); throw error; }
      }
      return unregister;
    },
    read(id, actual) {
      const scope = current(id);
      // Each read is isolated from both the fixture definition and previous UI
      // reads, so a renderer cannot leak edits into another step or real state.
      return scope ? structuredClone(scope.get(id)) : actual;
    },
    isActive(id) { return Boolean(current(id)); },
    activate(samples = []) {
      if (!Array.isArray(samples)) throw new TypeError('Guide samples must be an array.');
      const scope = new Map();
      for (const sample of samples) {
        if (!validId(sample?.id) || !Object.hasOwn(sample, 'value') || scope.has(sample.id)) {
          throw new TypeError('Each guide sample needs a unique region ID and a value.');
        }
        // Validate and copy every value before changing the visible scope.
        scope.set(sample.id, structuredClone(sample.value));
      }
      scopes.push(scope);
      try { notify(scope.keys()); } catch (error) {
        // A partially rendered activation must not leave any samples behind.
        try { remove(scope); } catch { /* Preserve the original rendering failure. */ }
        throw error;
      }
      return () => remove(scope);
    },
    clear() {
      const changed = new Set(scopes.flatMap(scope => [...scope.keys()]));
      scopes.length = 0;
      notify(changed);
    }
  };
}

(function() {
  'use strict';

  function apply(options = {}) {
    const items = Array.isArray(options.items) ? options.items : [];
    const keyOf = typeof options.keyOf === 'function' ? options.keyOf : (item) => item?.id;
    const selectable = typeof options.selectable === 'function' ? options.selectable : () => true;
    const targetKey = String(options.targetKey ?? '');
    const selectedKeys = new Set(Array.from(options.selectedKeys || [], String));
    const maximum = Math.max(1, Number(options.maximum) || Number.MAX_SAFE_INTEGER);
    const targetIndex = items.findIndex((item) => String(keyOf(item)) === targetKey);
    if (targetIndex < 0 || !selectable(items[targetIndex])) {
      return { selectedKeys, anchorKey: options.anchorKey ?? null, limitReached: false };
    }

    let limitReached = false;
    const add = (key) => {
      if (selectedKeys.has(key)) return;
      if (selectedKeys.size >= maximum) {
        limitReached = true;
        return;
      }
      selectedKeys.add(key);
    };

    if (options.range) {
      const anchorKey = String(options.anchorKey ?? targetKey);
      const anchorIndex = items.findIndex((item) => String(keyOf(item)) === anchorKey);
      if (!options.additive) selectedKeys.clear();
      const start = anchorIndex < 0 ? targetIndex : Math.min(anchorIndex, targetIndex);
      const end = anchorIndex < 0 ? targetIndex : Math.max(anchorIndex, targetIndex);
      for (let index = start; index <= end; index += 1) {
        if (selectable(items[index])) add(String(keyOf(items[index])));
      }
      return { selectedKeys, anchorKey: anchorIndex < 0 ? targetKey : anchorKey, limitReached };
    }

    if (options.toggle) {
      if (selectedKeys.has(targetKey)) selectedKeys.delete(targetKey);
      else add(targetKey);
    } else {
      selectedKeys.clear();
      add(targetKey);
    }
    return { selectedKeys, anchorKey: targetKey, limitReached };
  }

  window.SdrtrunkTableSelection = Object.freeze({ apply });
})();

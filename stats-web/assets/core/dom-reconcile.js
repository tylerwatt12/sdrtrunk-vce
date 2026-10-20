/** Update attributes and text without discarding matching links or their keyboard focus.
 * Intended for display-only trees; interactive controls keep their own state/event handlers.
 */
export function patchElementTree(current, next) {
  if (current.isEqualNode(next)) return;
  for (const attribute of [...current.attributes]) {
    if (!next.hasAttribute(attribute.name)) current.removeAttribute(attribute.name);
  }
  for (const attribute of [...next.attributes]) {
    if (current.getAttribute(attribute.name) !== attribute.value)
      current.setAttribute(attribute.name, attribute.value);
  }
  const before = [...current.childNodes];
  const after = [...next.childNodes];
  after.forEach((child, index) => {
    const previous = before[index];
    if (!previous) current.append(child);
    else if (previous.nodeType !== child.nodeType || previous.nodeName !== child.nodeName)
      previous.replaceWith(child);
    else if (child.nodeType === 3) {
      if (previous.nodeValue !== child.nodeValue) previous.nodeValue = child.nodeValue;
    } else if (child.nodeType === 1) patchElementTree(previous, child);
  });
  before.slice(after.length).forEach((child) => child.remove());
}

/** Reconcile stable, unique dataset keys. Existing members keep their nodes and order;
 * new members append in the caller's supplied order, and removed members leave the list.
 */
export function reconcileKeyedChildren(parent, fresh, key, update) {
  const previous = [...parent.children];
  const nextByKey = new Map(fresh.map((element) => [element.dataset[key], element]));
  const currentByKey = new Map(previous.map((element) => [element.dataset[key], element]));
  const keys = previous.map((element) => element.dataset[key]).filter((value) => nextByKey.has(value));
  for (const element of fresh) if (!currentByKey.has(element.dataset[key])) keys.push(element.dataset[key]);
  for (const element of previous) if (!nextByKey.has(element.dataset[key])) element.remove();
  let cursor = parent.firstElementChild;
  for (const value of keys) {
    const next = nextByKey.get(value);
    const current = currentByKey.get(value) || next;
    if (current !== next) update(current, next);
    if (current !== cursor) parent.insertBefore(current, cursor);
    cursor = current.nextElementSibling;
  }
}

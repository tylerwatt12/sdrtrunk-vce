// A context exposes the real owning surface while the step's targets keep the
// precise outline. Owners are declared by each tour; never guess a page-wide
// container from a generic button, field, or table ancestor.
export function contextualTargets(focusTargets, ownerSelector) {
  if (!ownerSelector) return undefined;
  const { owner, surfaces } = typeof ownerSelector === 'string' ? { owner: ownerSelector } : ownerSelector;
  return () => [...new Set(Array.from(typeof focusTargets === 'function' ? focusTargets() : focusTargets || [])
    .flatMap(target => {
      const container = target?.closest?.(owner);
      return container ? [container, ...(surfaces ? container.querySelectorAll(surfaces) : [])] : [];
    }))];
}

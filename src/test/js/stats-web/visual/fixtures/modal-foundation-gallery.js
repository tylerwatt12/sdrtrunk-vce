// Gallery specimens execute the production modal functions, so focus, stacking,
// dismissal and cleanup remain covered by the same foundation as real editors.
export async function createGalleryModalFoundation() {
  const response = await fetch('/assets/app.js');
  if (!response.ok) throw new Error('The production modal foundation could not be loaded.');
  const source = await response.text();
  const start = source.indexOf('function closeReadOnlyModal(');
  const end = source.indexOf('function statsLoggingState(', start);
  if (start < 0 || end < 0) throw new Error('The production modal foundation was not found.');
  const node = (tag, className = '', text = null) => {
    const element = document.createElement(tag);
    element.className = className;
    if (text !== null) element.textContent = String(text);
    return element;
  };
  const iconButton = (_iconId, label, className) => {
    const control = node('button', className);
    control.type = 'button';
    control.setAttribute('aria-label', label);
    const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    icon.setAttribute('aria-hidden', 'true');
    use.setAttribute('href', '#visual-icon-close');
    icon.append(use);
    control.append(icon);
    return control;
  };
  return new Function('node', 'valueNode', 'iconButton',
    `let activeReadOnlyModal = null; ${source.slice(start, end)}
     return { openReadOnlyModal, closeReadOnlyModal, confirmAction };`)(node,
    value => value instanceof Node ? value : document.createTextNode(String(value)), iconButton);
}

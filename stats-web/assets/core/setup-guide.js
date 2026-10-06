// A short introduction to existing actions, using the shared dialog lifecycle.
// Move the actual controls so keyboard and pointer actions use the same workflow.
export function openSetupGuide({ node, openReadOnlyModal }, { title, choices, onDismiss }) {
  const body = node('div', 'ui-setup-guide-choices');
  const originals = [];
  let automaticClose = false;
  let closed = false;
  const restore = () => {
    closed = true;
    originals.forEach(({ button, placeholder, describedBy, choose }) => {
      button.removeEventListener('click', choose, true);
      if (describedBy === null) button.removeAttribute('aria-describedby');
      else button.setAttribute('aria-describedby', describedBy);
      placeholder.replaceWith(button);
    });
  };
  const modal = openReadOnlyModal(title, body, {
    id: 'setup-guide', className: 'ui-setup-guide', closeLabel: 'Dismiss setup guide',
    cleanup: restore,
    onDismiss: () => { if (!automaticClose) onDismiss?.(); }
  });
  if (!modal) return null;
  modal.state.backdrop.classList.add('ui-setup-guide-backdrop');
  void modal.ready.then((shown) => {
    if (!shown || closed) return;
    choices.forEach(({ button, description }, index) => {
      const rect = button.getBoundingClientRect();
      const placeholder = node('span', 'ui-setup-guide-placeholder');
      placeholder.setAttribute('aria-hidden', 'true');
      placeholder.style.width = `${Math.ceil(rect.width)}px`;
      placeholder.style.height = `${Math.ceil(rect.height)}px`;
      const describedBy = button.getAttribute('aria-describedby');
      const detail = node('p', 'ui-setup-guide-description', description);
      detail.id = `${modal.dialog.querySelector('h2').id}-choice-${index}`;
      button.setAttribute('aria-describedby', [describedBy, detail.id].filter(Boolean).join(' '));
      const choice = node('div', 'ui-setup-guide-choice');
      const choose = () => modal.close();
      originals.push({ button, placeholder, describedBy, choose });
      button.replaceWith(placeholder);
      button.addEventListener('click', choose, true);
      choice.append(button, detail);
      body.append(choice);
    });
    modal.focus(choices[0]?.button);
  });
  return {
    ready: modal.ready,
    isOpen: () => !closed && modal.state.backdrop.isConnected,
    close: () => { automaticClose = true; modal.close(); }
  };
}

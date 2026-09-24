let creatorSequence = 0;

function normalizedFamily(value) {
  return String(value || '').trim().toUpperCase();
}

function familyLabel(value) {
  const family = normalizedFamily(value);
  return family === 'NBFM' ? 'Conventional Analog (AM/NBFM)' : family;
}

export async function createAliasList(requestJson, { name, family, revision }) {
  const normalizedName = String(name || '').trim();
  const normalized = normalizedFamily(family);
  const result = await requestJson('/api/v1/admin/alias-lists', {
    method: 'POST', body: {
      revision: Number(revision ?? 0), name: normalizedName, family: normalized.toLowerCase()
    }
  });
  const id = Number(result?.alias_list_id);
  if (!Number.isInteger(id) || id <= 0) throw new Error('The new Alias List did not return a usable identifier.');
  return {
    ...result,
    aliasList: { id, alias_list_id: id, name: normalizedName, family: normalized }
  };
}

export function createInlineAliasListCreator(dependencies, options) {
  const { node, iconGlyph, uiPill, requestJson } = dependencies;
  const {
    select, family, getRevision, onCreated, triggerLabel = 'New list', submitLabel = 'Create and use',
    helperText = 'The list is created immediately and selected for this workflow.'
  } = options;
  const normalized = normalizedFamily(family);
  const host = node('div', 'ui-inline-create');
  const trigger = node('button', 'ui-button ui-button-secondary ui-inline-create-trigger');
  trigger.type = 'button';
  if (iconGlyph) trigger.append(iconGlyph('icon-plus'));
  trigger.append(node('span', '', triggerLabel));
  const panel = node('div', 'ui-inline-create-panel');
  panel.hidden = true;
  panel.id = `inline-alias-list-create-${++creatorSequence}`;
  trigger.setAttribute('aria-controls', panel.id);
  trigger.setAttribute('aria-expanded', 'false');

  const heading = node('div', 'ui-inline-create-heading');
  const headingText = node('h3', '', 'Create Alias List');
  headingText.id = `${panel.id}-heading`;
  heading.append(headingText);
  panel.setAttribute('role', 'group');
  panel.setAttribute('aria-labelledby', headingText.id);
  const name = node('input', 'ui-input');
  name.type = 'text';
  name.maxLength = 25;
  name.autocomplete = 'off';
  const nameField = node('label', 'ui-field');
  nameField.append(node('span', 'ui-field-label', 'Name'), name);
  const context = node('div', 'ui-inline-create-context');
  context.append(node('span', '', 'Protocol family'), uiPill(familyLabel(normalized), 'neutral'));
  const detail = node('small', 'ui-field-detail', helperText);
  const error = node('div', 'ui-inline-create-status');
  error.setAttribute('role', 'alert');
  const cancel = node('button', 'ui-button ui-button-secondary', 'Cancel');
  cancel.type = 'button';
  const submit = node('button', 'ui-button ui-button-primary', submitLabel);
  submit.type = 'button';
  const actions = node('div', 'ui-inline-create-actions');
  actions.append(cancel, submit);
  panel.append(heading, nameField, context, detail, error, actions);
  const status = node('div', 'ui-inline-create-status');
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');
  host.append(trigger, panel, status);

  const setOpen = (open) => {
    panel.hidden = !open;
    trigger.setAttribute('aria-expanded', String(open));
    if (open) name.focus();
    else trigger.focus();
  };
  const setBusy = (busy) => {
    trigger.disabled = busy;
    name.disabled = busy;
    cancel.disabled = busy;
    submit.disabled = busy;
  };
  trigger.addEventListener('click', () => {
    status.replaceChildren();
    error.replaceChildren();
    setOpen(true);
  });
  cancel.addEventListener('click', () => {
    name.value = '';
    error.replaceChildren();
    setOpen(false);
  });
  panel.addEventListener('input', (event) => event.stopPropagation());
  panel.addEventListener('change', (event) => event.stopPropagation());
  panel.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      name.value = '';
      error.replaceChildren();
      setOpen(false);
    } else if (event.target === name && event.key === 'Enter') {
      event.preventDefault();
      submit.click();
    }
  });
  submit.addEventListener('click', async () => {
    const listName = name.value.trim();
    error.replaceChildren();
    status.replaceChildren();
    if (!listName) {
      error.append(node('div', 'ui-notice ui-notice-danger', 'Enter a name for the Alias List.'));
      name.focus();
      return;
    }
    setBusy(true);
    let completed = false;
    let failed = false;
    try {
      const revision = await getRevision();
      const result = await createAliasList(requestJson, { name: listName, family: normalized, revision });
      await onCreated(result);
      name.value = '';
      panel.hidden = true;
      trigger.setAttribute('aria-expanded', 'false');
      status.append(node('div', 'ui-notice',
        `${result.aliasList.name} was created and selected. It remains available if you cancel this workflow.`));
      completed = true;
    } catch (cause) {
      error.append(node('div', 'ui-notice ui-notice-danger',
        cause?.message || 'The Alias List could not be created.'));
      failed = true;
    } finally {
      setBusy(false);
      if (completed) (select && !select.disabled ? select : trigger).focus();
      else if (failed) name.focus();
    }
  });
  return host;
}

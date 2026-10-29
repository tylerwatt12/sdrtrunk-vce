import { ALIAS_LIST_NAME_MAX_LENGTH, discoveryAliasListName } from './discovery-radioreference.js?v=19';
import { createActionWorkflow } from '../core/action-workflows.js?v=1';

let creatorSequence = 0;

function normalizedFamily(value) {
  return String(value || '').trim().toUpperCase();
}

function familyLabel(value) {
  const family = normalizedFamily(value);
  return family === 'NBFM' ? 'Conventional Analog (AM/NBFM)' : family;
}

export async function createAliasList(requestJson, { name, family }) {
  const normalizedName = String(name || '').trim();
  const normalized = normalizedFamily(family);
  const result = await requestJson('/api/v1/admin/alias-lists', {
    method: 'POST', body: {
      name: normalizedName, family: normalized.toLowerCase()
    }
  });
  const id = Number(result?.alias_list_id);
  if (!Number.isInteger(id) || id <= 0) throw new Error('The new Alias List did not return a usable identifier.');
  return {
    ...result,
    aliasList: { id, alias_list_id: id, name: normalizedName, family: normalized }
  };
}

export function createAliasListPopupTrigger(dependencies, options) {
  const { node, iconGlyph, uiPill, requestJson, openReadOnlyModal } = dependencies;
  const {
    select, family, onCreated, getInitialName, triggerLabel = 'New list', submitLabel = 'Create and use',
    helperText = 'The list is created immediately and selected for this workflow.'
  } = options;
  const normalized = normalizedFamily(family);
  const host = node('div', 'ui-alias-list-create');
  const trigger = node('button', 'ui-button ui-button-secondary ui-alias-list-create-trigger');
  trigger.type = 'button';
  if (iconGlyph) trigger.append(iconGlyph('icon-plus'));
  trigger.append(node('span', '', triggerLabel));
  trigger.setAttribute('aria-haspopup', 'dialog');
  trigger.setAttribute('aria-expanded', 'false');
  host.append(trigger);

  trigger.addEventListener('click', async () => {
    const form = node('form', 'admin-form');
    const name = node('input', 'ui-input');
    name.type = 'text';
    name.maxLength = ALIAS_LIST_NAME_MAX_LENGTH;
    name.autocomplete = 'off';
    name.value = discoveryAliasListName(getInitialName?.());
    const nameField = node('label', 'ui-field');
    nameField.append(node('span', 'ui-field-label', 'Name'), name);
    const context = node('div', 'ui-field-detail');
    context.append('Protocol family: ', uiPill(familyLabel(normalized), 'neutral'));
    const detail = node('div', 'ui-field-detail', helperText);
    const error = node('div');
    error.setAttribute('role', 'alert');
    const cancel = node('button', 'ui-button ui-button-secondary', 'Cancel');
    cancel.type = 'button';
    const submit = node('button', 'ui-button ui-button-primary', submitLabel);
    submit.type = 'submit';
    const actions = node('footer', 'ui-modal-footer ui-action-row');
    actions.append(cancel, submit);
    form.append(nameField, context, detail, error, actions);
    const modal = openReadOnlyModal('Create Alias List', form, {
      id: `create-alias-list-child-${++creatorSequence}`, className: 'modal-size-small alias-list-create-modal',
      stack: 'child', onClose: () => trigger.setAttribute('aria-expanded', 'false')
    });
    if (!modal) return;
    trigger.setAttribute('aria-expanded', 'true');
    cancel.addEventListener('click', () => modal.close());
    const workflow = createActionWorkflow({ surface: form, feedback: error, modal });
    let createdResult = null;
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      if (workflow.isBusy()) return;
      const listName = name.value.trim();
      if (!listName) {
        workflow.showFeedback('error', 'Enter a name for the Alias List.');
        modal.focus(name);
        return;
      }
      try {
        await workflow.run(async () => {
          if (!createdResult) {
            createdResult = await createAliasList(requestJson, { name: listName, family: normalized });
          }
          await onCreated(createdResult);
        }, { pending: createdResult ? 'Selecting Alias List…' : 'Creating Alias List…',
          onError: (cause) => {
            if (createdResult) workflow.showFeedback('error',
              `${createdResult.aliasList.name} was created, but could not be selected. ${cause?.message || 'Try again.'}`);
          } });
        modal.close();
        (select && !select.disabled ? select : trigger).focus({ preventScroll: true });
      } catch (_) {
        if (!modal.dialog.isConnected) return;
        name.disabled = Boolean(createdResult);
        if (createdResult) {
          submit.textContent = 'Use created list';
          modal.focus(submit);
        } else modal.focus(name);
      }
    });
    if (modal.ready && !await modal.ready) return;
    modal.focus(name);
  });
  return host;
}

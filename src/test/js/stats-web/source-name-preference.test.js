'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');

const applicationPath = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/app.js'));
const source = fs.readFileSync(applicationPath, 'utf8');

function functionSource(name) {
  const signature = `function ${name}(`;
  let start = source.indexOf(signature);
  assert.notEqual(start, -1, `Missing ${name}`);
  if (source.slice(start - 6, start) === 'async ') start -= 6;
  // Default object arguments precede the function body (for example options = {}).
  const opening = source.indexOf(') {', start) + 2;
  assert.ok(opening > start, `Missing body for ${name}`);
  let depth = 0;
  let quote = '';
  for (let index = opening; index < source.length; index += 1) {
    const character = source[index];
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
    } else if (['\'', '"', '`'].includes(character)) quote = character;
    else if (character === '{') depth += 1;
    else if (character === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`Unclosed ${name}`);
}

class Element {
  constructor(tag, className = '', text = '') {
    this.tagName = tag.toUpperCase();
    this.textContent = text;
    this.children = [];
    this.ownerDocument = { createElement: (tag) => new Element(tag) };
    this.listeners = new Map();
    this.attributes = new Map();
    this.disabled = false;
    this.hidden = false;
    this.value = '';
    const classes = new Set(className.split(/\s+/).filter(Boolean));
    this.classList = {
      add: (...values) => values.forEach((value) => classes.add(value)),
      remove: (...values) => values.forEach((value) => classes.delete(value)),
      contains: (value) => classes.has(value)
    };
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  addEventListener(name, listener) {
    if (!this.listeners.has(name)) this.listeners.set(name, []);
    this.listeners.get(name).push(listener);
  }
  async dispatch(name) {
    await Promise.all((this.listeners.get(name) || []).map((listener) =>
      listener({ preventDefault() {} })));
  }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
  reportValidity() { return true; }
  get elements() {
    const controls = [];
    const visit = (element) => {
      if (['INPUT', 'SELECT', 'BUTTON', 'TEXTAREA'].includes(element.tagName)) controls.push(element);
      element.children?.forEach(visit);
    };
    this.children.forEach(visit);
    return controls;
  }
}

async function main() {
  const { formatSourceName } = await import(pathToFileURL(path.join(path.dirname(applicationPath),
    'core/source-names.js')).href);
  const { p25ServingSystemKey } = await import(pathToFileURL(path.join(path.dirname(applicationPath),
    'core/radio-labels.js')).href);
  const { createFormWorkflow } = await import(pathToFileURL(path.join(path.dirname(applicationPath),
    'core/form-workflows.js')).href);
  let mode = 'talker_alias';
  const context = {
    activeUserPreferences: () => ({ presentation: { source_name_display: mode } }),
    formatSourceName,
    p25ServingSystemKey,
    scannerDetailMode: 'normal',
    scannerMatchedScanLists: () => '',
    liveIdentityActionLink: (row, kind, text) => ({ row, kind, text })
  };
  vm.createContext(context);
  vm.runInContext(['sourceNameDisplayMode', 'liveAliasReferences', 'liveSourceName',
    'liveAliasValue', 'liveIdentityRenderKey', 'scannerSourceAlias', 'scannerCallRenderKey']
    .map(functionSource).join('\n'), context);

  const call = { source_alias: 'Engine 4', talker_alias: 'Portable 4', source_id: '44', call_id: 'call-1' };
  for (const [choice, expected] of [['talker_alias', 'Portable 4'], ['source_alias', 'Engine 4'],
    ['both', 'Engine 4 · Portable 4']]) {
    mode = choice;
    assert.equal(context.sourceNameDisplayMode(), choice);
    assert.equal(context.liveSourceName(call), expected, `Live respects ${choice}`);
    assert.equal(context.scannerSourceAlias(call), expected, `Scanner respects ${choice}`);
    const linked = context.liveAliasValue(call, 'source');
    assert.equal(linked.text, expected);
    assert.equal(linked.kind, 'source');
    assert.equal(linked.row, call, 'The chosen name keeps the original source drilldown identity');
    assert.equal(context.liveSourceName({ source_alias: ' Engine 4 ', talker_alias: ' ' }), 'Engine 4');
    assert.equal(context.scannerSourceAlias({ source_alias: ' ', talker_alias: ' Portable 4 ' }), 'Portable 4');
    assert.equal(context.liveSourceName({}), '');
    assert.equal(context.scannerSourceAlias(null), '');
  }
  mode = 'both';
  assert.equal(context.scannerSourceAlias({ source_alias: 'Engine 4', talker_alias: ' engine 4 ' }), 'Engine 4');
  const multipleAliases = { ...call, source_aliases: [
    { alias_id: 1, alias_list_id: 2, name: 'Engine 4' },
    { alias_id: 2, alias_list_id: 2, name: 'Command' },
    { alias_id: 3, alias_list_id: 2, name: 'engine 4' }
  ] };
  assert.equal(context.liveSourceName(multipleAliases), 'Engine 4, Command · Portable 4');
  assert.equal(context.liveSourceName({ ...multipleAliases, talker_alias: 'COMMAND' }), 'Engine 4, Command');
  mode = 'source_alias';
  assert.equal(context.liveSourceName(multipleAliases), 'Engine 4, Command');
  mode = 'talker_alias';
  assert.equal(context.liveSourceName(multipleAliases), 'Portable 4');
  assert.equal(context.liveSourceName({ ...call, source_aliases: [{ alias_id: 0, alias_list_id: 2,
    name: 'Invalid reference' }], talker_alias: '' }), 'Engine 4');

  const sourceKey = context.liveIdentityRenderKey(call, 'source');
  const targetKey = context.liveIdentityRenderKey(call, 'target');
  const scannerKey = context.scannerCallRenderKey(call, {}, null);
  mode = 'source_alias';
  assert.notEqual(context.liveIdentityRenderKey(call, 'source'), sourceKey,
    'A source-name preference change invalidates unchanged Live cell content');
  assert.equal(context.liveIdentityRenderKey(call, 'target'), targetKey,
    'A source-name change must not invalidate unrelated target cells');
  assert.notEqual(context.scannerCallRenderKey(call, {}, null), scannerKey,
    'An unchanged current Scanner call redraws when its name preference changes');
  assert.notEqual(context.scannerCallRenderKey(call, {}, null),
    context.scannerCallRenderKey({ ...call, source_alias: 'Engine 5' }, {}, null),
    'Late alias metadata must invalidate the Scanner call display');
  const column = functionSource('liveChannelsSection').match(/\{ id: 'source', label: 'Source',[\s\S]*?reconcileKey:/)?.[0];
  assert.ok(column, 'The Live Source column exists');
  assert.match(column, /sortValue:.*liveIdentitySortValue\(row, 'source'\)/,
    'The combined Source column sorts by its displayed name with an identifier fallback');
  assert.match(column, /render:.*liveIdentityValue\(row, 'source'\)/,
    'The Source column renders the name and exact identifier together');
  const rows = [{ source_alias: 'Zulu', talker_alias: 'Alpha' },
    { source_alias: 'Alpha', talker_alias: 'Zulu' }];
  assert.equal([...rows].sort((a, b) => context.liveSourceName(a).localeCompare(context.liveSourceName(b)))[0], rows[1]);
  mode = 'talker_alias';
  assert.equal([...rows].sort((a, b) => context.liveSourceName(a).localeCompare(context.liveSourceName(b)))[0], rows[0]);

  // Live, Scanner and Receiver Health modal editors share save/reload/session
  // decisions while retaining their own payloads and refresh policy.
  for (const outcome of ['saved', 'density-only', 'failed', 'conflict', 'reload-failed', 'new-account', 'session-changed']) {
    const form = new Element('form');
    const input = new Element('input');
    input.value = 'draft';
    const submit = new Element('button');
    const feedback = new Element('div');
    form.append(input, submit, feedback);
    let writes = 0, renders = 0;
    const modal = { dirty: false, busy: false, closed: false,
      setDirty(value) { this.dirty = value; }, setBusy(value) { this.busy = value; },
      close() { this.closed = !this.dirty && !this.busy; return this.closed; } };
    const workflow = createFormWorkflow({ form, submit, feedback, modal });
    await form.dispatch('input');
    const scope = {
      userPreferenceController: { snapshot: () => ({ loaded: true,
        identity: outcome === 'new-account' ? 'second' : 'first', preferences: { value: 'saved' } }) },
      updateUserPreferences: async (mutate, retry) => {
        writes += 1;
        assert.equal(retry, false, 'Editor conflicts must not overwrite the saved document automatically');
        if (outcome === 'failed') throw new Error('Offline');
        if (outcome === 'session-changed') throw Object.assign(new Error('Changed account'),
          { code: 'preference_session_changed' });
        if (['conflict', 'reload-failed'].includes(outcome)) throw Object.assign(new Error('Conflict'),
          { code: 'preference_conflict', reloadError: outcome === 'reload-failed' ? new Error('Offline') : null });
        const preferences = {};
        mutate(preferences);
        assert.equal(preferences.value, 'draft');
      },
      render: () => { renders += 1; }
    };
    vm.createContext(scope);
    vm.runInContext(functionSource('savePreferenceForm'), scope);
    await scope.savePreferenceForm(workflow, modal, preferences => { preferences.value = input.value; },
      { identity: 'first', saving: 'Saving…', apply: preferences => { input.value = preferences.value; },
        renderAfterSave: outcome !== 'density-only' });
    assert.equal(workflow.isBusy(), false, outcome);
    assert.equal(input.disabled, false, outcome);
    assert.equal(writes, outcome === 'new-account' ? 0 : 1, outcome);
    const closed = ['saved', 'density-only', 'new-account', 'session-changed'].includes(outcome);
    assert.equal(modal.closed, closed, outcome);
    assert.equal(renders, closed && outcome !== 'density-only' ? 1 : 0, outcome);
    assert.equal(input.value, outcome === 'conflict' ? 'saved' : 'draft', outcome);
    assert.equal(modal.dirty, ['failed', 'reload-failed'].includes(outcome), outcome);
    assert.equal(submit.disabled, ['saved', 'density-only', 'new-account', 'session-changed'].includes(outcome), outcome);
    if (['saved', 'density-only'].includes(outcome)) {
      assert.equal(submit.hidden, true, outcome);
      assert.equal(feedback.textContent, '', outcome);
      assert.equal(feedback.hidden, true, outcome);
    }
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });

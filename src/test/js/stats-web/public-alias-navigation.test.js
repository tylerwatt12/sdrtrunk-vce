'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../../..');
const source = fs.readFileSync(path.join(root, 'stats-web/assets/app.js'), 'utf8');
const routesSource = fs.readFileSync(path.join(root, 'stats-web/assets/core/routes.js'), 'utf8');
const routes = vm.runInNewContext(routesSource.replace(/^export .*;$/m, '') + '\n({definitions, requestedView});',
  { URLSearchParams });

function declaration(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `Missing ${name}`);
  let quote = '', depth = 0;
  for (let index = source.indexOf('{', start); index < source.length; index++) {
    const char = source[index];
    if (quote) {
      if (char === '\\') index++;
      else if (char === quote) quote = '';
    } else if (['\'', '"', '`'].includes(char)) quote = char;
    else if (char === '{') depth++;
    else if (char === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`Unclosed ${name}`);
}

class Element {
  constructor(tag, className = '', textContent = '') {
    Object.assign(this, { tag, className, textContent, children: [], listeners: {} });
  }
  append(...values) { this.children.push(...values); }
  setAttribute(name, value) { this[name] = value; }
  addEventListener(name, handler) { this.listeners[name] = handler; }
}

const route = new URLSearchParams('view=dashboard');
let admin = false, modal;
const node = (...args) => new Element(...args);
const context = {
  route, routeFoundation: routes, applicationRoutes: Object.fromEntries(routes.definitions.map(value => [value.id, value])),
  aliasAdminAllowed: () => admin,
  node, anchor: (label, href, className) => Object.assign(node('a', className, label), { href }),
  href: (view, parameters) => `/?${new URLSearchParams({ view, ...parameters })}`,
  document: { createTextNode: text => node('text', '', text) }, liveIdentityActionSequence: 0,
  liveIdentityInfo: () => ({ target: '/?view=radio', title: 'Open radio info', description: 'View radio activity.' }),
  liveIdentityFacts: () => node('dl', '', 'Permanent identity and working assignment'),
  liveIdentityActionTitle: (_row, _kind, label) => `Radio ${label}`,
  radioIdentifierText: (_row, value) => String(value),
  openReadOnlyModal: (title, body) => { modal = { title, body }; }
};
const behavior = vm.runInNewContext(['aliasListLink', 'liveAliasReferences', 'liveIdentityActionLink',
  'liveAliasValue', 'liveIdentifierAliasValue'].map(declaration).join('\n') +
  '\n({aliasListLink,liveAliasValue,liveIdentifierAliasValue});', context);
const elements = value => value instanceof Element ? [value, ...value.children.flatMap(elements)] : [];

for (admin of [false, true]) {
  for (const view of routes.definitions.filter(value => !value.access).map(value => value.id)) {
    route.set('view', view);
    assert.equal(behavior.aliasListLink('County Public Safety', 41), 'County Public Safety',
      `Public ${view} must not become an Alias editor link for an administrator.`);
  }
  for (const view of ['aliases', 'channel-setup', 'scan-lists', 'admin']) {
    route.set('view', view);
    const value = behavior.aliasListLink('County Public Safety', 41);
    assert.equal(value instanceof Element, admin, 'Administration preserves permitted Alias List links.');
    if (admin) assert.match(value.href, /view=aliases/);
  }
  for (const kind of ['source', 'target']) {
    const row = { alias_list_id: 41, [`${kind}_id`]: 501, [`${kind}_form`]: 'RADIO',
      [`${kind}_alias`]: 'Engine 4', [`${kind}_aliases`]: [{ alias_id: 7, alias_list_id: 41, name: 'Engine 4' }] };
    const presentations = [behavior.liveAliasValue(row, kind), behavior.liveIdentifierAliasValue(row, kind),
      behavior.liveAliasValue({ ...row, [`${kind}_aliases`]: [] }, kind)];
    for (const presentation of presentations) {
      const trigger = elements(presentation).find(value => value.listeners.click);
      assert.ok(trigger, 'Live keeps its read-only identity dialog and radio drilldown.');
      trigger.listeners.click({ button: 0, preventDefault() {} });
      assert.ok(elements(modal.body).some(value => value.tag === 'dl'));
      const links = elements(modal.body).filter(value => value.tag === 'a');
      assert.deepEqual(links.map(value => value.href), ['/?view=radio']);
      assert.equal(elements(modal.body).some(value => /Create alias|Edit alias|Manage aliases/i.test(value.textContent)), false,
        'Public Live must not construct Alias editing controls, even for a logged-in administrator.');
    }
  }
}
assert.doesNotMatch(declaration('renderAliasCoverageDirectory'), /Manage aliases|alias-coverage-manage/);
assert.match(declaration('routedAliasPrefill'), /type === 'p25_subscriber_identity'/,
  'Configure retains permanent P25 subscriber matcher editing.');
console.log('Public Alias navigation checks passed');

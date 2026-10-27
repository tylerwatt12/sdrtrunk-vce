'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../../..');
const source = fs.readFileSync(path.join(root, 'stats-web/assets/app.js'), 'utf8');
const routesSource = fs.readFileSync(path.join(root, 'stats-web/assets/core/routes.js'), 'utf8');
const sourceNamesSource = fs.readFileSync(path.join(root, 'stats-web/assets/core/source-names.js'), 'utf8');
const { formatSourceName } = vm.runInNewContext(sourceNamesSource.replace(/^export /gm, '') + '\n({formatSourceName});');
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
    Object.assign(this, { tag, className, textContent, children: [], listeners: {}, dataset: {} });
  }
  append(...values) { this.children.push(...values); }
  setAttribute(name, value) { this[name] = value; }
  addEventListener(name, handler) { this.listeners[name] = handler; }
}

const route = new URLSearchParams('view=dashboard');
let admin = false;
const node = (...args) => new Element(...args);
const context = {
  formatSourceName, sourceNameDisplayMode: () => 'talker_alias',
  route, routeFoundation: routes, applicationRoutes: Object.fromEntries(routes.definitions.map(value => [value.id, value])),
  aliasAdminAllowed: () => admin,
  node, anchor: (label, href, className) => Object.assign(node('a', className, label), { href }),
  href: (view, parameters) => `/?${new URLSearchParams({ view, ...parameters })}`,
  document: { createTextNode: text => node('text', '', text) },
  liveIdentityInfo: row => row.unresolved ? null : ({ target: '/?view=radio' }),
  radioIdentifierText: (_row, value) => String(value),
  partyIdentifierText: (_row, value) => String(value)
};
const behavior = vm.runInNewContext(['aliasListLink', 'liveAliasReferences', 'liveIdentityActionLink',
  'liveSourceName', 'liveAliasValue', 'liveIdentifierAliasValue'].map(declaration).join('\n') +
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
      const links = elements(presentation).filter(value => value.tag === 'a');
      assert.equal(links.length, 1, 'Live goes directly to the associated entity.');
      assert.equal(links[0].href, '/?view=radio');
      assert.equal(links[0].listeners.click, undefined, 'Entity navigation has no action popup.');
      assert.equal(links[0]['aria-haspopup'], undefined);
      assert.equal(elements(presentation).some(value => /Create alias|Edit alias|Manage aliases/i.test(value.textContent)), false,
        'Public Live must not construct Alias editing controls, even for a logged-in administrator.');
    }
  }
}
assert.equal(behavior.liveIdentifierAliasValue({ source_id: 501, source_form: 'RADIO', unresolved: true }, 'source'),
  '501', 'Unresolved identities remain plain text without a fake destination.');
assert.doesNotMatch(declaration('renderAliasCoverageDirectory'), /Manage aliases|alias-coverage-manage/);
assert.match(declaration('routedAliasPrefill'), /type === 'p25_subscriber_identity'/,
  'Configure retains permanent P25 subscriber matcher editing.');
console.log('Public Alias navigation checks passed');

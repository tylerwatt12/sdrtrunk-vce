'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const core = path.resolve(process.argv[2] || 'stats-web/assets/core');
const assets = path.dirname(core);
const web = path.dirname(assets);
const modules = Promise.all(['preference-registry', 'preference-schema', 'browser-preferences']
  .map(name => import(pathToFileURL(path.join(core, `${name}.js`)).href)));

function fields(value, prefix = '') {
  if (value === null || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length === 0) return [prefix];
  return Object.entries(value).flatMap(([key, child]) => key === 'version' && !prefix ? [] : fields(child, prefix ? `${prefix}.${key}` : key));
}

function files(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const filename = path.join(directory, entry.name);
    return entry.isDirectory() ? files(filename) : /\.(?:js|html)$/.test(entry.name) ? [filename] : [];
  });
}

function storageApis(source) {
  const apis = new Set();
  for (const name of ['localStorage', 'sessionStorage', 'indexedDB']) {
    if (new RegExp(`\\b${name}\\s*(?:\\?\\.|\\.|\\[)|` +
      `\\b(?:window|self|globalThis)\\s*(?:(?:\\?\\.|\\.)\\s*${name}\\b|\\[\\s*['\"]${name}['\"]\\s*\\])`).test(source)) apis.add(name);
  }
  if (/document\.cookie\s*=/.test(source)) apis.add('cookie');
  return apis;
}

function violations(filename, source, records) {
  const allowed = records.find(record => record.file === filename);
  const unexpected = [...storageApis(source)].filter(api => !allowed?.apis.includes(api));
  // Storage wrappers do not need to name the global again at their write sites.
  // A new setItem/removeItem wrapper must still declare its non-preference owner.
  if (/\b(?:setItem|removeItem)\s*\(/.test(source) &&
      !allowed?.apis.some(api => api === 'localStorage' || api === 'sessionStorage')) unexpected.push('storage-writer');
  if (source.includes('/api/v1/me/preferences') && filename !== 'core/user-preferences.js') {
    unexpected.push('account-preferences');
  }
  return unexpected.map(api => `${filename}: unregistered ${api}`);
}

test('every typed personal preference has one ownership, guest, group and reset declaration', async () => {
  const [registry, schema] = await modules;
  const actual = registry.preferenceRegistry.map(record => record.path);
  assert.equal(new Set(actual).size, actual.length, 'A preference has exactly one owner');
  assert.deepEqual(actual.slice().sort(), fields(schema.defaults).sort());
  const groups = new Set(registry.preferenceGroups.map(group => group.id));
  const interfaceSource = fs.readFileSync(path.join(web, 'index.html'), 'utf8');
  for (const group of registry.preferenceGroups) {
    assert.ok(interfaceSource.includes(`id="${group.icon}"`), `${group.id} uses an existing icon`);
  }
  for (const record of registry.preferenceRegistry) {
    assert.equal(record.owner, 'personal');
    assert.equal(record.lifetime, 'profile');
    assert.equal(typeof record.guest, 'boolean');
    assert.equal(typeof record.reset, 'boolean');
    assert.ok(record.capability === null || typeof record.capability === 'string');
    assert.ok(groups.has(record.group), `${record.path} has a known group`);
  }
  assert.equal(registry.guestDefaults(schema.defaults).guides.automatic_page_guides, false);
  assert.equal(schema.defaults.guides.automatic_page_guides, true, 'Guest defaults must not mutate account defaults');
});

test('personal browser storage has one adapter and other stores have explicit separate owners', async () => {
  const [registry] = await modules;
  const personal = registry.browserStateRegistry.filter(record => record.owner === 'personal');
  assert.deepEqual(personal.map(record => record.file), ['core/browser-preferences.js']);
  const errors = [];
  for (const filename of files(web)) {
    if (filename.includes(`${path.sep}vendor${path.sep}`)) continue;
    const relative = path.relative(assets, filename).split(path.sep).join('/');
    errors.push(...violations(relative, fs.readFileSync(filename, 'utf8'), registry.browserStateRegistry));
  }
  assert.deepEqual(errors, [], errors.join('\n'));
  assert.deepEqual(violations('features/new-display.js', 'localStorage.setItem("choice", "1");', registry.browserStateRegistry),
    ['features/new-display.js: unregistered localStorage', 'features/new-display.js: unregistered storage-writer']);
  assert.deepEqual(violations('app.js', 'localStorage.setItem("choice", "1");', registry.browserStateRegistry),
    ['app.js: unregistered localStorage', 'app.js: unregistered storage-writer'],
    'A download-cookie exception must not permit personal localStorage in the application');
  assert.deepEqual(violations('features/new-display.js', 'fetch("/api/v1/me/preferences");', registry.browserStateRegistry),
    ['features/new-display.js: unregistered account-preferences'],
    'Feature editors must use the controller for account changes too');
});

test('browser schema changes require an explicit adjacent guest migration contract', async () => {
  const [, schema, browser] = await modules;
  assert.equal(browser.CURRENT_GUEST_PREFERENCE_VERSION, schema.defaults.version,
    'A new preference schema must update browser compatibility deliberately');
  for (let version = 14; version < schema.defaults.version; version++) {
    const steps = browser.GUEST_PREFERENCE_MIGRATIONS.filter(step => step.from === version);
    assert.equal(steps.length, 1, `Guest version ${version} has exactly one migration`);
    assert.equal(steps[0].to, version + 1);
    assert.equal(typeof steps[0].convert, 'function', 'The registry owns each executable conversion');
    assert.ok(Object.isFrozen(steps[0]));
  }
});

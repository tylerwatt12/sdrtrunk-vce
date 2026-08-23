'use strict';

const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const applicationPath = process.argv[2];
if (!applicationPath) throw new Error('app.js path is required');
const application = fs.readFileSync(applicationPath, 'utf8');

function functionSource(signature) {
  const start = application.indexOf(signature);
  if (start < 0) throw new Error(`Missing ${signature}`);
  const openingBrace = application.indexOf('{', start + signature.length);
  let depth = 0;
  for (let index = openingBrace; index < application.length; index += 1) {
    if (application[index] === '{') depth += 1;
    else if (application[index] === '}' && --depth === 0) return application.slice(start, index + 1);
  }
  throw new Error(`Unterminated ${signature}`);
}

class RuntimeNode {
  constructor(text = '') {
    this.children = [];
    this.textContent = String(text);
  }

  append(...values) {
    values.forEach((value) => {
      const child = value instanceof RuntimeNode ? value : new RuntimeNode(value);
      this.children.push(child);
      this.textContent += child.textContent;
    });
  }
}

const context = {
  Intl,
  Node: RuntimeNode,
  Number,
  document: { createTextNode: (value) => new RuntimeNode(value) },
  node: (tag, className = '', text = null) => {
    const result = new RuntimeNode();
    result.tag = tag;
    result.className = className;
    if (text !== null && text !== undefined) result.append(text);
    return result;
  }
};
vm.createContext(context);
vm.runInContext([
  functionSource('function number(value)'),
  functionSource('function valueNode(value)'),
  functionSource('function metrics(values, embedded = false)'),
  functionSource('function adminStatusNumber(value)'),
  functionSource('function adminStatusBytes(value)'),
  functionSource('function adminDatabaseDisplay(database)')
].join('\n'), context);

assert.strictEqual(context.number('On'), '—');
assert.strictEqual(context.number(Number.NaN), '—');
assert.strictEqual(context.number(null), '0');
assert.strictEqual(context.number(1234), '1,234');

for (const missing of [undefined, null, '', '4096', false, Number.NaN, -1]) {
  assert.strictEqual(context.adminStatusNumber(missing), '—');
  assert.strictEqual(context.adminStatusBytes(missing), '—');
}
assert.strictEqual(context.adminStatusNumber(0), '0');
assert.strictEqual(context.adminStatusBytes(0), '0 B');
assert.strictEqual(context.adminStatusBytes(1024), '1 KB');
assert.strictEqual(context.adminStatusBytes(1048576), '1.0 MB');
assert.strictEqual(context.adminDatabaseDisplay(undefined), 'Unknown');
assert.strictEqual(context.adminDatabaseDisplay({}), 'Unknown');
assert.strictEqual(context.adminDatabaseDisplay({ database_exists: false, database_bytes: 1024 }), 'Missing');
assert.strictEqual(context.adminDatabaseDisplay({ database_exists: true }), 'Present');
assert.strictEqual(context.adminDatabaseDisplay({ database_exists: true, database_bytes: '1024' }), 'Present');
assert.strictEqual(context.adminDatabaseDisplay({ database_exists: true, database_bytes: 1048576 }), '1.0 MB');

const status = context.metrics([
  ['Summary logging', true, 'Running'],
  ['Detailed history', false, 'Configured · Inactive'],
  ['Activity database', 1048576, '1.0 MB']
], true);
assert.deepStrictEqual(status.children.map((metric) => metric.children[1].textContent),
  ['Running', 'Configured · Inactive', '1.0 MB']);

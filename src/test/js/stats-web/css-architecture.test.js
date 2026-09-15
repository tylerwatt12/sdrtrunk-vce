'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const entryStylesheet = path.resolve(process.argv[2]
  || path.resolve(__dirname, '../../../../stats-web/assets/app.css'));

const EXPECTED_ENTRY_MANIFEST = [
  '@layer reset, tokens, legacy, components, compositions, features, utilities;',
  '@import url("./styles/base.css") layer(reset);',
  '@import url("./styles/tokens.css") layer(tokens);',
  '@import url("./styles/legacy.css") layer(legacy);',
  '@import url("./styles/components/controls.css") layer(components);',
  '@import url("./styles/compositions/workspaces.css") layer(compositions);',
  '@import url("./styles/features/channels.css") layer(features);',
  '@import url("./styles/features/radio-directory.css") layer(features);',
  '@import url("./styles/features/administration.css") layer(features);',
  '@import url("./styles/utilities/reduced-motion.css") layer(utilities);',
];

// Existing global element selectors are frozen debt. New controls and tables must be
// scoped beneath a page, component, or explicit density/composition boundary.
const LEGACY_UNSCOPED_SELECTOR_BUDGET = new Map([
  ['button', 1],
  ['button:disabled', 1],
  ['button.danger', 1],
  ['button.secondary.danger-outline', 1],
  ['button.secondary', 1],
  ['table', 1],
  ['button.receiver-health-section-toggle', 1],
  ['button.receiver-health-section-toggle::before', 1],
  ['button.receiver-health-section-toggle[aria-expanded="true"]::before', 1],
  ['button.receiver-health-section-toggle:focus-visible', 1],
  ['th', 2],
  ['td', 1],
  ['th:last-child', 1],
  ['td:last-child', 1],
  ['th .table-sort-control', 1],
  ['th .table-column-label', 1],
  ['th .table-sort-control:hover', 1],
  ['th[aria-sort="ascending"] .table-sort-control::after', 2],
  ['th[aria-sort="descending"] .table-sort-control::after', 2],
  ['td.numeric', 1],
  ['th.numeric', 1],
]);

function locator(source) {
  const lineStarts = [0];
  for (let index = 0; index < source.length; index += 1) {
    if (source[index] === '\n') lineStarts.push(index + 1);
  }

  return (index) => {
    let low = 0;
    let high = lineStarts.length - 1;
    while (low <= high) {
      const middle = Math.floor((low + high) / 2);
      if (lineStarts[middle] <= index) low = middle + 1;
      else high = middle - 1;
    }
    return { line: high + 1, column: index - lineStarts[high] + 1 };
  };
}

function failure(label, locate, index, message) {
  const { line, column } = locate(index);
  return new Error(`${label}:${line}:${column}: ${message}`);
}

function maskComments(source, label, locate) {
  const masked = [...source];
  let quote = '';
  let quoteStart = -1;
  let commentStart = -1;

  for (let index = 0; index < source.length; index += 1) {
    const character = source[index];
    const next = source[index + 1];

    if (commentStart >= 0) {
      if (character === '*' && next === '/') {
        masked[index] = ' ';
        masked[index + 1] = ' ';
        commentStart = -1;
        index += 1;
      } else if (character !== '\n' && character !== '\r') {
        masked[index] = ' ';
      }
      continue;
    }

    if (quote) {
      if (character === '\\') {
        index += 1;
      } else if (character === quote) {
        quote = '';
        quoteStart = -1;
      } else if (character === '\n' || character === '\r') {
        throw failure(label, locate, quoteStart, 'unterminated string');
      }
      continue;
    }

    if (character === '/' && next === '*') {
      masked[index] = ' ';
      masked[index + 1] = ' ';
      commentStart = index;
      index += 1;
    } else if (character === '"' || character === "'") {
      quote = character;
      quoteStart = index;
    }
  }

  if (commentStart >= 0) throw failure(label, locate, commentStart, 'unterminated comment');
  if (quote) throw failure(label, locate, quoteStart, 'unterminated string');
  return masked.join('');
}

function importSpecifier(statement, label, locate, index) {
  const urlMatch = statement.match(
    /^@import\s+url\(\s*(?:"([^"]+)"|'([^']+)'|([^'"\s)]+))\s*\)/i,
  );
  const stringMatch = statement.match(/^@import\s+(?:"([^"]+)"|'([^']+)')/i);
  const match = urlMatch || stringMatch;
  if (!match) throw failure(label, locate, index, 'malformed @import rule');
  return match.slice(1).find((value) => value !== undefined);
}

function parseStylesheet(source, label) {
  const locate = locator(source);
  const masked = maskComments(source, label, locate);
  const blocks = [];
  const delimiters = [];
  const rules = [];
  const imports = [];
  const matchingDelimiter = { ')': '(', ']': '[' };
  let quote = '';
  let segmentStart = 0;

  for (let index = 0; index < masked.length; index += 1) {
    const character = masked[index];

    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
      continue;
    }
    if (character === '"' || character === "'") {
      quote = character;
      continue;
    }
    if (character === '(' || character === '[') {
      delimiters.push({ character, index });
      continue;
    }
    if (character === ')' || character === ']') {
      const opening = delimiters.pop();
      if (!opening || opening.character !== matchingDelimiter[character]) {
        throw failure(label, locate, index, `unmatched closing ${character}`);
      }
      continue;
    }
    if (character === '{') {
      if (delimiters.length) {
        throw failure(label, locate, index, `block opened before closing ${delimiters.at(-1).character}`);
      }
      const rawHeader = masked.slice(segmentStart, index);
      const leading = rawHeader.search(/\S/);
      if (leading < 0) throw failure(label, locate, index, 'block has no selector or at-rule');
      const header = rawHeader.slice(leading).trim();
      const headerIndex = segmentStart + leading;
      const atRule = header.startsWith('@');
      if (/^@import\b/i.test(header)) {
        throw failure(label, locate, headerIndex, '@import must end with a semicolon');
      }
      const insideKeyframes = blocks.some((block) => block.keyframes);
      const rule = !atRule && !insideKeyframes ? {
        header,
        index: headerIndex,
        body: '',
        atRules: blocks.filter((block) => block.header.startsWith('@'))
          .map((block) => block.header),
      } : null;
      if (rule) rules.push(rule);
      blocks.push({
        header,
        index: headerIndex,
        keyframes: /^@(?:-webkit-)?keyframes\b/i.test(header),
        rule,
        bodyStart: index + 1,
      });
      segmentStart = index + 1;
      continue;
    }
    if (character === '}') {
      if (delimiters.length) {
        throw failure(label, locate, index, `block closed before ${delimiters.at(-1).character}`);
      }
      if (!blocks.length) throw failure(label, locate, index, 'unmatched closing brace');
      const block = blocks.pop();
      if (block.rule) block.rule.body = masked.slice(block.bodyStart, index);
      segmentStart = index + 1;
      continue;
    }
    if (character === ';' && delimiters.length === 0) {
      if (blocks.length === 0) {
        const rawStatement = masked.slice(segmentStart, index);
        const leading = rawStatement.search(/\S/);
        if (leading >= 0) {
          const statement = rawStatement.slice(leading).trim();
          if (/^@import\b/i.test(statement)) {
            const statementIndex = segmentStart + leading;
            imports.push({
              specifier: importSpecifier(statement, label, locate, statementIndex),
              index: statementIndex,
            });
          }
        }
      }
      segmentStart = index + 1;
    }
  }

  if (delimiters.length) {
    const opening = delimiters.at(-1);
    throw failure(label, locate, opening.index, `unclosed ${opening.character}`);
  }
  if (blocks.length) {
    const opening = blocks.at(-1);
    throw failure(label, locate, opening.index, `unclosed block for ${opening.header}`);
  }

  return { imports, locate, rules };
}

function splitSelectorList(selectorList) {
  const selectors = [];
  const delimiters = [];
  const matchingDelimiter = { ')': '(', ']': '[' };
  let quote = '';
  let start = 0;

  for (let index = 0; index < selectorList.length; index += 1) {
    const character = selectorList[index];
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
      continue;
    }
    if (character === '"' || character === "'") quote = character;
    else if (character === '(' || character === '[') delimiters.push(character);
    else if (character === ')' || character === ']') {
      if (delimiters.at(-1) === matchingDelimiter[character]) delimiters.pop();
    } else if (character === ',' && delimiters.length === 0) {
      selectors.push(selectorList.slice(start, index).replace(/\s+/g, ' ').trim());
      start = index + 1;
    }
  }
  selectors.push(selectorList.slice(start).replace(/\s+/g, ' ').trim());
  return selectors.filter(Boolean);
}

function isUnscopedBareSelector(selector) {
  const target = /\b(?:button|select|table|th|td)\b/gi;
  let match;

  while((match = target.exec(selector)) !== null) {
    let parentheses = 0;
    let brackets = 0;
    let quote = '';
    let scoped = false;

    for(let index = 0; index < match.index; index += 1) {
      const character = selector[index];
      if(quote) {
        if(character === '\\') index += 1;
        else if(character === quote) quote = '';
        continue;
      }
      if(character === '"' || character === "'") quote = character;
      else if(character === '(') parentheses += 1;
      else if(character === ')') parentheses = Math.max(0, parentheses - 1);
      else if(character === '[') {
        if(parentheses === 0 && brackets === 0) scoped = true;
        brackets += 1;
      } else if(character === ']') brackets = Math.max(0, brackets - 1);
      else if(parentheses === 0 && brackets === 0 && (character === '.' || character === '#')) {
        scoped = true;
      }
    }

    if(!scoped) return true;
  }

  return false;
}

function isRemoteImport(specifier) {
  return /^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(specifier);
}

function isWithin(directory, candidate) {
  const relative = path.relative(directory, candidate);
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}

function readStylesheetGraph(entry) {
  const resolvedEntry = path.resolve(entry);
  const stylesDirectory = path.resolve(path.dirname(resolvedEntry), 'styles');
  const visiting = [];
  const visited = new Set();
  const stylesheets = [];

  function visit(file) {
    const resolvedFile = path.resolve(file);
    if (visiting.includes(resolvedFile)) {
      const cycle = [...visiting.slice(visiting.indexOf(resolvedFile)), resolvedFile]
        .map((item) => path.relative(path.dirname(resolvedEntry), item) || path.basename(item));
      throw new Error(`Circular stylesheet import: ${cycle.join(' -> ')}`);
    }
    if (visited.has(resolvedFile)) return;
    if (!fs.existsSync(resolvedFile) || !fs.statSync(resolvedFile).isFile()) {
      throw new Error(`Missing stylesheet: ${resolvedFile}`);
    }

    const source = fs.readFileSync(resolvedFile, 'utf8').replace(/\r\n?/g, '\n');
    const parsed = parseStylesheet(source, resolvedFile);
    const stylesheet = { file: resolvedFile, source, ...parsed };
    visiting.push(resolvedFile);

    for (const imported of parsed.imports) {
      if (isRemoteImport(imported.specifier)) {
        const { line, column } = parsed.locate(imported.index);
        throw new Error(`${resolvedFile}:${line}:${column}: remote stylesheet imports are not allowed`);
      }
      const importPath = imported.specifier.split(/[?#]/, 1)[0];
      const resolvedImport = path.resolve(path.dirname(resolvedFile), importPath);
      if (!isWithin(stylesDirectory, resolvedImport)) {
        const { line, column } = parsed.locate(imported.index);
        throw new Error(
          `${resolvedFile}:${line}:${column}: local @import must stay under ${stylesDirectory}`,
        );
      }
      if (path.extname(resolvedImport).toLowerCase() !== '.css') {
        const { line, column } = parsed.locate(imported.index);
        throw new Error(`${resolvedFile}:${line}:${column}: local @import must reference a .css file`);
      }
      visit(resolvedImport);
    }

    visiting.pop();
    visited.add(resolvedFile);
    stylesheets.push(stylesheet);
  }

  visit(resolvedEntry);
  return stylesheets;
}

function manifestLines(source, label) {
  const locate = locator(source);
  return maskComments(source, label, locate)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
}

function validateEntryManifest(entry) {
  const resolved = path.resolve(entry);
  const actual = manifestLines(fs.readFileSync(resolved, 'utf8').replace(/\r\n?/g, '\n'), resolved);
  if(actual.length !== EXPECTED_ENTRY_MANIFEST.length
    || actual.some((line, index) => line !== EXPECTED_ENTRY_MANIFEST[index])) {
    throw new Error(
      `${resolved}: stylesheet entry point must contain only the ordered, layered design-system manifest\n`
      + `Expected:\n${EXPECTED_ENTRY_MANIFEST.join('\n')}\nActual:\n${actual.join('\n')}`,
    );
  }
}

function validateModuleReachability(stylesheets, entry) {
  const stylesDirectory = path.resolve(path.dirname(path.resolve(entry)), 'styles');
  const reachable = new Set(stylesheets.map((stylesheet) => path.resolve(stylesheet.file)));
  const unreachable = [];

  function collect(directory) {
    for(const name of fs.readdirSync(directory)) {
      const candidate = path.join(directory, name);
      const stat = fs.statSync(candidate);
      if(stat.isDirectory()) collect(candidate);
      else if(name.endsWith('.css') && !reachable.has(path.resolve(candidate))) {
        unreachable.push(path.relative(path.dirname(path.resolve(entry)), candidate));
      }
    }
  }

  collect(stylesDirectory);
  if(unreachable.length) {
    throw new Error(`Stylesheet modules are not reachable from app.css:\n${unreachable.sort().join('\n')}`);
  }
}

function unscopedSelectorOccurrences(stylesheets) {
  const occurrences = new Map();
  for (const stylesheet of stylesheets) {
    for (const rule of stylesheet.rules) {
      for (const selector of splitSelectorList(rule.header)) {
        if (!isUnscopedBareSelector(selector)) continue;
        const list = occurrences.get(selector) || [];
        const selectorOffset = rule.header.indexOf(selector);
        const { line, column } = stylesheet.locate(rule.index + Math.max(0, selectorOffset));
        list.push({ file: stylesheet.file, line, column });
        occurrences.set(selector, list);
      }
    }
  }
  return occurrences;
}

function validateSelectorBudget(stylesheets, budget = LEGACY_UNSCOPED_SELECTOR_BUDGET) {
  const occurrences = unscopedSelectorOccurrences(stylesheets);
  const violations = [];
  for (const [selector, locations] of occurrences) {
    const allowance = budget.get(selector) || 0;
    const legacyLocations = locations.filter((location) =>
      path.basename(location.file) === 'legacy.css' &&
      path.basename(path.dirname(location.file)) === 'styles');
    const moduleLocations = locations.filter((location) => !legacyLocations.includes(location));
    for (const location of moduleLocations) {
      violations.push(
        `${location.file}:${location.line}:${location.column}: unscoped selector \`${selector}\` `
        + 'is permitted only in assets/styles/legacy.css',
      );
    }
    for (const location of legacyLocations.slice(allowance)) {
      violations.push(
        `${location.file}:${location.line}:${location.column}: unscoped selector \`${selector}\` `
        + 'must be scoped beneath a page, component, or density/composition boundary',
      );
    }
  }
  for(const [selector, allowance] of budget) {
    const actual = (occurrences.get(selector) || []).filter((location) =>
      path.basename(location.file) === 'legacy.css'
      && path.basename(path.dirname(location.file)) === 'styles').length;
    if(actual !== allowance) {
      violations.push(
        `assets/styles/legacy.css: expected exactly ${allowance} occurrence(s) of legacy selector `
        + `\`${selector}\`, found ${actual}`,
      );
    }
  }
  if (violations.length) {
    throw new Error(`Unscoped control/table selector contract failed:\n${violations.join('\n')}`);
  }
}

function stylesheetModule(stylesheets, entry, relativeName) {
  const stylesDirectory = path.resolve(path.dirname(path.resolve(entry)), 'styles');
  const expected = relativeName.split('/').join(path.sep);
  const matches = stylesheets.filter((stylesheet) =>
    path.relative(stylesDirectory, stylesheet.file) === expected);
  assert.equal(matches.length, 1, `Expected exactly one stylesheet module ${relativeName}`);
  return matches[0];
}

function ruleBody(source, header) {
  const start = source.indexOf(header);
  assert.notEqual(start, -1, `Missing CSS rule ${header}`);
  const open = source.indexOf('{', start + header.length);
  const close = source.indexOf('}', open + 1);
  assert.notEqual(open, -1, `Missing declaration block for ${header}`);
  assert.notEqual(close, -1, `Unclosed declaration block for ${header}`);
  return source.slice(open + 1, close);
}

function validateModernControlStates(stylesheets, entry) {
  const controls = stylesheetModule(stylesheets, entry, 'components/controls.css').source;
  assert.match(controls, /(?:^|\n)\.link-button\s*\{\s*min-height:\s*0;/,
    'Link-style buttons must not inherit the legacy button minimum height');
  const dangerHoverHeader = '.ui-button-danger:hover:not(:disabled),\n'
    + '.ui-button-danger-quiet:hover:not(:disabled)';
  const dangerHover = ruleBody(controls, dangerHoverHeader);
  assert.ok(controls.indexOf(dangerHoverHeader) > controls.indexOf('.ui-button:hover:not(:disabled)'),
    'Danger hover rules must follow the shared button hover rule');
  assert.match(dangerHover, /color:\s*var\(--danger\)/);
  assert.match(dangerHover, /background:[^;]*var\(--danger\)/);
  assert.match(dangerHover, /border-color:[^;]*var\(--danger\)/);

  const darkNeutral = ':root[data-theme="dark"] '
    + '.ui-button:not(.ui-button-primary):not(.ui-button-danger):not(.ui-button-danger-quiet)';
  ruleBody(controls, darkNeutral);
  const darkDanger = ruleBody(controls,
    ':root[data-theme="dark"] .ui-button-danger,\n'
    + ':root[data-theme="dark"] .ui-button-danger-quiet');
  assert.match(darkDanger, /color:\s*var\(--danger\)/);

  const darkPrimaryHeader = ':root[data-theme="dark"] .ui-button-primary';
  const darkPrimaryHoverHeader = `${darkPrimaryHeader}:hover:not(:disabled)`;
  const darkPrimary = ruleBody(controls, darkPrimaryHeader);
  const darkPrimaryHover = ruleBody(controls, darkPrimaryHoverHeader);
  assert.ok(controls.indexOf(darkPrimaryHoverHeader) > controls.indexOf(darkPrimaryHeader),
    'The dark primary hover rule must follow the dark primary base rule');
  assert.notEqual(
    darkPrimaryHover.match(/background:\s*([^;]+)/)?.[1],
    darkPrimary.match(/background:\s*([^;]+)/)?.[1],
    'Dark primary hover must visibly change its background',
  );
}

function validateReducedMotionCoverage(stylesheets, entry) {
  const stylesDirectory = path.resolve(path.dirname(path.resolve(entry)), 'styles');
  const utilities = stylesheetModule(stylesheets, entry, 'utilities/reduced-motion.css');
  const reducedMotionHeader = '@media (prefers-reduced-motion: reduce)';
  const disabledTransitions = new Set();

  for (const rule of utilities.rules) {
    if (!rule.atRules.includes(reducedMotionHeader) || !/\btransition\s*:\s*none\s*;?/i.test(rule.body)) continue;
    for (const selector of splitSelectorList(rule.header)) disabledTransitions.add(selector);
  }

  const missing = [];
  for (const stylesheet of stylesheets) {
    const relative = path.relative(stylesDirectory, stylesheet.file).split(path.sep).join('/');
    if (!/^(?:components|compositions|features)\//.test(relative)) continue;
    for (const rule of stylesheet.rules) {
      if (!/\btransition(?:-[a-z-]+)?\s*:/i.test(rule.body)) continue;
      for (const selector of splitSelectorList(rule.header)) {
        if (!disabledTransitions.has(selector)) missing.push(`${relative}: ${selector}`);
      }
    }
  }

  assert.deepEqual(missing, [],
    `Modern transitions need ${reducedMotionHeader} overrides in utilities/reduced-motion.css`);
  const pressedButton = ruleBody(utilities.source, '.ui-button:active:not(:disabled)');
  assert.match(pressedButton, /transform:\s*none/);
}

function runFocusedContractTests() {
  const valid = parseStylesheet(
    '/* } */ @media (width > 1px) { .card:is(.wide, .narrow) { content: "}"; } }',
    'balanced-fixture.css',
  );
  assert.deepEqual(valid.rules.map((rule) => rule.header), ['.card:is(.wide, .narrow)']);
  assert.throws(
    () => parseStylesheet('.card { color: red; }}', 'extra-brace.css'),
    /extra-brace\.css:1:22: unmatched closing brace/,
  );
  assert.throws(
    () => parseStylesheet('.card { color: red;', 'missing-brace.css'),
    /missing-brace\.css:1:1: unclosed block/,
  );
  assert.throws(
    () => parseStylesheet('.card:not(.wide { color: red; }', 'missing-paren.css'),
    /block opened before closing \(/,
  );

  const selectorFixture = parseStylesheet(
    'button.primary, .dialog select { color: red; } td.numeric { text-align: right; }',
    'selector-fixture.css',
  );
  const fixtureStylesheet = [{
    file: path.join('styles', 'legacy.css'),
    source: '',
    ...selectorFixture,
  }];
  assert.deepEqual(
    [...unscopedSelectorOccurrences(fixtureStylesheet).keys()],
    ['button.primary', 'td.numeric'],
  );
  const bypassFixture = [{
    file: path.join('styles', 'components', 'controls.css'),
    source: '',
    ...parseStylesheet(
      'body button, :where(button), :is(select), html table { color: red; }',
      'selector-bypass.css',
    ),
  }];
  assert.deepEqual(
    [...unscopedSelectorOccurrences(bypassFixture).keys()],
    ['body button', ':where(button)', ':is(select)', 'html table'],
  );
  assert.throws(
    () => validateSelectorBudget(bypassFixture, new Map()),
    /permitted only in assets\/styles\/legacy\.css/,
  );
  assert.throws(
    () => validateSelectorBudget(fixtureStylesheet, new Map([['button.primary', 1]])),
    /unscoped selector `td\.numeric`/,
  );
  assert.throws(
    () => validateSelectorBudget([{
      file: path.join('styles', 'components', 'controls.css'),
      source: '',
      ...parseStylesheet('select { color: red; }', 'controls.css'),
    }], new Map([['select', 1]])),
    /permitted only in assets\/styles\/legacy\.css/,
  );

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'stats-web-css-contract-'));
  try {
    const assets = path.join(temporaryRoot, 'assets');
    const styles = path.join(assets, 'styles');
    fs.mkdirSync(styles, { recursive: true });
    fs.writeFileSync(path.join(assets, 'app.css'), '@import "./styles/base.css";\n.shell button { color: red; }\n');
    fs.writeFileSync(path.join(styles, 'base.css'), '@import url("nested.css");\n.grid table { width: 100%; }\n');
    fs.writeFileSync(path.join(styles, 'nested.css'), '.field select { width: 100%; }\n');
    const fixtureGraph = readStylesheetGraph(path.join(assets, 'app.css'));
    assert.deepEqual(
      fixtureGraph.map((item) => path.basename(item.file)),
      ['nested.css', 'base.css', 'app.css'],
    );
    validateSelectorBudget(fixtureGraph, new Map());

    fs.writeFileSync(path.join(styles, 'nested.css'), 'select.compact { width: 100%; }\n');
    assert.throws(
      () => validateSelectorBudget(readStylesheetGraph(path.join(assets, 'app.css')), new Map()),
      /unscoped selector `select\.compact`/,
    );
    fs.writeFileSync(path.join(styles, 'nested.css'), '.field { width: 100%; }}\n');
    assert.throws(
      () => readStylesheetGraph(path.join(assets, 'app.css')),
      /nested\.css:1:24: unmatched closing brace/,
    );

    fs.writeFileSync(path.join(styles, 'base.css'), '@import "../outside.css";\n');
    assert.throws(
      () => readStylesheetGraph(path.join(assets, 'app.css')),
      /local @import must stay under/,
    );

    fs.writeFileSync(path.join(assets, 'app.css'), '@import "https://example.com/theme.css";\n');
    assert.throws(
      () => readStylesheetGraph(path.join(assets, 'app.css')),
      /remote stylesheet imports are not allowed/,
    );

    fs.writeFileSync(path.join(assets, 'app.css'), `${EXPECTED_ENTRY_MANIFEST.join('\n')}\n.extra { color: red; }\n`);
    assert.throws(
      () => validateEntryManifest(path.join(assets, 'app.css')),
      /must contain only the ordered, layered design-system manifest/,
    );
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
  }
}

runFocusedContractTests();
validateEntryManifest(entryStylesheet);
const stylesheets = readStylesheetGraph(entryStylesheet);
validateModuleReachability(stylesheets, entryStylesheet);
validateSelectorBudget(stylesheets);
validateModernControlStates(stylesheets, entryStylesheet);
validateReducedMotionCoverage(stylesheets, entryStylesheet);
console.log(`CSS architecture contract passed for ${stylesheets.length} stylesheet(s).`);

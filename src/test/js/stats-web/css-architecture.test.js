'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const entryStylesheet = path.resolve(process.argv[2]
  || path.resolve(__dirname, '../../../../stats-web/assets/app.css'));

const EXPECTED_ENTRY_MANIFEST = [
  '@layer reset, tokens, components, compositions, features, utilities;',
  '@import url("./styles/base.css?v=1") layer(reset);',
  '@import url("./styles/tokens.css?v=13") layer(tokens);',
  '@import url("./styles/components/semantic-text.css?v=2") layer(components);',
  '@import url("./styles/components/controls.css?v=27") layer(components);',
  '@import url("./styles/components/audio-controls.css?v=1") layer(components);',
  '@import url("./styles/compositions/workspaces.css?v=18") layer(compositions);',
  '@import url("./styles/compositions/tables.css?v=11") layer(compositions);',
  '@import url("./styles/compositions/app-chrome.css?v=12") layer(compositions);',
  '@import url("./styles/compositions/audio-dock.css?v=1") layer(compositions);',
  '@import url("./styles/compositions/charts.css?v=3") layer(compositions);',
  '@import url("./styles/compositions/modals.css?v=6") layer(compositions);',
  '@import url("./styles/compositions/settings.css?v=7") layer(compositions);',
  '@import url("./styles/features/access-landing.css?v=2") layer(features);',
  '@import url("./styles/features/about.css?v=1") layer(features);',
  '@import url("./styles/features/channels.css?v=16") layer(features);',
  '@import url("./styles/features/entity-details.css?v=11") layer(features);',
  '@import url("./styles/features/live.css?v=15") layer(features);',
  '@import url("./styles/features/radio-directory.css?v=6") layer(features);',
  '@import url("./styles/features/tuner-spectrum.css?v=10") layer(features);',
  '@import url("./styles/features/tuners.css?v=10") layer(features);',
  '@import url("./styles/features/listen-map.css?v=2") layer(features);',
  '@import url("./styles/features/network-visualizer.css?v=9") layer(features);',
  '@import url("./styles/features/scanner.css?v=6") layer(features);',
  '@import url("./styles/features/aliases.css?v=16") layer(features);',
  '@import url("./styles/features/scan-lists.css?v=3") layer(features);',
  '@import url("./styles/features/dashboard.css?v=3") layer(features);',
  '@import url("./styles/features/administration.css?v=8") layer(features);',
  '@import url("./styles/features/retained-statistics.css?v=2") layer(features);',
  '@import url("./styles/features/call-matching.css?v=4") layer(features);',
  '@import url("./styles/features/signal-quality.css?v=4") layer(features);',
  '@import url("./styles/features/radioreference.css?v=15") layer(features);',
  '@import url("./styles/features/streaming.css?v=4") layer(features);',
  '@import url("./styles/features/remote-links.css?v=5") layer(features);',
  '@import url("./styles/features/p25-settings.css?v=4") layer(features);',
  '@import url("./styles/features/receiver-health.css?v=6") layer(features);',
  '@import url("./styles/features/activity.css?v=3") layer(features);',
  '@import url("./styles/features/recordings.css?v=9") layer(features);',
  '@import url("./styles/utilities/reduced-motion.css?v=11") layer(utilities);',
];

// Feature styles may shape shared primitives only where page-specific composition requires it.
// This is a shrinking migration budget, not permission for new shared-component overrides.
const FEATURE_SHARED_SELECTOR_BUDGET = 30;

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

function validateSelectorBoundaries(stylesheets) {
  const occurrences = unscopedSelectorOccurrences(stylesheets);
  const violations = [];
  for (const [selector, locations] of occurrences) {
    for (const location of locations) {
      violations.push(
        `${location.file}:${location.line}:${location.column}: unscoped selector \`${selector}\` `
        + 'must be scoped beneath a page, component, or density/composition boundary',
      );
    }
  }
  if (violations.length) {
    throw new Error(`Unscoped control/table selector contract failed:\n${violations.join('\n')}`);
  }
}

function relativeStyleName(stylesheet, entry) {
  const stylesDirectory = path.resolve(path.dirname(path.resolve(entry)), 'styles');
  return path.relative(stylesDirectory, stylesheet.file).split(path.sep).join('/');
}

function topLevelCombinatorCount(selector) {
  let parentheses = 0;
  let brackets = 0;
  let quote = '';
  let combinators = 0;
  let whitespace = false;
  for(let index = 0; index < selector.length; index += 1) {
    const character = selector[index];
    if(quote) {
      if(character === '\\') index += 1;
      else if(character === quote) quote = '';
      continue;
    }
    if(character === '"' || character === "'") quote = character;
    else if(character === '(') parentheses += 1;
    else if(character === ')') parentheses = Math.max(0, parentheses - 1);
    else if(character === '[') brackets += 1;
    else if(character === ']') brackets = Math.max(0, brackets - 1);
    else if(parentheses === 0 && brackets === 0 && /\s/.test(character)) whitespace = true;
    else if(parentheses === 0 && brackets === 0) {
      if(character === '>' || character === '+' || character === '~') {
        combinators += 1;
        whitespace = false;
      } else if(whitespace) {
        combinators += 1;
        whitespace = false;
      }
    }
  }
  return combinators;
}

function validateModernDesignSystemBoundaries(stylesheets, entry) {
  const violations = [];
  let featureSharedSelectors = 0;
  for(const stylesheet of stylesheets) {
    const relative = relativeStyleName(stylesheet, entry);
    if(relative === 'tokens.css' || relative.startsWith('..')) continue;

    const rawColors = stylesheet.source.match(/#[0-9a-f]{3,8}\b|rgba?\s*\(/gi) || [];
    if(rawColors.length) {
      violations.push(`${relative}: ${rawColors.length} raw color value(s); add a semantic token in tokens.css`);
    }

    if(/:root\[data-theme=["']dark["']\]/.test(stylesheet.source)) {
      violations.push(`${relative}: dark-theme overrides belong in tokens.css`);
    }

    if(/^(?:components|compositions|features|utilities)\//.test(relative)) {
      const importantCount = (stylesheet.source.match(/!important\b/gi) || []).length;
      if(importantCount > 0) {
        violations.push(`${relative}: !important is not allowed, found ${importantCount}`);
      }
    }

    for(const rule of stylesheet.rules) {
      for(const selector of splitSelectorList(rule.header)) {
        if(relative.startsWith('features/') && /\.ui-[a-z0-9_-]+/i.test(selector)) {
          featureSharedSelectors += 1;
        }
        if(/#[a-z_][a-z0-9_-]*/i.test(selector)) {
          const { line, column } = stylesheet.locate(rule.index);
          violations.push(`${relative}:${line}:${column}: modern selectors may not use IDs: ${selector}`);
        }
        const depth = topLevelCombinatorCount(selector);
        if(depth > 4) {
          const { line, column } = stylesheet.locate(rule.index);
          violations.push(`${relative}:${line}:${column}: selector depth ${depth} exceeds 4: ${selector}`);
        }
      }
    }
  }
  if(featureSharedSelectors > FEATURE_SHARED_SELECTOR_BUDGET) {
    violations.push(`feature styles target shared .ui-* components ${featureSharedSelectors} times; `
      + `budget is ${FEATURE_SHARED_SELECTOR_BUDGET}. Move the rule into components or compositions.`);
  }
  if(violations.length) {
    throw new Error(`Design-system boundary contract failed:\n${violations.join('\n')}`);
  }
}

function validateInlineStyleRatchets(entry) {
  const assets = path.dirname(path.resolve(entry));
  const indexSource = fs.readFileSync(path.resolve(assets, '../index.html'), 'utf8');
  const appSource = fs.readFileSync(path.resolve(assets, 'app.js'), 'utf8');
  assert.doesNotMatch(indexSource, /\sstyle\s*=/i,
    'Production HTML must use design-system classes instead of inline style attributes');
  const inlineStylePattern = /\.style\.cssText\s*=|setAttribute\(\s*['"]style['"]/;
  assert.doesNotMatch(appSource, inlineStylePattern,
    'JavaScript must not inject arbitrary style strings; use classes, tokens, or bounded geometry properties');
  assert.match(appSource, /element\.classList\.add\('ui-feedback', `ui-feedback-\$\{state\}`\)/,
    'Feedback constructors must adapt to shared feedback primitives');
  assert.match(appSource, /element\.classList\.contains\('admin-form-actions'\).*'ui-action-row'/,
    'Administration action rows must receive the shared action-row primitive');
  assert.match(appSource, /control\.classList\.add\('ui-input'\)/,
    'Administration fields must receive shared input primitives through formField');
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
    'Link-style buttons must not inherit the shared button minimum height');
  const dangerHoverHeader = '.ui-button-danger:hover:not(:disabled, [aria-disabled="true"]),\n'
    + '.ui-button-danger-quiet:hover:not(:disabled, [aria-disabled="true"])';
  const dangerHover = ruleBody(controls, dangerHoverHeader);
  assert.ok(controls.indexOf(dangerHoverHeader) >
    controls.indexOf('.ui-button:hover:not(:disabled, [aria-disabled="true"])'),
    'Danger hover rules must follow the shared button hover rule');
  assert.match(dangerHover, /color:\s*var\(--danger\)/);
  assert.match(dangerHover, /background:[^;]*var\(--danger\)/);
  assert.match(dangerHover, /border-color:[^;]*var\(--danger\)/);

  assert.match(controls,
    /(?:^|\n)\.ui-button-primary\s*\{[^}]*background:\s*var\(--primary-control\)/,
    'Primary buttons must use the theme token');
  assert.match(controls,
    /(?:^|\n)\.ui-button-primary:hover:not\(:disabled, \[aria-disabled="true"\]\)\s*\{[^}]*background:\s*var\(--primary-control-hover\)/,
    'Primary button hover must use the theme hover token');
}

function validateThemeFoundation(stylesheets, entry) {
  const tokens = stylesheetModule(stylesheets, entry, 'tokens.css').source;
  const controls = stylesheetModule(stylesheets, entry, 'components/controls.css').source;
  const workspaces = stylesheetModule(stylesheets, entry, 'compositions/workspaces.css').source;

  for(const token of ['--radius-control', '--radius-card', '--radius-modal', '--font-size-body',
    '--font-size-control', '--font-weight-control', '--shadow-button', '--shadow-button-hover',
    '--shadow-button-pressed', '--shadow-surface', '--shadow-surface-soft']) {
    assert.match(tokens, new RegExp(`${token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*:`),
      `The unified theme foundation requires ${token}`);
  }

  const button = ruleBody(controls, '.ui-button');
  assert.match(button, /border-radius:\s*var\(--ui-control-radius, var\(--radius-control\)\)/,
    'Shared buttons must use the semantic control radius');
  assert.match(button, /box-shadow:\s*var\(--shadow-button\)/,
    'Shared buttons must use the semantic button elevation');
  assert.match(button, /font-size:\s*var\(--font-size-control\)/,
    'Shared buttons must use the semantic control type size');
  assert.match(button, /font-weight:\s*var\(--font-weight-control\)/,
    'Shared buttons must use the semantic control weight');

  const compact = ruleBody(workspaces, ':where(.data-workspace, [data-ui-density="compact"])');
  assert.match(compact, /--ui-control-radius:\s*var\(--radius-control\)/,
    'Compact workspaces must share the standard control shape');
  assert.match(compact, /--ui-surface-radius:\s*var\(--radius-card\)/,
    'Compact workspaces must share the standard card shape');
  assert.match(compact, /--ui-surface-shadow:\s*var\(--shadow-surface-soft\)/,
    'Compact workspaces must retain the shared surface elevation');
  assert.doesNotMatch(compact, /--ui-(?:control|surface)-radius:\s*var\(--radius-compact\)/,
    'Density may change spacing but must not create an older compact visual theme');
}

function validateModalComposition(stylesheets, entry) {
  const modals = stylesheetModule(stylesheets, entry, 'compositions/modals.css').source;
  for(const selector of ['body.modal-open', '.modal-backdrop', '.read-only-modal', '.modal-header',
    '.modal-header h2', '.modal-content']) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const baseRule = new RegExp(`(?:^|\\n)${escaped}\\s*\\{`);
    assert.match(modals, baseRule, `Missing shared modal foundation rule ${selector}`);
  }
  assert.match(modals, /@media \(max-width: 560px\)[\s\S]*\.read-only-modal\s*\{[\s\S]*height:\s*100dvh/,
    'Shared modals must become full-height dialogs on small screens');
  assert.match(modals, /\.modal-content\s*\{[\s\S]*overflow:\s*auto/,
    'Shared modal content must own bounded scrolling');
}

function validateSettingsComposition(stylesheets, entry) {
  const settings = stylesheetModule(stylesheets, entry, 'compositions/settings.css').source;
  for(const selector of ['.admin-form', '.settings-page-form', '.settings-card-grid', '.settings-card',
    '.settings-card-header', '.settings-card-body', '.settings-form-footer', '.settings-summary',
    '.admin-settings-form', '.admin-toggle-control', '.admin-form-message']) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const baseRule = new RegExp(`(?:^|\\n)${escaped}\\s*\\{`);
    assert.match(settings, baseRule, `Missing shared settings foundation rule ${selector}`);
  }
  assert.match(settings,
    /@media \(max-width: 560px\)[\s\S]*\.settings-field-grid,[\s\S]*\.settings-form-footer,[\s\S]*\.admin-settings-form\s*\{[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'Shared settings forms must collapse to a single column on small screens');
  assert.match(settings,
    /\.settings-form-footer\s*\{[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\) max-content/,
    'Shared settings forms must use the standard message-and-action footer');
}

function validateSettingsFeatures(stylesheets, entry) {
  const p25 = stylesheetModule(stylesheets, entry, 'features/p25-settings.css').source;
  const health = stylesheetModule(stylesheets, entry, 'features/receiver-health.css').source;
  const chrome = stylesheetModule(stylesheets, entry, 'compositions/app-chrome.css').source;
  assert.match(chrome, /(?:^|\n)\.ui-header-indicator\s*\{/,
    'Header status icons should use the shared chrome treatment');
  for(const [selector, stylesheet, label] of [
    ['.p25-overrides-intro', p25, 'P25 settings'],
    ['.p25-override-profile-header', p25, 'P25 settings'],
    ['.p25-override-band-row', p25, 'P25 settings'],
    ['.receiver-health-overview', health, 'receiver health'],
    ['.receiver-health-resource-bars', health, 'receiver health'],
    ['.receiver-health-incident', health, 'receiver health'],
    ['.receiver-health-measurement-row', health, 'receiver health'],
  ]) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const baseRule = new RegExp(`(?:^|\\n)${escaped}\\s*\\{`);
    assert.match(stylesheet, baseRule, `Missing ${label} rule ${selector}`);
  }
  assert.doesNotMatch(health, /:root\[data-theme="dark"\]/,
    'Receiver-health presentation must adapt through semantic tokens instead of feature theme overrides');
  assert.match(health,
    /@media \(max-width: 560px\)[\s\S]*\.receiver-health-overview,[\s\S]*\.receiver-health-incident-guidance[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'Receiver-health layouts must collapse on small screens');
  assert.match(p25,
    /@media \(max-width: 560px\)[\s\S]*\.p25-override-identity,[\s\S]*\.p25-override-band-row[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'P25 override fields must collapse on small screens');
}

function validateTunerSpectrumFeature(stylesheets, entry) {
  const spectrum = stylesheetModule(stylesheets, entry, 'features/tuner-spectrum.css').source;
  for(const selector of ['.tuner-spectrum-layout', '.tuner-spectrum-toolbar',
    '.tuner-spectrum-options-panel', '.tuner-spectrum-card', '.tuner-spectrum-plot']) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    assert.match(spectrum, new RegExp(`(?:^|\\n)${escaped}\\s*\\{`),
      `Missing Tuner Spectrum rule ${selector}`);
  }
  assert.match(spectrum,
    /@media \(max-width: 680px\)[\s\S]*\.tuner-spectrum-options\[open\] \.tuner-spectrum-options-panel\s*\{[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'Tuner Spectrum options must collapse to one column on small screens');
  assert.doesNotMatch(spectrum, /:root\[data-theme="dark"\]/,
    'Tuner Spectrum presentation must adapt through semantic tokens instead of feature theme overrides');
}

function validateScannerFeature(stylesheets, entry) {
  const scanner = stylesheetModule(stylesheets, entry, 'features/scanner.css').source;
  const chrome = stylesheetModule(stylesheets, entry, 'compositions/app-chrome.css').source;
  for(const selector of ['.scanner-page', '.scanner-workspace', '.scanner-console-main',
    '.scanner-now-playing', '.scanner-display', '.scanner-scan-lists']) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    assert.match(scanner, new RegExp(`(?:^|\\n)${escaped}\\s*\\{`),
      `Missing Scanner rule ${selector}`);
  }
  assert.match(scanner,
    /\.scanner-console-main\s*\{[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\) minmax\(300px, 340px\)/,
    'Scanner console must reserve a bounded desktop rail for scan lists');
  assert.match(scanner,
    /@media \(max-width: 1000px\)[\s\S]*\.scanner-console-main\s*\{[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'Scanner console must stack the scan-list rail on narrower screens');
  assert.match(scanner,
    /@media \(max-width: 680px\)[\s\S]*\.scanner-scan-buttons,[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'Scanner scan lists must collapse to one column on small screens');
  assert.match(chrome,
    /:is\(\.desktop-playback-slot, \.scanner-player-host\) \.playback-control-menu > summary\s*\{/,
    'Responsive Scanner playback controls must retain the shared trigger button shell');
  assert.match(chrome,
    /:is\(\.desktop-playback-slot, \.scanner-player-host\) \.playback-control-menu > summary:focus-visible\s*\{/,
    'Responsive Scanner playback controls must retain the shared focus treatment');
  assert.doesNotMatch(scanner, /:root\[data-theme="dark"\]/,
    'Scanner presentation must adapt through semantic tokens instead of feature theme overrides');
  assert.doesNotMatch(scanner, /#[0-9a-f]{3,8}\b|\brgba?\(/i,
    'Scanner presentation must use semantic color tokens');
  assert.doesNotMatch(scanner, /!important/,
    'Scanner presentation must not add important declarations');
}

function validateAliasesFeature(stylesheets, entry) {
  const aliases = stylesheetModule(stylesheets, entry, 'features/aliases.css').source;
  for(const selector of ['.alias-editor-workspace', '.alias-list-rail', '.alias-list-summary',
    '.alias-editor-filter-toolbar', '.scan-list-members-workspace']) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    assert.match(aliases, new RegExp(`(?:^|\\n)${escaped}\\s*\\{`),
      `Missing Alias management rule ${selector}`);
  }
  assert.match(aliases,
    /@media \(max-width: 900px\)[\s\S]*\.alias-editor-workspace\s*\{[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'Alias management workspace must collapse on tablet-sized screens');
  assert.match(aliases,
    /@media \(max-width: 560px\)[\s\S]*\.alias-filter-group-identity \.alias-filter-group-fields,[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'Alias management filters must collapse on small screens');
  assert.doesNotMatch(aliases, /:root\[data-theme="dark"\]/,
    'Alias management presentation must adapt through semantic tokens instead of feature theme overrides');

  const assets = path.dirname(path.resolve(entry));
  const appSource = fs.readFileSync(path.resolve(assets, 'app.js'), 'utf8');
  const channelEditorStart = appSource.indexOf('async function openChannelEditorModal');
  const channelEditorEnd = appSource.indexOf('\nasync function ', channelEditorStart + 1);
  assert.ok(channelEditorStart >= 0 && channelEditorEnd > channelEditorStart,
    'Channel editor function must remain discoverable for dependency checks');
  assert.doesNotMatch(appSource.slice(channelEditorStart, channelEditorEnd), /alias-(?:editor|modal)/,
    'Channels must not borrow Alias Editor presentation classes or helpers');
}

function validateScanListsFeature(stylesheets, entry) {
  const scanLists = stylesheetModule(stylesheets, entry, 'features/scan-lists.css').source;
  for(const selector of ['.scan-list-catalog', '.scan-list-catalog-toolbar',
    '.scan-list-layout', '.scan-list-list', '.scan-list-selector-main', '.scan-list-detail']) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    assert.match(scanLists, new RegExp(`(?:^|\\n)${escaped}\\s*\\{`),
      `Missing Scan Lists rule ${selector}`);
  }
  assert.match(scanLists,
    /\.scan-list-layout\s*\{[^}]*grid-template-columns:\s*[^;}]*\b(?:fr|minmax)\b/,
    'Scan List catalog must present a two-pane layout on desktop');
  assert.match(scanLists,
    /@media\s*\(max-width:\s*\d+px\)[\s\S]*\.scan-list-layout\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'Scan List panes must stack on small screens');
  assert.match(scanLists,
    /@media\s*\(max-width:\s*\d+px\)[\s\S]*\.scan-list-list\s*\{[^}]*max-height:\s*[^;]+;[^}]*overflow-y:\s*auto/,
    'The stacked Scan List selector list must have bounded scrolling');
  assert.doesNotMatch(scanLists, /:root\[data-theme="dark"\]/,
    'Scan Lists presentation must adapt through semantic tokens instead of feature theme overrides');
  assert.doesNotMatch(scanLists, /#[0-9a-f]{3,8}\b|\brgba?\(/i,
    'Scan Lists presentation must use semantic color tokens');
  assert.doesNotMatch(scanLists, /!important/,
    'Scan Lists presentation must not add important declarations');
}

function validateLiveFeature(stylesheets, entry) {
  const live = stylesheetModule(stylesheets, entry, 'features/live.css').source;
  for(const selector of ['body[data-view="live"]', '.live-right-workspace', '.live-channel-picker',
    '.channels-live-tabs', '.live-selected-view-header', '.live-details-header',
    '.live-workspace-resizer', '.live-workspace-resizer-grip', '.channel-diagnostic-grid',
    '.live-filter-editor']) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const baseRule = new RegExp(`(?:^|\\n)${escaped}\\s*\\{`);
    assert.match(live, baseRule, `Missing Live feature rule ${selector}`);
  }
  assert.doesNotMatch(live, /:root\[data-theme="dark"\]/,
    'Live presentation must adapt through semantic tokens instead of feature theme overrides');
  assert.match(live,
    /@media \(max-width: 680px\)[\s\S]*\.live-details-header\s*\{[\s\S]*flex-wrap:\s*wrap/,
    'Live detail controls must wrap cleanly on small screens');
  assert.match(live,
    /@media \(max-width: 1120px\)[\s\S]*\.live-picker-toggle\s*\{[\s\S]*display:\s*inline-flex/,
    'The desktop Live picker must collapse to a mobile selector at narrow widths');
  assert.match(live,
    /\.live-split\.picker-collapsed\s*\{[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'The collapsed desktop Live picker must release its full column');
  assert.match(live,
    /\.live-split\.picker-collapsed > \.live-channel-picker\s*\{[\s\S]*display:\s*none/,
    'The collapsed desktop Live picker must not leave a blank rail');
  assert.doesNotMatch(live, /grid-template-columns:\s*72px minmax\(0, 1fr\)/,
    'The removed picker rail must not return');
  const workspace = ruleBody(live, '.live-right-workspace');
  assert.match(workspace, /--live-primary-pane-share:\s*75fr/);
  assert.match(workspace, /--live-details-pane-share:\s*25fr/);
  assert.match(workspace,
    /grid-template-rows:[\s\S]*var\(--live-primary-pane-share\)[\s\S]*14px[\s\S]*var\(--live-details-pane-share\)/,
    'The Live right workspace must reserve a draggable row between its two panels');
  assert.match(ruleBody(live, '.live-workspace-resizer'), /cursor:\s*row-resize/,
    'The Live panel separator must advertise vertical resizing');
  assert.match(live,
    /\.live-split\.details-collapsed \.live-workspace-resizer\s*\{[\s\S]*display:\s*none/,
    'Collapsing details must also hide its inactive separator');
  assert.match(live,
    /@media \(max-width: 760px\)[\s\S]*\.live-workspace-resizer\s*\{[\s\S]*display:\s*none/,
    'The stacked mobile Live view must hide the desktop resize handle');
  assert.doesNotMatch(live,
    /live-(?:events|messages|channel)-(?:toolbar|selection)|live-details-summary/,
    'The compact details header must replace legacy duplicate toolbars and headings');
  assert.match(live,
    /@media \(max-width: 1120px\)[\s\S]*\.live-picker-collapse\s*\{[\s\S]*display:\s*none/,
    'The desktop collapse control must not replace the narrow-screen picker');
  assert.match(live,
    /@media \(max-width: 1120px\)[\s\S]*\.live-split\.picker-collapsed\s*\{[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)/,
    'A saved collapsed state must return to the single-column picker on narrow screens');
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
  const hoveredButton = ruleBody(utilities.source,
    '.ui-button:hover:not(:disabled, [aria-disabled="true"])');
  assert.match(hoveredButton, /transform:\s*none/);
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
    file: path.join('styles', 'components', 'controls.css'),
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
    () => validateSelectorBoundaries(bypassFixture),
    /must be scoped beneath a page, component, or density\/composition boundary/,
  );
  assert.throws(
    () => validateSelectorBoundaries(fixtureStylesheet),
    /unscoped selector `button\.primary`/,
  );
  assert.throws(
    () => validateSelectorBoundaries([{
      file: path.join('styles', 'components', 'controls.css'),
      source: '',
      ...parseStylesheet('select { color: red; }', 'controls.css'),
    }]),
    /unscoped selector `select`.*must be scoped/,
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
    validateSelectorBoundaries(fixtureGraph);

    fs.writeFileSync(path.join(styles, 'nested.css'), 'select.compact { width: 100%; }\n');
    assert.throws(
      () => validateSelectorBoundaries(readStylesheetGraph(path.join(assets, 'app.css'))),
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
validateSelectorBoundaries(stylesheets);
validateModernDesignSystemBoundaries(stylesheets, entryStylesheet);
validateInlineStyleRatchets(entryStylesheet);
validateModernControlStates(stylesheets, entryStylesheet);
validateThemeFoundation(stylesheets, entryStylesheet);
validateModalComposition(stylesheets, entryStylesheet);
validateSettingsComposition(stylesheets, entryStylesheet);
validateSettingsFeatures(stylesheets, entryStylesheet);
validateTunerSpectrumFeature(stylesheets, entryStylesheet);
validateScannerFeature(stylesheets, entryStylesheet);
validateAliasesFeature(stylesheets, entryStylesheet);
validateScanListsFeature(stylesheets, entryStylesheet);
validateLiveFeature(stylesheets, entryStylesheet);
validateReducedMotionCoverage(stylesheets, entryStylesheet);
console.log(`CSS architecture contract passed for ${stylesheets.length} stylesheet(s).`);

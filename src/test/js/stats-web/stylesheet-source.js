'use strict';

const fs = require('node:fs');
const path = require('node:path');

/** Reads imported modules in cascade order, followed by their importing stylesheet. */
function readStylesheetSource(entryStylesheet, visited = new Set(), visiting = new Set()) {
  const resolved = path.resolve(entryStylesheet);
  if (visited.has(resolved)) return '';
  if (visiting.has(resolved)) throw new Error(`Circular stylesheet import: ${resolved}`);
  visiting.add(resolved);
  const source = fs.readFileSync(resolved, 'utf8');
  const imports = [...source.matchAll(
    /@import\s+(?:url\(\s*)?["']([^"']+\.css(?:[?#][^"']*)?)["']\s*\)?[^;]*;/gi,
  )];
  const importedSources = imports.map((match) => {
    const specifier = match[1].split(/[?#]/, 1)[0];
    if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(specifier)) {
      throw new Error(`Remote stylesheet imports are not allowed: ${specifier}`);
    }
    return readStylesheetSource(path.resolve(path.dirname(resolved), specifier), visited, visiting);
  });
  visiting.delete(resolved);
  visited.add(resolved);
  return [...importedSources, source].join('\n');
}

module.exports = { readStylesheetSource };

'use strict';

// Preserve the contracts' existing quote-aware brace boundaries; this is not a JavaScript parser.
function functionSource(source, signature) {
  const start = source.indexOf(signature);
  if (start < 0) throw new Error(`Missing ${signature}`);
  const openingBrace = source.indexOf('{', start + signature.length);
  let depth = 0;
  let quote = '';
  let escaped = false;
  for (let index = openingBrace; index < source.length; index += 1) {
    const character = source[index];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (quote) {
      if (character === '\\') escaped = true;
      else if (character === quote) quote = '';
      continue;
    }
    if (character === '\'' || character === '"' || character === '`') {
      quote = character;
      continue;
    }
    if (character === '{') depth += 1;
    else if (character === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`Unterminated ${signature}`);
}

module.exports = { functionSource };

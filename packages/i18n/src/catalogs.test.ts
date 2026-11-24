import { describe, expect, it } from 'vitest';

import { LOCALE_CODES, messages, resolveLocale } from './index';

type Tree = { [key: string]: string | Tree };

function flatten(tree: Tree, prefix = ''): Map<string, string> {
  const out = new Map<string, string>();
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') out.set(path, value);
    else for (const [k, v] of flatten(value, path)) out.set(k, v);
  }
  return out;
}

/** Top-level ICU argument names, e.g. "{count, plural, ...} {skill}" → ["count", "skill"]. */
function placeholders(message: string): string[] {
  const names = new Set<string>();
  let depth = 0;
  for (let i = 0; i < message.length; i++) {
    const ch = message[i];
    if (ch === '{') {
      if (depth === 0) {
        const match = /^\{\s*([A-Za-z_][\w]*)/.exec(message.slice(i));
        if (match?.[1]) names.add(match[1]);
      }
      depth++;
    } else if (ch === '}') {
      depth--;
    }
  }
  return [...names].sort();
}

const reference = flatten(messages.en as Tree);

describe.each(LOCALE_CODES.filter((l) => l !== 'en'))('catalog %s', (locale) => {
  const catalog = flatten(messages[locale] as Tree);

  it('has exactly the same keys as en', () => {
    expect([...catalog.keys()].sort()).toEqual([...reference.keys()].sort());
  });

  it('uses the same placeholders as en', () => {
    for (const [key, source] of reference) {
      expect(placeholders(catalog.get(key) ?? ''), key).toEqual(placeholders(source));
    }
  });

  it('has no empty messages', () => {
    for (const [key, value] of catalog) expect(value.trim(), key).not.toBe('');
  });
});

describe('resolveLocale', () => {
  it('matches exact and base-language tags', () => {
    expect(resolveLocale(['am-ET', 'en'])).toBe('am');
    expect(resolveLocale(['fr-FR', 'en-US'])).toBe('en');
  });

  it('falls back when nothing matches', () => {
    expect(resolveLocale(['fr', 'de'])).toBe('en');
    expect(resolveLocale([], 'am')).toBe('am');
  });
});

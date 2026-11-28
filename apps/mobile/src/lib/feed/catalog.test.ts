import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { messages } from '@school/i18n';

type Tree = { [key: string]: string | Tree };

const SRC = join(__dirname, '..', '..');

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.(ts|tsx)$/.test(name) && !/\.test\.ts$/.test(name) ? [path] : [];
  });
}

function node(tree: Tree, key: string): string | Tree | undefined {
  let current: string | Tree | undefined = tree;
  for (const part of key.split('.')) {
    if (typeof current !== 'object' || !(part in current)) return undefined;
    current = current[part];
  }
  return current;
}

function leaves(tree: Tree, path: string): string[] {
  return Object.entries(tree).flatMap(([key, value]) => (typeof value === 'string' ? [`${path}${key}`] : leaves(value, `${path}${key}.`)));
}

describe('the feed catalog (mobile.feed.*)', () => {
  const exact = new Set<string>();
  const prefixes = new Set<string>();
  for (const file of sources(SRC)) {
    const text = readFileSync(file, 'utf8');
    // A key written out, or the start of one built from an enum (`mobile.feed.author.${role}`).
    for (const match of text.matchAll(/mobile\.feed\.[A-Za-z0-9_.]*[A-Za-z0-9_](\.\$\{)?/g)) {
      if (match[1]) prefixes.add(match[0].slice(0, -match[1].length + 1));
      else exact.add(match[0]);
    }
  }

  it('finds the keys of the code', () => {
    expect(exact.size).toBeGreaterThan(100);
    expect(prefixes.size).toBeGreaterThan(2);
  });

  it.each(['en', 'am'] as const)('has every key the code uses, in %s', (locale) => {
    const missing = [...exact].filter((key) => {
      const found = node(messages[locale] as Tree, key);
      // A group (`mobile.feed.author`) is only ever the front of a built key; it is checked below.
      return typeof found === 'undefined';
    });
    const prefixMissing = [...prefixes].filter((prefix) => typeof node(messages[locale] as Tree, prefix.replace(/\.$/, '')) !== 'object');
    expect(missing).toEqual([]);
    expect(prefixMissing).toEqual([]);
  });

  it.each(['en', 'am'] as const)('has no key the code does not use, in %s', (locale) => {
    const all = leaves((messages[locale].mobile as unknown as { feed: Tree }).feed, 'mobile.feed.');
    const dead = all.filter((key) => !exact.has(key) && ![...prefixes].some((prefix) => key.startsWith(prefix)));
    expect(dead).toEqual([]);
  });

  it('has the same keys in English and Amharic', () => {
    const en = leaves((messages.en.mobile as unknown as { feed: Tree }).feed, '');
    const am = leaves((messages.am.mobile as unknown as { feed: Tree }).feed, '');
    expect(am.sort()).toEqual(en.sort());
  });

  it.each(['en', 'am'] as const)('has no ICU plural or select in %s (Hermes has no Intl.PluralRules)', (locale) => {
    const offenders = leaves((messages[locale].mobile as unknown as { feed: Tree }).feed, 'mobile.feed.').filter((key) => {
      const text = node(messages[locale] as Tree, key);
      return typeof text === 'string' && /\{\s*\w+\s*,\s*(plural|selectordinal|select)/.test(text);
    });
    expect(offenders).toEqual([]);
  });
});

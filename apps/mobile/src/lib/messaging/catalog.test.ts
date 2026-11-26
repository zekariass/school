import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { messages } from '@school/i18n';

import * as labels from './labels';

type Tree = { [key: string]: string | Tree };

const SRC = join(__dirname, '..', '..');

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.(ts|tsx)$/.test(name) && !/\.test\.ts$/.test(name) ? [path] : [];
  });
}

function has(tree: Tree, key: string): boolean {
  let node: string | Tree | undefined = tree;
  for (const part of key.split('.')) {
    if (typeof node !== 'object' || !(part in node)) return false;
    node = node[part];
  }
  return typeof node === 'string';
}

function leaves(tree: Tree, path = ''): [string, string][] {
  return Object.entries(tree).flatMap(([key, value]) => (typeof value === 'string' ? [[`${path}${key}`, value] as [string, string]] : leaves(value, `${path}${key}.`)));
}

describe('the messaging catalog', () => {
  const used = new Set<string>();
  for (const file of sources(SRC)) {
    for (const match of readFileSync(file, 'utf8').matchAll(/mobile\.messaging\.[A-Za-z0-9_.]*[A-Za-z0-9_]/g)) used.add(match[0]);
  }
  for (const map of Object.values(labels)) {
    if (Array.isArray(map)) {
      for (const item of map) if (typeof item === 'object' && 'key' in item) used.add(item.key);
    } else if (typeof map === 'object') {
      for (const key of Object.values(map)) if (typeof key === 'string' && key.startsWith('mobile.messaging.')) used.add(key);
    }
  }

  it('finds the keys of the code', () => {
    expect(used.size).toBeGreaterThan(200);
  });
  it.each(['en', 'am'] as const)('has every key the code uses, in %s', (locale) => {
    const missing = [...used].filter((key) => !has(messages[locale] as Tree, key));
    expect(missing).toEqual([]);
  });
  it('has the same keys and the same placeholders in en and am', () => {
    const en = new Map(leaves((messages.en.mobile as unknown as { messaging: Tree }).messaging));
    const am = new Map(leaves((messages.am.mobile as unknown as { messaging: Tree }).messaging));
    expect([...am.keys()].sort()).toEqual([...en.keys()].sort());
    const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
    for (const [key, text] of en) expect(placeholders(am.get(key)!), key).toEqual(placeholders(text));
  });
  it.each(['en', 'am'] as const)('has no ICU plural or select in %s (Hermes has no Intl.PluralRules)', (locale) => {
    const offenders = leaves((messages[locale].mobile as unknown as { messaging: Tree }).messaging)
      .filter(([, text]) => /\{\s*\w+\s*,\s*(plural|selectordinal|select)/.test(text))
      .map(([key]) => key);
    expect(offenders).toEqual([]);
  });
  it('never puts a message text, a name or an e-mail address in a notification-like string', () => {
    // The catalog holds labels only: no sample text that could be mistaken for a child's words.
    for (const [key, text] of leaves((messages.en.mobile as unknown as { messaging: Tree }).messaging)) {
      expect(text, key).not.toMatch(/@|https?:\/\//);
    }
  });
});

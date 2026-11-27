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

describe('the portfolio catalog', () => {
  const used = new Set<string>();
  for (const file of sources(SRC)) {
    for (const match of readFileSync(file, 'utf8').matchAll(/mobile\.portfolio\.[A-Za-z0-9_.]*[A-Za-z0-9_]/g)) used.add(match[0]);
  }
  // Keys built from an enum are written out in labels.ts (and the review sheet), so the regex above finds them too; this
  // also checks the maps of labels.ts value by value.
  for (const map of Object.values(labels)) {
    if (typeof map === 'string') used.add(map);
    else for (const key of Object.values(map)) used.add(key);
  }

  it('finds the keys of the code', () => {
    expect(used.size).toBeGreaterThan(150);
  });
  it.each(['en', 'am'] as const)('has every key the code uses, in %s', (locale) => {
    const missing = [...used].filter((key) => !has(messages[locale] as Tree, key));
    expect(missing).toEqual([]);
  });
  it('has no key the code does not use (a dead message is a translation to review for nothing)', () => {
    const all = leaves((messages.en.mobile as unknown as { portfolio: Tree }).portfolio, 'mobile.portfolio.').map(([key]) => key);
    expect(all.filter((key) => !used.has(key))).toEqual([]);
  });
  it('has the same keys and the same placeholders in en and am', () => {
    const en = new Map(leaves((messages.en.mobile as unknown as { portfolio: Tree }).portfolio));
    const am = new Map(leaves((messages.am.mobile as unknown as { portfolio: Tree }).portfolio));
    expect([...am.keys()].sort()).toEqual([...en.keys()].sort());
    const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
    for (const [key, text] of en) expect(placeholders(am.get(key)!), key).toEqual(placeholders(text));
  });
  it.each(['en', 'am'] as const)('has no ICU plural or select in %s (Hermes has no Intl.PluralRules)', (locale) => {
    const offenders = leaves((messages[locale].mobile as unknown as { portfolio: Tree }).portfolio)
      .filter(([, text]) => /\{\s*\w+\s*,\s*(plural|selectordinal|select)/.test(text))
      .map(([key]) => key);
    expect(offenders).toEqual([]);
  });
  it('never holds sample text that could be mistaken for a child\'s words, an address or a link', () => {
    for (const [key, text] of leaves((messages.en.mobile as unknown as { portfolio: Tree }).portfolio)) {
      expect(text, key).not.toMatch(/@|https?:\/\//);
    }
  });
});

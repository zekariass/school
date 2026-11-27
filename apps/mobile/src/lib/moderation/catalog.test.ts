import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { messages } from '@school/i18n';

import { RETRY_LABEL } from './failure';
import { INTRO_LABEL, REASON_LABEL } from './reasons';

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

const moderation = (locale: 'en' | 'am') => (messages[locale].mobile as unknown as { moderation: Tree }).moderation;

describe('the moderation catalog', () => {
  const used = new Set<string>();
  for (const file of sources(SRC)) {
    for (const match of readFileSync(file, 'utf8').matchAll(/mobile\.moderation\.[A-Za-z0-9_.]*[A-Za-z0-9_]/g)) used.add(match[0]);
  }
  for (const map of [REASON_LABEL, INTRO_LABEL, RETRY_LABEL]) for (const key of Object.values(map)) used.add(key);

  it('finds the keys of the code', () => {
    expect(used.size).toBeGreaterThan(20);
  });
  it.each(['en', 'am'] as const)('has every key the code uses, in %s', (locale) => {
    expect([...used].filter((key) => !has(messages[locale] as Tree, key))).toEqual([]);
  });
  it.each(['en', 'am'] as const)('has no key the code does not use, in %s', (locale) => {
    const dead = leaves(moderation(locale))
      .map(([key]) => `mobile.moderation.${key}`)
      .filter((key) => !used.has(key));
    expect(dead).toEqual([]);
  });
  it('has the same keys and placeholders in en and am', () => {
    const en = new Map(leaves(moderation('en')));
    const am = new Map(leaves(moderation('am')));
    expect([...am.keys()].sort()).toEqual([...en.keys()].sort());
    const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
    for (const [key, text] of en) expect(placeholders(am.get(key)!), key).toEqual(placeholders(text));
  });
  it.each(['en', 'am'] as const)('has no ICU plural or select in %s (Hermes has no Intl.PluralRules)', (locale) => {
    const offenders = leaves(moderation(locale)).filter(([, text]) => /\{\s*\w+\s*,\s*(plural|selectordinal|select)/.test(text));
    expect(offenders).toEqual([]);
  });
  it('writes no safety promise of its own: the texts name no outcome, authority or helpline', () => {
    // What happens after a report, and whom to call, is never composed here: the thank-you is generic and the safety line is the API's.
    for (const [key, text] of leaves(moderation('en'))) {
      expect(text, key).not.toMatch(/police|helpline|hotline|\b\d{3,}\b|will (be )?(look|review|investigat|punish|remov)/i);
    }
  });
  it('has no leftover keys of the old per-module report sheets', () => {
    const feed = messages.en.mobile.feed as unknown as Record<string, unknown>;
    const messaging = messages.en.mobile.messaging as unknown as Record<string, unknown>;
    expect(feed.report).toBeUndefined();
    expect(messaging.report).toBeUndefined();
  });
});

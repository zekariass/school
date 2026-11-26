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

describe('the announcements catalog', () => {
  const used = new Set<string>();
  for (const file of sources(SRC)) {
    for (const match of readFileSync(file, 'utf8').matchAll(/mobile\.announcements\.[A-Za-z0-9_.]*[A-Za-z0-9_]/g)) used.add(match[0]);
  }
  // Keys built from an enum are written out in labels.ts.
  for (const map of Object.values(labels)) for (const key of Object.values(map)) used.add(key);

  it('finds the keys of the code', () => {
    expect(used.size).toBeGreaterThan(150);
  });
  it.each(['en', 'am'] as const)('has every key the code uses, in %s', (locale) => {
    const missing = [...used].filter((key) => !has(messages[locale] as Tree, key));
    expect(missing).toEqual([]);
  });
  it.each(['en', 'am'] as const)('has no ICU plural or select in %s (Hermes has no Intl.PluralRules)', (locale) => {
    const offenders: string[] = [];
    const walk = (tree: Tree, path: string) => {
      for (const [key, value] of Object.entries(tree)) {
        if (typeof value === 'string') {
          if (/\{\s*\w+\s*,\s*(plural|selectordinal|select)/.test(value)) offenders.push(`${path}${key}`);
        } else walk(value, `${path}${key}.`);
      }
    };
    walk((messages[locale].mobile as unknown as { announcements: Tree }).announcements, 'mobile.announcements.');
    expect(offenders).toEqual([]);
  });
});

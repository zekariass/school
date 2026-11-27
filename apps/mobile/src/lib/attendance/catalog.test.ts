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

/** Keys the screens build at run time (a template literal), so the scan below cannot see them. */
const RUN_TIME_KEYS = [
  'denied',
  'disabled',
  'featureOff',
  'notFound',
].flatMap((refusal) => [`mobile.attendance.staff.${refusal}.title`, `mobile.attendance.staff.${refusal}.body`]);

describe('the attendance catalog (period rolls and staff attendance)', () => {
  const used = new Set<string>(RUN_TIME_KEYS);
  for (const file of sources(SRC)) {
    // A key is a literal when nothing continues it: `mobile.attendance.slot.${x}` is a template, not a key.
    for (const match of readFileSync(file, 'utf8').matchAll(/mobile\.attendance\.[A-Za-z0-9_.]*[A-Za-z0-9_](?![.\w$])/g)) used.add(match[0]);
  }

  it('finds the keys of the code', () => {
    expect(used.size).toBeGreaterThan(80);
    expect([...used].some((key) => key.startsWith('mobile.attendance.staff.'))).toBe(true);
    expect([...used].some((key) => key.startsWith('mobile.attendance.period.'))).toBe(true);
  });

  it.each(['en', 'am'] as const)('has every key the code uses, in %s', (locale) => {
    expect([...used].filter((key) => !has(messages[locale] as Tree, key))).toEqual([]);
  });

  it('has the same staff and period keys with the same placeholders in en and am', () => {
    for (const part of ['period', 'staff'] as const) {
      const en = new Map(leaves((messages.en.mobile as unknown as { attendance: Record<string, Tree> }).attendance[part]!));
      const am = new Map(leaves((messages.am.mobile as unknown as { attendance: Record<string, Tree> }).attendance[part]!));
      expect([...am.keys()].sort()).toEqual([...en.keys()].sort());
      for (const [key, text] of en) {
        const placeholders = (value: string) => [...value.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
        expect(placeholders(am.get(key) ?? '')).toEqual(placeholders(text));
      }
    }
  });

  it('has no dead staff or period message (a translation to review for nothing)', () => {
    const attendance = (messages.en.mobile as unknown as { attendance: Record<string, Tree> }).attendance;
    const all = [...leaves(attendance.period!, 'mobile.attendance.period.'), ...leaves(attendance.staff!, 'mobile.attendance.staff.')].map(([key]) => key);
    expect(all.filter((key) => !used.has(key))).toEqual([]);
  });
});

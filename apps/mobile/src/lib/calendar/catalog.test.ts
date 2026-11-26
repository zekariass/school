import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { messages } from '@school/i18n';

import { SOURCE_CHIPS, itemStyle } from './item-style';
import { rsvpLabelKey } from './event-model';

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

describe('the calendar catalog', () => {
  const used = new Set<string>();
  for (const file of sources(SRC)) {
    for (const match of readFileSync(file, 'utf8').matchAll(/mobile\.calendar\.[A-Za-z0-9_.]*[A-Za-z0-9_]/g)) used.add(match[0]);
  }
  // Keys built at run time are written out here.
  for (const source of ['HOLIDAY', 'ACADEMIC_PERIOD', 'PERSONAL_EVENT', 'EVENT']) {
    for (const kind of ['YEAR_START', 'YEAR_END', 'PERIOD_START', 'PERIOD_END', 'PUBLIC', 'SCHOOL_EVENT', 'CLASS_EVENT', 'EXAM', 'PARENT_MEETING', 'OTHER']) used.add(itemStyle({ source, kind }).labelKey);
  }
  for (const chip of SOURCE_CHIPS) used.add(chip.labelKey);
  for (const status of ['YES', 'NO', 'MAYBE', 'PENDING']) used.add(rsvpLabelKey(status));
  for (const scope of ['SCHOOL', 'CAMPUS', 'GRADE_LEVEL', 'CLASS', 'PERSONAL', 'INVITEES']) used.add(`mobile.calendar.audience.${scope}`);
  for (const kind of ['school', 'campus', 'grade', 'class', 'meeting', 'personal']) used.add(`mobile.calendar.form.kind.${kind}`);
  for (const problem of ['interval', 'weekdaysNone', 'weekdayStart', 'monthDaysNone', 'monthDayStart', 'untilMissing', 'untilBeforeStart', 'untilTooFar', 'count']) {
    used.add(`mobile.calendar.form.problem.repeat.${problem}`);
  }

  it('finds the keys of the code', () => {
    expect(used.size).toBeGreaterThan(150);
  });
  it.each(['en', 'am'] as const)('has every key the code uses, in %s', (locale) => {
    const missing = [...used].filter((key) => !has(messages[locale] as Tree, key) && !isNamespace(messages[locale] as Tree, key));
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
    walk((messages[locale].mobile as unknown as { calendar: Tree }).calendar, 'mobile.calendar.');
    expect(offenders).toEqual([]);
  });
  it('has no unfinished entries: every Amharic message has text', () => {
    const empty: string[] = [];
    const walk = (tree: Tree, path: string) => {
      for (const [key, value] of Object.entries(tree)) {
        if (typeof value === 'string') {
          if (!value.trim()) empty.push(`${path}${key}`);
        } else walk(value, `${path}${key}.`);
      }
    };
    walk((messages.am.mobile as unknown as { calendar: Tree }).calendar, 'mobile.calendar.');
    expect(empty).toEqual([]);
  });
});

/** A prefix of keys built at run time (`mobile.calendar.audience`) is fine when it names a branch of the catalog. */
function isNamespace(tree: Tree, key: string): boolean {
  let node: string | Tree | undefined = tree;
  for (const part of key.split('.')) {
    if (typeof node !== 'object' || !(part in node)) return false;
    node = node[part];
  }
  return typeof node === 'object';
}

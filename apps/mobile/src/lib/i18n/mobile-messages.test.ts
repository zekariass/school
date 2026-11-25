import { describe, expect, it } from 'vitest';

import { messages } from '@school/i18n';

type Tree = { [key: string]: string | Tree };

function values(tree: Tree, path = ''): [string, string][] {
  return Object.entries(tree).flatMap(([key, value]) =>
    typeof value === 'string' ? [[`${path}${key}`, value] as [string, string]] : values(value, `${path}${key}.`),
  );
}

describe('mobile catalog', () => {
  // Hermes has no Intl.PluralRules, so formatting an ICU plural throws FORMATTING_ERROR on a device.
  it.each(['en', 'am'] as const)('uses no ICU plural or select in %s', (locale) => {
    const offenders = values(messages[locale].mobile as Tree).filter(([, text]) => /\{\s*\w+\s*,\s*(plural|selectordinal)/.test(text));
    expect(offenders).toEqual([]);
  });
});

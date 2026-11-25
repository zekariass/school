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

  // The guardian screens share these namespaces with the web, and they run on Hermes too.
  it.each(['en', 'am'] as const)('keeps the shared guardian namespaces (family, invite, consent) plural-free in %s', (locale) => {
    const shared = { family: messages[locale].family, invite: messages[locale].invite, consent: messages[locale].consent };
    const offenders = values(shared as Tree).filter(([, text]) => /\{\s*\w+\s*,\s*(plural|selectordinal|select)/.test(text));
    expect(offenders).toEqual([]);
  });
});

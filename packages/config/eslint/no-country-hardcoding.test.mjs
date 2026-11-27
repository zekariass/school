import { Linter } from 'eslint';
import { describe, expect, it } from 'vitest';

import { noCountryHardcoding } from './no-country-hardcoding.mjs';

const linter = new Linter({ configType: 'flat' });

function violations(code, filename = 'src/example.js') {
  return linter
    .verify(code, [{ languageOptions: { ecmaVersion: 'latest', sourceType: 'module' } }, ...noCountryHardcoding], filename)
    .filter((m) => m.ruleId === 'no-restricted-syntax')
    .map((m) => m.message);
}

describe('no-country-hardcoding (D42)', () => {
  it.each([
    ["const country = 'ET';"],
    ['const country = "ET";'],
    ["const zone = 'Africa/Addis_Ababa';"],
    ['const zone = `Africa/Addis_Ababa`;'],
    ["const currency = 'ETB';"],
    ["const prefix = '+251';"],
    ["const phone = '+251911234567';"],
    ["const languages = ['en', 'am'];"],
    ['const languages = ["am", "en"];'],
    ["const languages = ['en', 'am', 'om'];"],
    ["const ethiopian = locale === 'am';"],
    ['const other = locale !== "am";'],
    ['const props = { country: "ET" };'],
    ['const fn = (country = "ET") => country;'],
    ['const el = <Field country="ET" />;'],
  ])('flags %s', (code) => {
    const jsx = { languageOptions: { ecmaVersion: 'latest', sourceType: 'module', parserOptions: { ecmaFeatures: { jsx: true } } } };
    const found = linter
      .verify(code, [jsx, ...noCountryHardcoding], 'src/example.js')
      .filter((m) => m.ruleId === 'no-restricted-syntax');
    expect(found.length).toBeGreaterThan(0);
  });

  it.each([
    ["// the zone is 'Africa/Addis_Ababa' and the code 'ET', +251, ETB, ['en', 'am']"],
    ['/* locale === "am" */ const a = 1;'],
    ["const word = 'ETA';"],
    ["const one = ['en'];"],
    ["const catalog = { am: 1 };"],
    ["const language = 'am';"],
    ["const country = code;"],
    ["const m = 'MET';"],
  ])('allows %s', (code) => {
    expect(violations(code)).toEqual([]);
  });

  it.each([
    'src/zone.test.js',
    'src/zone.spec.js',
    'src/lib/fixtures/payloads.js',
    'src/lib/announcements/fixtures.js',
    'src/generated/schema.js',
    'src/__tests__/a.js',
  ])('does not apply to %s', (filename) => {
    expect(violations("const country = 'ET'; const l = ['en', 'am'];", filename)).toEqual([]);
  });
});

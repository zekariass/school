// D42: no country is hard-coded in client code. Country, time zone, calendars, languages, currency and phone rules
// come from the API (GET /meta, GET /me, the school summary). This is the web/mobile/packages counterpart of the
// backend's NoCountryHardcodingTest. It is an AST rule, so comments are never matched.
//
// Not covered: tests, fixtures and generated code (turned off below), packages/calendar (the conversion algorithm)
// and packages/i18n (the list of UI catalogs), which switch it off in their own eslint.config.mjs.

const MESSAGE =
  'No country, zone, currency or language list in code (D42): read it from the API (/meta, /me, the school summary).';

export const noCountryHardcodingSelectors = [
  // Country code, currency and zone of one country.
  "Literal[value='ET']",
  "Literal[value='ETB']",
  'Literal[value=/Africa\\/Addis_Ababa/]',
  'Literal[value=/^\\+251/]',
  "TemplateElement[value.cooked='ET']",
  'TemplateElement[value.cooked=/Africa\\/Addis_Ababa|\\+251/]',
  // A literal list of the two shipped UI languages, and a branch on one of them.
  "ArrayExpression:has(> Literal[value='en']):has(> Literal[value='am'])",
  "BinaryExpression[operator=/^[!=]==?$/] > Literal[value='am']",
];

/** Where the rule does not apply: tests, fixtures and generated code. */
export const exemptFiles = ['**/*.test.*', '**/*.spec.*', '**/fixtures/**', '**/fixtures.*', '**/generated/**', '**/__tests__/**'];

export const noCountryHardcoding = [
  {
    rules: {
      'no-restricted-syntax': ['error', ...noCountryHardcodingSelectors.map((selector) => ({ selector, message: MESSAGE }))],
    },
  },
  { files: exemptFiles, rules: { 'no-restricted-syntax': 'off' } },
];

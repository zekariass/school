// https://docs.expo.dev/guides/using-eslint/
const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');
// D42: no country, zone, currency or language list in code.
const { noCountryHardcoding } = require('@school/config/eslint/no-country-hardcoding');

module.exports = defineConfig([
  expoConfig,
  ...noCountryHardcoding,
  {
    ignores: ['dist/*'],
  },
]);

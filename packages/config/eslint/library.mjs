// Shared flat ESLint config for framework-agnostic workspace packages.
// Apps keep their framework presets (eslint-config-next, eslint-config-expo).
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import { noCountryHardcoding } from './no-country-hardcoding.mjs';

export default tseslint.config(
  { ignores: ['dist/**', 'src/generated/**', 'generated/**', 'coverage/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...noCountryHardcoding,
  {
    rules: {
      '@typescript-eslint/consistent-type-imports': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
);

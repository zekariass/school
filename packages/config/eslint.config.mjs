import library from './eslint/library.mjs';

// The guard's own test names the forbidden literals on purpose.
export default [...library, { files: ['eslint/no-country-hardcoding.test.mjs'], rules: { 'no-restricted-syntax': 'off' } }];

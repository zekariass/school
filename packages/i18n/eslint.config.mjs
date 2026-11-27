import library from '@school/config/eslint/library';

// This package IS the list of UI catalogs the apps ship text for (D42: it stays code).
export default [...library, { rules: { 'no-restricted-syntax': 'off' } }];

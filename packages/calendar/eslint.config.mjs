import library from '@school/config/eslint/library';

// The calendar algorithm and the Ethiopian month and weekday names live here (D42: they stay code).
export default [...library, { rules: { 'no-restricted-syntax': 'off' } }];

import { describe, expect, it } from 'vitest';

import { emergencyCountry, emergencyText } from './emergency';

const me = {
  country: { code: 'AA' },
  memberships: [
    { schoolId: 's1', countryCode: 'BB' },
    { schoolId: 's2', countryCode: 'CC' },
  ],
};

describe('emergencyCountry', () => {
  it("is the school's country when the person belongs to the school", () => {
    expect(emergencyCountry(me, 's2')).toBe('CC');
  });
  it("falls back to the person's own country (home, else platform default, as /me says)", () => {
    expect(emergencyCountry(me, null)).toBe('AA');
    expect(emergencyCountry(me, 'unknown-school')).toBe('AA');
    expect(emergencyCountry({ country: { code: 'AA' } }, 's1')).toBe('AA');
  });
  it('is null before /me is known: no country is ever guessed', () => {
    expect(emergencyCountry(null, 's1')).toBeNull();
    expect(emergencyCountry({}, null)).toBeNull();
  });
});

describe('emergencyText', () => {
  it('is only what the API sent', () => {
    expect(emergencyText({ text: '  Call the local number.  ' })).toBe('Call the local number.');
  });
  it('is nothing for a missing, blank or absent answer (404 becomes null upstream)', () => {
    expect(emergencyText(null)).toBeNull();
    expect(emergencyText(undefined)).toBeNull();
    expect(emergencyText({})).toBeNull();
    expect(emergencyText({ text: '   ' })).toBeNull();
  });
});

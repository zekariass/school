import { describe, expect, it } from 'vitest';

import { classifyScan, formatTextCode, isCompleteTextCode, normalizeTextCode, toCodeCard } from './login-code';

describe('text code formatting', () => {
  it('formats a typed code as XXXXX-XXXXX', () => {
    expect(formatTextCode('ahr4pa4ws9')).toBe('AHR4P-A4WS9');
  });

  it('accepts any case, spaces and a hyphen anywhere', () => {
    expect(formatTextCode(' ahr4p-a4ws9 ')).toBe('AHR4P-A4WS9');
    expect(formatTextCode('Ahr 4pA 4ws9')).toBe('AHR4P-A4WS9');
    expect(normalizeTextCode('ahr4p a4ws9')).toBe('AHR4PA4WS9');
  });

  it('formats as the child types and drops extra characters', () => {
    expect(formatTextCode('ahr')).toBe('AHR');
    expect(formatTextCode('ahr4p')).toBe('AHR4P');
    expect(formatTextCode('ahr4pa')).toBe('AHR4P-A');
    expect(formatTextCode('ahr4pa4ws9zzz')).toBe('AHR4P-A4WS9');
    expect(formatTextCode('')).toBe('');
  });

  it('knows when the code is complete and made of valid characters', () => {
    expect(isCompleteTextCode('AHR4P-A4WS9')).toBe(true);
    expect(isCompleteTextCode('ahr4pa4ws9')).toBe(true);
    expect(isCompleteTextCode('AHR4P-A4WS')).toBe(false);
    // 0, O, 1, I and L are not in the alphabet.
    expect(isCompleteTextCode('AHR4P-A4WS0')).toBe(false);
    expect(isCompleteTextCode('AHR4P-A4WSL')).toBe(false);
  });
});

describe('classifyScan', () => {
  const token = 'Zk3-_aB9xQ2mPq7RtYw1Lc';

  it('reads the bare QR token as it is (case-sensitive)', () => {
    expect(token).toHaveLength(22);
    expect(classifyScan(token)).toEqual({ kind: 'qr', code: token });
    expect(classifyScan(`  ${token}\n`)).toEqual({ kind: 'qr', code: token });
  });

  it('also accepts a QR that carries the text code', () => {
    expect(classifyScan('ahr4p-a4ws9')).toEqual({ kind: 'text', code: 'AHR4P-A4WS9' });
  });

  it('ignores anything that is not a class code', () => {
    expect(classifyScan('https://example.com/join/abc')).toEqual({ kind: 'unknown' });
    expect(classifyScan('WIFI:S:home;T:WPA;P:secret;;')).toEqual({ kind: 'unknown' });
    expect(classifyScan('')).toEqual({ kind: 'unknown' });
    expect(classifyScan(null)).toEqual({ kind: 'unknown' });
    expect(classifyScan(token.slice(1))).toEqual({ kind: 'unknown' });
  });
});

describe('toCodeCard', () => {
  it('shows the text code as printed and keeps the QR token untouched', () => {
    expect(toCodeCard({ displayName: 'Abebe Kebede', qrToken: 'Zk3-_aB9xQ2mPq7RtYw1Lc', textCode: 'ahr4pa4ws9', expiresAt: '2027-01-01T00:00:00Z' })).toEqual({
      displayName: 'Abebe Kebede',
      qrToken: 'Zk3-_aB9xQ2mPq7RtYw1Lc',
      textCode: 'AHR4P-A4WS9',
      expiresAt: '2027-01-01T00:00:00Z',
    });
  });

  it('copes with a response that has only one kind of code', () => {
    expect(toCodeCard({ displayName: 'A', textCode: 'AHR4PA4WS9' })).toMatchObject({ qrToken: null, textCode: 'AHR4P-A4WS9', expiresAt: null });
  });
});

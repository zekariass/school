import { describe, expect, it } from 'vitest';
import {
  classifyLoginScan,
  formatLoginTextCode,
  isCompleteLoginTextCode,
  isLoginQrToken,
  loginCodeToSend,
  normalizeLoginTextCode,
} from './login-code';

const TOKEN = 'abcDEF123_-abcDEF123_-';

describe('text codes', () => {
  it('are normalized the way the child types them', () => {
    expect(normalizeLoginTextCode('ahr4p a4ws9')).toBe('AHR4PA4WS9');
    expect(normalizeLoginTextCode('ahr4p-a4ws9-extra')).toBe('AHR4PA4WS9');
    expect(formatLoginTextCode('ahr4pa4ws9')).toBe('AHR4P-A4WS9');
    expect(formatLoginTextCode('ahr4')).toBe('AHR4');
  });

  it('are complete only with 10 characters of the alphabet (no 0, O, 1, I, L)', () => {
    expect(isCompleteLoginTextCode('AHR4P-A4WS9')).toBe(true);
    expect(isCompleteLoginTextCode('ahr4pa4ws9')).toBe(true);
    expect(isCompleteLoginTextCode('AHR4P-A4WS')).toBe(false);
    expect(isCompleteLoginTextCode('AHR4P-A4WS0')).toBe(false);
    expect(isCompleteLoginTextCode('AHR4P-A4WSO')).toBe(false);
    expect(isCompleteLoginTextCode('')).toBe(false);
  });
});

describe('classifyLoginScan', () => {
  it('reads the bare token a QR code carries, case-sensitive and untouched', () => {
    expect(isLoginQrToken(TOKEN)).toBe(true);
    expect(classifyLoginScan(` ${TOKEN}\n`)).toEqual({ kind: 'qr', code: TOKEN });
  });

  it('also reads a text code on a printout', () => {
    expect(classifyLoginScan('ahr4pa4ws9')).toEqual({ kind: 'text', code: 'AHR4P-A4WS9' });
  });

  it('ignores everything else: links, wifi codes, long texts, tokens of the wrong length', () => {
    expect(classifyLoginScan('https://example.com/?c=' + TOKEN)).toEqual({ kind: 'unknown' });
    expect(classifyLoginScan('WIFI:S:home;T:WPA;P:secret;;')).toEqual({ kind: 'unknown' });
    expect(classifyLoginScan(TOKEN + 'x')).toEqual({ kind: 'unknown' });
    expect(classifyLoginScan(TOKEN.slice(1))).toEqual({ kind: 'unknown' });
    expect(classifyLoginScan('')).toEqual({ kind: 'unknown' });
    expect(classifyLoginScan(null)).toEqual({ kind: 'unknown' });
    expect(classifyLoginScan(undefined)).toEqual({ kind: 'unknown' });
  });
});

describe('loginCodeToSend', () => {
  it('sends the token as it is and a text code in its canonical form, and nothing else', () => {
    expect(loginCodeToSend(TOKEN)).toBe(TOKEN);
    expect(loginCodeToSend('ahr4p a4ws9')).toBe('AHR4P-A4WS9');
    expect(loginCodeToSend('hello')).toBeNull();
    expect(loginCodeToSend(null)).toBeNull();
  });
});

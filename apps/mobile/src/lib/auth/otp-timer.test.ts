import { describe, expect, it } from 'vitest';

import { formatCountdown, intParam, sanitizeCode, secondsLeft } from './otp-timer';

describe('otp timers', () => {
  it('counts whole seconds up and never below zero', () => {
    expect(secondsLeft(10_000, 9_001)).toBe(1);
    expect(secondsLeft(10_000, 9_000)).toBe(1);
    expect(secondsLeft(10_000, 10_000)).toBe(0);
    expect(secondsLeft(10_000, 12_000)).toBe(0);
  });

  it('formats m:ss', () => {
    expect(formatCountdown(300)).toBe('5:00');
    expect(formatCountdown(65)).toBe('1:05');
    expect(formatCountdown(0)).toBe('0:00');
    expect(formatCountdown(-3)).toBe('0:00');
  });

  it('keeps only digits up to the code length', () => {
    expect(sanitizeCode('123 456')).toBe('123456');
    expect(sanitizeCode('12-34-56-78')).toBe('123456');
    expect(sanitizeCode('abc')).toBe('');
    expect(sanitizeCode('12345678', 8)).toBe('12345678');
  });

  it('reads integer route params', () => {
    expect(intParam('6', 1)).toBe(6);
    expect(intParam(['300', '1'], 1)).toBe(300);
    expect(intParam(undefined, 7)).toBe(7);
    expect(intParam('x', 7)).toBe(7);
    expect(intParam('0', 7)).toBe(7);
  });
});

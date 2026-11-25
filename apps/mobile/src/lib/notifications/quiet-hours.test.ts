import { describe, expect, it } from 'vitest';

import { isValidTime, normalizeTime, quietHoursPayload } from './quiet-hours';

describe('normalizeTime', () => {
  it('accepts common ways of typing a time', () => {
    expect(normalizeTime('22:00')).toBe('22:00');
    expect(normalizeTime('6:30')).toBe('06:30');
    expect(normalizeTime('2200')).toBe('22:00');
    expect(normalizeTime('630')).toBe('06:30');
    expect(normalizeTime('22')).toBe('22:00');
    expect(normalizeTime(' 7 ')).toBe('07:00');
  });
  it('rejects what is not a time of day', () => {
    for (const bad of ['', '24:00', '12:60', '25', 'ab', '1:2', '12345', '22.30']) expect(normalizeTime(bad)).toBeNull();
  });
});

describe('isValidTime', () => {
  it('is strict HH:mm', () => {
    expect(isValidTime('00:00')).toBe(true);
    expect(isValidTime('23:59')).toBe(true);
    expect(isValidTime('7:00')).toBe(false);
  });
});

describe('quietHoursPayload', () => {
  it('clears with both empty', () => {
    expect(quietHoursPayload({ start: '', end: ' ' })).toEqual({ ok: true });
  });
  it('needs both times', () => {
    expect(quietHoursPayload({ start: '22:00', end: '' })).toEqual({ ok: false, reason: 'incomplete' });
  });
  it('rejects bad times', () => {
    expect(quietHoursPayload({ start: '22:00', end: '99:00' })).toEqual({ ok: false, reason: 'invalid' });
  });
  it('normalizes good ones (also across midnight)', () => {
    expect(quietHoursPayload({ start: '2200', end: '6:30' })).toEqual({ ok: true, quietHoursStart: '22:00', quietHoursEnd: '06:30' });
  });
});

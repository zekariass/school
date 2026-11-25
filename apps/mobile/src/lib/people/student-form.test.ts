import { describe, expect, it } from 'vitest';

import { formatDobInput, parseDob, validateStudentDraft } from './student-form';

const today = new Date(2026, 10, 25);

describe('date of birth input', () => {
  it('formats digits as they are typed', () => {
    expect(formatDobInput('2015')).toBe('2015');
    expect(formatDobInput('201503')).toBe('2015-03');
    expect(formatDobInput('20150324')).toBe('2015-03-24');
    expect(formatDobInput('2015-03-24999')).toBe('2015-03-24');
    expect(formatDobInput('abc')).toBe('');
  });

  it('accepts an empty value and real past dates', () => {
    expect(parseDob('', today)).toEqual({ ok: true, value: null });
    expect(parseDob('2015-03-24', today)).toEqual({ ok: true, value: '2015-03-24' });
    expect(parseDob('2026-11-25', today).ok).toBe(true);
  });

  it('rejects impossible, future and implausible dates', () => {
    expect(parseDob('2015-02-30', today)).toMatchObject({ ok: false, error: 'format' });
    expect(parseDob('2015-3-4', today)).toMatchObject({ ok: false, error: 'format' });
    expect(parseDob('2026-11-26', today)).toMatchObject({ ok: false, error: 'future' });
    expect(parseDob('1900-01-01', today)).toMatchObject({ ok: false, error: 'tooOld' });
  });
});

describe('validateStudentDraft', () => {
  it('requires a first name only', () => {
    expect(validateStudentDraft({ givenName: ' ', fatherName: '', dateOfBirth: '' }, today).errors).toEqual({ givenName: 'required' });
    const ok = validateStudentDraft({ givenName: 'Abebe', fatherName: 'Kebede', dateOfBirth: '2015-03-24' }, today);
    expect(ok.errors).toEqual({});
    expect(ok.dateOfBirth).toBe('2015-03-24');
  });

  it('reports every problem at once', () => {
    const result = validateStudentDraft({ givenName: 'x'.repeat(101), fatherName: 'y'.repeat(101), dateOfBirth: '2030-01-01' }, today);
    expect(result.errors).toEqual({ givenName: 'tooLong', fatherName: 'tooLong', dateOfBirth: 'future' });
  });
});

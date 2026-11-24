import { messages } from '@school/i18n';
import { describe, expect, it } from 'vitest';

import {
  emailSchema,
  optionalPersonNameSchema,
  otpCodeSchema,
  passwordSchema,
  personNameSchema,
  phoneSchema,
  toFieldErrors,
  z,
} from './index';

function errorKey(schema: z.ZodType, value: unknown): string | undefined {
  const result = schema.safeParse(value);
  return result.success ? undefined : toFieldErrors(result.error)['']?.key;
}

describe('phoneSchema', () => {
  it('normalizes Ethiopian local formats to E.164', () => {
    const et = phoneSchema('ET');
    expect(et.parse('0911 23 45 67')).toBe('+251911234567');
    expect(et.parse('0711234567')).toBe('+251711234567');
    expect(et.parse('+251 91 123 4567')).toBe('+251911234567');
  });

  it('uses the given default country', () => {
    expect(phoneSchema('KE').parse('0712 345678')).toBe('+254712345678');
  });

  it('rejects invalid numbers with an i18n key', () => {
    expect(errorKey(phoneSchema('ET'), '12345')).toBe('validation.phone');
    expect(errorKey(phoneSchema('ET'), '   ')).toBe('validation.required');
  });
});

describe('emailSchema', () => {
  it('trims and lowercases', () => {
    expect(emailSchema.parse('  Teacher@Example.ORG ')).toBe('teacher@example.org');
  });

  it('rejects invalid email', () => {
    expect(errorKey(emailSchema, 'not-an-email')).toBe('validation.email');
    expect(errorKey(emailSchema, '')).toBe('validation.required');
  });
});

describe('otp, password and names', () => {
  it('validates a 6-digit code', () => {
    expect(otpCodeSchema.parse(' 123456 ')).toBe('123456');
    expect(errorKey(otpCodeSchema, '12a456')).toBe('validation.otp');
  });

  it('enforces password length with interpolation values', () => {
    const result = passwordSchema.safeParse('short');
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(toFieldErrors(result.error)['']).toEqual({ key: 'validation.passwordTooShort', values: { min: 10 } });
    }
  });

  it('trims names and treats blank optional names as absent', () => {
    expect(personNameSchema.parse('  Abebe ')).toBe('Abebe');
    expect(errorKey(personNameSchema, '  ')).toBe('validation.required');
    expect(optionalPersonNameSchema.parse('   ')).toBeUndefined();
  });

  it('reports errors per field path', () => {
    const form = z.object({ phone: phoneSchema('ET'), givenName: personNameSchema });
    const result = form.safeParse({ phone: '1', givenName: '' });
    expect(result.success).toBe(false);
    if (!result.success) {
      const errors = toFieldErrors(result.error);
      expect(errors.phone?.key).toBe('validation.phone');
      expect(errors.givenName?.key).toBe('validation.required');
    }
  });
});

describe('message keys', () => {
  it('every key used by the schemas exists in the catalogs', () => {
    const used = ['required', 'email', 'phone', 'otp', 'passwordTooShort', 'passwordTooLong', 'nameTooLong'];
    for (const key of used) expect(messages.en.validation).toHaveProperty(key);
  });
});

import { parsePhoneNumberFromString, type CountryCode } from 'libphonenumber-js';
import { z } from 'zod';

/**
 * Shared form schemas for web and mobile. The backend re-validates everything:
 * these schemas exist for fast, consistent client feedback, not for trust.
 *
 * Every error is an i18n message key from @school/i18n ("validation.*"), so UIs
 * render it in the user's language. Use `toFieldErrors` to get key + values.
 */

export const OTP_LENGTH = 6;
export const PASSWORD_MIN = 10;
export const PASSWORD_MAX = 128;
export const NAME_MAX = 100;

export const emailSchema = z
  .string({ error: 'validation.required' })
  .trim()
  .toLowerCase()
  .min(1, { error: 'validation.required' })
  .pipe(z.email({ error: 'validation.email' }));

/**
 * Phone number in any common local or international format, normalized to E.164.
 * `defaultCountry` is required (D42): the school's country, the user's, or the platform
 * default from `GET /meta`, never a constant. "0911 23 45 67" read in Ethiopia becomes
 * "+251911234567"; a number that starts with "+" keeps its own country. With no country (the API has not
 * said yet) only numbers with a "+" are valid.
 */
export function phoneSchema(defaultCountry: CountryCode | undefined) {
  return z
    .string({ error: 'validation.required' })
    .trim()
    .min(1, { error: 'validation.required' })
    .transform((value, ctx) => {
      const parsed = parsePhoneNumberFromString(value, defaultCountry);
      if (!parsed?.isValid()) {
        ctx.issues.push({ code: 'custom', message: 'validation.phone', input: value });
        return z.NEVER;
      }
      return parsed.number;
    });
}

export const otpCodeSchema = z
  .string({ error: 'validation.required' })
  .trim()
  .regex(new RegExp(`^\\d{${OTP_LENGTH}}$`), { error: 'validation.otp' });

export const passwordSchema = z
  .string({ error: 'validation.required' })
  .min(PASSWORD_MIN, { error: 'validation.passwordTooShort' })
  .max(PASSWORD_MAX, { error: 'validation.passwordTooLong' });

export const personNameSchema = z
  .string({ error: 'validation.required' })
  .trim()
  .min(1, { error: 'validation.required' })
  .max(NAME_MAX, { error: 'validation.nameTooLong' });

export const optionalPersonNameSchema = z
  .string()
  .trim()
  .max(NAME_MAX, { error: 'validation.nameTooLong' })
  .optional()
  .transform((value) => (value ? value : undefined));

/** Interpolation values each validation message expects (see packages/i18n/messages). */
const MESSAGE_VALUES: Record<string, Record<string, number>> = {
  'validation.otp': { length: OTP_LENGTH },
  'validation.passwordTooShort': { min: PASSWORD_MIN },
  'validation.passwordTooLong': { max: PASSWORD_MAX },
  'validation.nameTooLong': { max: NAME_MAX },
};

export interface FieldError {
  /** i18n message key, e.g. "validation.phone". */
  key: string;
  /** ICU interpolation values for the message. */
  values: Record<string, number>;
}

/** Flatten a ZodError into the first error per field path ("" for the root). */
export function toFieldErrors(error: z.ZodError): Record<string, FieldError> {
  const out: Record<string, FieldError> = {};
  for (const issue of error.issues) {
    const path = issue.path.join('.');
    if (out[path]) continue;
    out[path] = { key: issue.message, values: MESSAGE_VALUES[issue.message] ?? {} };
  }
  return out;
}

export { z };
export type { CountryCode };

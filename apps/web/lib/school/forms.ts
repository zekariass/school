/** Form validation helpers. Each returns an i18n key under `school.validation` or null when the value is fine. */

export type FieldError = "required" | "invalid" | "tooLong" | "future" | "order" | "contactOne" | "short" | null;

export const isBlank = (value: string | null | undefined): boolean => !value || value.trim() === "";

export function required(value: string | null | undefined): FieldError {
  return isBlank(value) ? "required" : null;
}

/** Student usernames: lower case, start with a letter, 3-31 of a-z 0-9 . _ - (as the API requires). */
const USERNAME = /^[a-z][a-z0-9._-]{2,30}$/;

export function validUsername(value: string): FieldError {
  if (isBlank(value)) return null; // optional
  return USERNAME.test(value.trim()) ? null : "invalid";
}

/** An ISO date (yyyy-mm-dd) that is not after `today` (a birth date cannot be in the future). */
export function pastDate(value: string, today: string): FieldError {
  if (isBlank(value)) return null;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return "invalid";
  return value > today ? "future" : null;
}

/** The end must come after the start (ISO dates compare as strings). */
export function dateOrder(start: string, end: string): FieldError {
  if (isBlank(start) || isBlank(end)) return null;
  return end > start ? null : "order";
}

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Staff are found or created by exactly one of phone or email. */
export function contact(phone: string, email: string): { phone: FieldError; email: FieldError } {
  const hasPhone = !isBlank(phone);
  const hasEmail = !isBlank(email);
  if (hasPhone === hasEmail) return { phone: "contactOne", email: "contactOne" };
  if (hasEmail && !EMAIL.test(email.trim())) return { phone: null, email: "invalid" };
  if (hasPhone && !/^\+?[0-9 ()-]{7,20}$/.test(phone.trim())) return { phone: "invalid", email: null };
  return { phone: null, email: null };
}

export function minLength(value: string, min: number): FieldError {
  return value.length >= min ? null : "short";
}

/** Today as yyyy-mm-dd in the browser's calendar day. */
export function todayIso(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

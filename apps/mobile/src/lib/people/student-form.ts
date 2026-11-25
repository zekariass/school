/** The "add a new student" form. */

export interface DobResult {
  ok: boolean;
  /** `YYYY-MM-DD` when given and valid, null when left empty. */
  value: string | null;
  error?: 'format' | 'future' | 'tooOld';
}

/** Digits only, formatted as they are typed: "20150304" -> "2015-03-04". */
export function formatDobInput(raw: string): string {
  const digits = raw.replace(/\D/g, '').slice(0, 8);
  if (digits.length <= 4) return digits;
  if (digits.length <= 6) return `${digits.slice(0, 4)}-${digits.slice(4)}`;
  return `${digits.slice(0, 4)}-${digits.slice(4, 6)}-${digits.slice(6)}`;
}

/** Empty is fine (optional); otherwise a real calendar date, not in the future, after 1950. */
export function parseDob(input: string, today: Date): DobResult {
  const text = input.trim();
  if (!text) return { ok: true, value: null };
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  if (!match) return { ok: false, value: null, error: 'format' };
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) {
    return { ok: false, value: null, error: 'format' };
  }
  const todayUtc = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  if (date.getTime() > todayUtc) return { ok: false, value: null, error: 'future' };
  if (year < 1950) return { ok: false, value: null, error: 'tooOld' };
  return { ok: true, value: text };
}

export interface StudentDraft {
  givenName: string;
  fatherName: string;
  dateOfBirth: string;
}

export interface StudentDraftErrors {
  givenName?: 'required' | 'tooLong';
  fatherName?: 'tooLong';
  dateOfBirth?: 'format' | 'future' | 'tooOld';
}

const MAX_NAME = 100;

export function validateStudentDraft(draft: StudentDraft, today: Date): { errors: StudentDraftErrors; dateOfBirth: string | null } {
  const errors: StudentDraftErrors = {};
  const given = draft.givenName.trim();
  if (!given) errors.givenName = 'required';
  else if (given.length > MAX_NAME) errors.givenName = 'tooLong';
  if (draft.fatherName.trim().length > MAX_NAME) errors.fatherName = 'tooLong';
  const dob = parseDob(draft.dateOfBirth, today);
  if (!dob.ok && dob.error) errors.dateOfBirth = dob.error;
  return { errors, dateOfBirth: dob.value };
}

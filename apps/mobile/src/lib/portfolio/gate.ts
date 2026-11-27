import { problemOf } from '@/lib/api-error';
import type { GateProblem } from '@/lib/feed/gate';

export { namedBlocked, withoutStudents, type GateProblem } from '@/lib/feed/gate';

/**
 * The consent gate (R-09) of the portfolio as the API reports it. Both are 422s that create or change nothing, so the screen
 * keeps everything the author typed and picked and explains what to do:
 * - `portfolio.media_consent_missing`: the child (or a tagged child) has no media-publication consent (`students`, with names
 *   only when the caller may see the class roster),
 * - `portfolio.media_confirmation_required`: files with nobody tagged need the creator's explicit statement.
 */
export const CONSENT_MISSING = 'portfolio.media_consent_missing';
export const CONFIRMATION_REQUIRED = 'portfolio.media_confirmation_required';

/** The gate problem in a failed call, or null when the failure is anything else. */
export function gateProblemOf(error: unknown): GateProblem | null {
  const problem = problemOf(error);
  if (!problem) return null;
  if (problem.code === CONFIRMATION_REQUIRED) return { kind: 'confirmation_required' };
  if (problem.code !== CONSENT_MISSING) return null;
  const raw = (problem as unknown as { students?: unknown }).students;
  const students: { studentId: string; displayName: string | null }[] = [];
  if (Array.isArray(raw)) {
    for (const entry of raw) {
      if (typeof entry !== 'object' || entry === null) continue;
      const record = entry as { studentId?: unknown; displayName?: unknown };
      if (typeof record.studentId !== 'string') continue;
      students.push({
        studentId: record.studentId,
        displayName: typeof record.displayName === 'string' && record.displayName.trim() ? record.displayName : null,
      });
    }
  }
  return { kind: 'consent_missing', students };
}

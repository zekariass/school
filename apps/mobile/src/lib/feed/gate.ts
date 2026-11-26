import { problemOf } from '@/lib/api-error';

/**
 * The consent gate (R-09) as the API reports it. Both are 422s that create or change nothing, so the screen keeps
 * everything the author typed and picked and explains what to do:
 * - `feed.media_consent_missing`: tagged students without media-publication consent (`students`, with names only when the
 *   caller may see the roster),
 * - `feed.media_confirmation_required`: photos with nobody tagged need the author's explicit confirmation.
 */
export type GateProblem =
  | { kind: 'consent_missing'; students: { studentId: string; displayName: string | null }[] }
  | { kind: 'confirmation_required' };

export const CONSENT_MISSING = 'feed.media_consent_missing';
export const CONFIRMATION_REQUIRED = 'feed.media_confirmation_required';

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

/** The tags left after dropping the students the gate named (the "remove the tag" way out). */
export function withoutStudents(studentIds: readonly string[], blocked: readonly { studentId: string }[]): string[] {
  const drop = new Set(blocked.map((student) => student.studentId));
  return studentIds.filter((id) => !drop.has(id));
}

/**
 * Names for the blocked students: the API's when it sent them, otherwise what the screen knows from the roster; ids
 * the screen cannot name stay anonymous (a count is shown instead of a guess).
 */
export function namedBlocked(
  blocked: readonly { studentId: string; displayName: string | null }[],
  known: ReadonlyMap<string, string>,
): { named: string[]; unnamed: number } {
  const named: string[] = [];
  let unnamed = 0;
  for (const student of blocked) {
    const name = student.displayName ?? known.get(student.studentId) ?? null;
    if (name) named.push(name);
    else unnamed += 1;
  }
  return { named, unnamed };
}

import { ApiRequestError } from '@/lib/api-error';

/**
 * A feed notification now names its school (`data.schoolId`, M8c follow-up): the post or story is asked for in that school
 * only. Without a school (an older notification), or when that school says "not here" (404, or 403 after a membership
 * ended), the screen falls back to asking each of the caller's schools in turn.
 */
export function shouldLookUpSchools(schoolParam: string | undefined, directError: unknown): boolean {
  if (!schoolParam) return true;
  return directError instanceof ApiRequestError && (directError.status === 404 || directError.status === 403);
}

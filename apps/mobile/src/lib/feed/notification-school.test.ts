import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '@/lib/api-error';

import { shouldLookUpSchools } from './notification-school';

describe('shouldLookUpSchools', () => {
  it("asks the caller's schools when the notification named none", () => {
    expect(shouldLookUpSchools(undefined, null)).toBe(true);
  });
  it('trusts the school of the notification while it answers', () => {
    expect(shouldLookUpSchools('school-1', null)).toBe(false);
    expect(shouldLookUpSchools('school-1', new ApiRequestError(500))).toBe(false);
    expect(shouldLookUpSchools('school-1', new ApiRequestError(undefined))).toBe(false);
  });
  it('falls back to the lookup when that school does not have it', () => {
    expect(shouldLookUpSchools('school-1', new ApiRequestError(404))).toBe(true);
    expect(shouldLookUpSchools('school-1', new ApiRequestError(403))).toBe(true);
  });
});

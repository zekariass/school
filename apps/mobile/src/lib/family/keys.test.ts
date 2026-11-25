import { describe, expect, it } from 'vitest';

import { isPersistableFamilyQuery, myChildrenKey, myConsentsKey } from './keys';

describe('family query keys', () => {
  it('keeps children and consent data out of the persisted cache', () => {
    expect(isPersistableFamilyQuery(myChildrenKey)).toBe(false);
    expect(isPersistableFamilyQuery(myConsentsKey)).toBe(false);
  });

  it('leaves everything else alone', () => {
    expect(isPersistableFamilyQuery(['me'])).toBe(true);
    expect(isPersistableFamilyQuery(['classes', 'mine'])).toBe(true);
  });
});

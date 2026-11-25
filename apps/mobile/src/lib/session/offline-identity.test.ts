import { describe, expect, it } from 'vitest';

import { OFFLINE_IDENTITY_MAX_AGE_MS, parseOfflineIdentity, toOfflineIdentity } from './offline-identity';

const NOW = 1_800_000_000_000;
const me = {
  id: 'user-1',
  displayName: 'Hanna Tesfaye',
  roleAssignments: [{ roleCode: 'SCHOOL_ADMIN', schoolId: 'x' }],
  memberships: [{ type: 'TEACHER', schoolId: 'x' }, { type: 'GUARDIAN' }],
  permissions: ['a', 'b'],
  email: 'hanna@example.org',
};

describe('toOfflineIdentity', () => {
  it('keeps the name, the role codes and the membership types, and nothing else', () => {
    const identity = toOfflineIdentity(me as never, NOW)!;
    expect(identity).toEqual({
      v: 1,
      userId: 'user-1',
      savedAt: NOW,
      me: { displayName: 'Hanna Tesfaye', roleAssignments: [{ roleCode: 'SCHOOL_ADMIN' }], memberships: [{ type: 'TEACHER' }, { type: 'GUARDIAN' }] },
    });
    expect(JSON.stringify(identity)).not.toMatch(/example\.org|permissions|schoolId/);
  });

  it('remembers only teachers: nobody else works offline', () => {
    expect(toOfflineIdentity({ ...me, memberships: [{ type: 'GUARDIAN' }] } as never, NOW)).toBeNull();
    expect(toOfflineIdentity({ ...me, memberships: [] } as never, NOW)).toBeNull();
    expect(toOfflineIdentity({ ...me, id: undefined } as never, NOW)).toBeNull();
  });
});

describe('parseOfflineIdentity', () => {
  const saved = JSON.stringify(toOfflineIdentity(me as never, NOW));

  it('reads what was saved, while it is young enough', () => {
    expect(parseOfflineIdentity(saved, NOW + 3_600_000)?.userId).toBe('user-1');
    expect(parseOfflineIdentity(saved, NOW + OFFLINE_IDENTITY_MAX_AGE_MS)).not.toBeNull();
  });

  it('expires after a week without contact with the server', () => {
    expect(parseOfflineIdentity(saved, NOW + OFFLINE_IDENTITY_MAX_AGE_MS + 1)).toBeNull();
  });

  it('does not trust a time in the future (a clock set back)', () => {
    expect(parseOfflineIdentity(saved, NOW - 3_600_000)).toBeNull();
  });

  it('rejects anything missing, damaged or without a teacher membership', () => {
    expect(parseOfflineIdentity(null, NOW)).toBeNull();
    expect(parseOfflineIdentity('', NOW)).toBeNull();
    expect(parseOfflineIdentity('{nope', NOW)).toBeNull();
    expect(parseOfflineIdentity('"text"', NOW)).toBeNull();
    expect(parseOfflineIdentity(JSON.stringify({ v: 2 }), NOW)).toBeNull();
    const guardian = { v: 1, userId: 'u', savedAt: NOW, me: { displayName: 'x', roleAssignments: [], memberships: [{ type: 'GUARDIAN' }] } };
    expect(parseOfflineIdentity(JSON.stringify(guardian), NOW)).toBeNull();
  });
});

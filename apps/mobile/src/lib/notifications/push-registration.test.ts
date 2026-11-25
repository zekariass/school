import { describe, expect, it } from 'vitest';

import { decideRegistration, isExpoPushToken, shouldShowPushCard } from './push-registration';

describe('decideRegistration', () => {
  it('registers with permission and a project id', () => {
    expect(decideRegistration({ permission: 'granted', projectId: 'abc', platform: 'ios' })).toEqual({ action: 'register', platform: 'IOS' });
    expect(decideRegistration({ permission: 'granted', projectId: 'abc', platform: 'android' })).toEqual({ action: 'register', platform: 'ANDROID' });
  });
  it('skips without permission, whatever else is true', () => {
    expect(decideRegistration({ permission: 'undetermined', projectId: 'abc', platform: 'ios' })).toEqual({ action: 'skip', reason: 'permission' });
    expect(decideRegistration({ permission: 'denied', projectId: 'abc', platform: 'ios' })).toEqual({ action: 'skip', reason: 'permission' });
  });
  it('skips while EAS is not linked', () => {
    expect(decideRegistration({ permission: 'granted', projectId: undefined, platform: 'ios' })).toEqual({ action: 'skip', reason: 'no-project' });
    expect(decideRegistration({ permission: 'granted', projectId: '', platform: 'android' })).toEqual({ action: 'skip', reason: 'no-project' });
  });
  it('skips other platforms', () => {
    expect(decideRegistration({ permission: 'granted', projectId: 'abc', platform: 'web' })).toEqual({ action: 'skip', reason: 'platform' });
  });
});

describe('shouldShowPushCard', () => {
  const base = { signedIn: true, preview: false, permission: 'undetermined' as const, dismissed: false };
  it('shows to a signed-in user not yet asked', () => {
    expect(shouldShowPushCard(base)).toBe(true);
  });
  it.each([
    { signedIn: false },
    { preview: true },
    { dismissed: true },
    { permission: 'granted' as const },
    { permission: 'denied' as const },
    { permission: null },
  ])('hides it for %o', (override) => {
    expect(shouldShowPushCard({ ...base, ...override })).toBe(false);
  });
});

describe('isExpoPushToken', () => {
  it('matches what the API accepts', () => {
    expect(isExpoPushToken('ExponentPushToken[abcdefghijklmnopqrstuv]')).toBe(true);
    expect(isExpoPushToken('ExpoPushToken[abcdefghijklmnopqrstuv]')).toBe(true);
    expect(isExpoPushToken('fcm-token')).toBe(false);
    expect(isExpoPushToken('ExponentPushToken[x]')).toBe(false);
  });
});

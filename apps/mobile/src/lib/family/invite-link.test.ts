import { describe, expect, it } from 'vitest';

import { inviteRouteFor } from './invite-link';

const token = 'abcDEF123_-abcDEF123_-abcDEF123_-abcDEF1';

describe('inviteRouteFor', () => {
  it('opens the web link of the invitation SMS', () => {
    expect(inviteRouteFor(`https://school.example/en/invite/${token}`)).toBe(`/invite/${token}`);
    expect(inviteRouteFor(`https://school.example/am/invite/${token}?utm=1#x`)).toBe(`/invite/${token}`);
    expect(inviteRouteFor(`/en/invite/${token}`)).toBe(`/invite/${token}`);
  });

  it('opens the app scheme link', () => {
    expect(inviteRouteFor(`school://invite/${token}`)).toBe(`/invite/${token}`);
    expect(inviteRouteFor(`/invite/${token}`)).toBe(`/invite/${token}`);
  });

  it('leaves other links alone', () => {
    expect(inviteRouteFor('school://notifications')).toBeNull();
    expect(inviteRouteFor('/notifications')).toBeNull();
    expect(inviteRouteFor(`https://school.example/en/s/sunrise/invite/${token}`)).toBeNull();
    expect(inviteRouteFor(`https://school.example/fr/invite/${token}`)).toBeNull();
  });

  it('refuses something that cannot be a token', () => {
    expect(inviteRouteFor('https://school.example/en/invite/short')).toBeNull();
    expect(inviteRouteFor('https://school.example/en/invite/../../x')).toBeNull();
    expect(inviteRouteFor('/en/invite/')).toBeNull();
  });
});

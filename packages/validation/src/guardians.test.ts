import { describe, expect, it } from 'vitest';

import {
  classifyInvitationFailure,
  formatParentCode,
  isCompleteParentCode,
  isDeadInvitation,
  isRelationshipType,
  looksLikeInviteToken,
  normalizeParentCode,
  parseDestinationInput,
} from './guardians';

describe('parent codes', () => {
  it('normalizes and formats as the parent types', () => {
    expect(normalizeParentCode(' ahr4p-a4ws9 ')).toBe('AHR4PA4WS9');
    expect(formatParentCode('ahr4')).toBe('AHR4');
    expect(formatParentCode('ahr4pa')).toBe('AHR4P-A');
    expect(formatParentCode('ahr4p a4ws9 extra')).toBe('AHR4P-A4WS9');
  });

  it('is complete only with 10 valid characters', () => {
    expect(isCompleteParentCode('AHR4P-A4WS9')).toBe(true);
    expect(isCompleteParentCode('ahr4pa4ws9')).toBe(true);
    expect(isCompleteParentCode('AHR4P-A4WS')).toBe(false);
    // 0, O, 1, I and L are not in the alphabet
    expect(isCompleteParentCode('AHR4P-A4WS0')).toBe(false);
    expect(isCompleteParentCode('')).toBe(false);
  });
});

describe('invitation tokens', () => {
  it('accepts base64url tokens and rejects the rest', () => {
    expect(looksLikeInviteToken('abcDEF123_-abcDEF123_-abcDEF123_-abcDEF1')).toBe(true);
    expect(looksLikeInviteToken('short')).toBe(false);
    expect(looksLikeInviteToken('has space has space has space')).toBe(false);
    expect(looksLikeInviteToken('../../etc/passwd/../../etc/passwd')).toBe(false);
    expect(looksLikeInviteToken(undefined)).toBe(false);
    expect(looksLikeInviteToken(null)).toBe(false);
  });
});

describe('parseDestinationInput', () => {
  it('reads a national phone number in the default country', () => {
    expect(parseDestinationInput('0911 23 45 67')).toEqual({ ok: true, destination: { phone: '+251911234567' } });
  });

  it('reads an email, lower-cased', () => {
    expect(parseDestinationInput(' Parent@Example.com ')).toEqual({
      ok: true,
      destination: { email: 'parent@example.com' },
    });
  });

  it('names the problem', () => {
    expect(parseDestinationInput('  ')).toEqual({ ok: false, key: 'validation.required' });
    expect(parseDestinationInput('12')).toEqual({ ok: false, key: 'validation.phone' });
    expect(parseDestinationInput('a@')).toEqual({ ok: false, key: 'validation.email' });
  });
});

describe('classifyInvitationFailure', () => {
  it('treats every dead invitation alike', () => {
    expect(classifyInvitationFailure(404, 'invitation.not_found')).toEqual({ kind: 'notFound' });
    expect(classifyInvitationFailure(404, undefined)).toEqual({ kind: 'notFound' });
    expect(isDeadInvitation(classifyInvitationFailure(404, 'invitation.not_found'))).toBe(true);
  });

  it('recognizes the contact that cannot be used', () => {
    expect(classifyInvitationFailure(409, 'invitation.contact_unavailable')).toEqual({ kind: 'contactUnavailable' });
  });

  it('carries the retry hint of a rate limit', () => {
    expect(classifyInvitationFailure(429, 'auth.rate_limited', 30)).toEqual({ kind: 'rateLimited', retryAfterSeconds: 30 });
    expect(classifyInvitationFailure(429, undefined)).toEqual({ kind: 'rateLimited', retryAfterSeconds: null });
  });

  it('separates wrong codes and bad destinations', () => {
    expect(classifyInvitationFailure(400, 'auth.invalid_code')).toEqual({ kind: 'invalidCode' });
    expect(classifyInvitationFailure(400, 'auth.invalid_destination')).toEqual({ kind: 'invalidContact' });
  });

  it('reads an unreachable server as a network problem', () => {
    expect(classifyInvitationFailure(undefined, undefined)).toEqual({ kind: 'network' });
    expect(classifyInvitationFailure(502, 'bff_upstream_unavailable')).toEqual({ kind: 'network' });
    expect(isDeadInvitation(classifyInvitationFailure(500, 'x'))).toBe(false);
    expect(classifyInvitationFailure(500, 'internal_error')).toEqual({ kind: 'generic' });
  });
});

describe('relationship types', () => {
  it('recognizes the backend values', () => {
    expect(isRelationshipType('MOTHER')).toBe(true);
    expect(isRelationshipType('NEIGHBOUR')).toBe(false);
  });
});

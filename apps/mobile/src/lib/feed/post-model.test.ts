import { describe, expect, it } from 'vitest';

import { ageOf, authorLabel, canOfferTranslation, isMine, overflowActions, photosHidden, photosWarning, postInstant, statusBadge } from './post-model';
import type { PostView } from './types';

const rights = (over: Partial<PostView['rights']> = {}): PostView['rights'] => ({
  canEdit: false,
  canDelete: false,
  canModerate: false,
  canPin: false,
  canComment: false,
  canPublish: false,
  ...over,
});

describe('authorLabel', () => {
  it('uses the name the API sent', () => {
    expect(authorLabel({ displayName: ' Ms Tigist ', role: 'TEACHER' })).toEqual({ kind: 'name', name: 'Ms Tigist' });
  });
  it('shows only the role when the API left the name out (a family member or a child)', () => {
    expect(authorLabel({ role: 'GUARDIAN' })).toEqual({ kind: 'role', role: 'GUARDIAN' });
    expect(authorLabel({ displayName: '  ', role: 'STUDENT' })).toEqual({ kind: 'role', role: 'STUDENT' });
  });
});

describe('ageOf', () => {
  const now = Date.parse('2026-11-26T12:00:00Z');
  it('is coarse', () => {
    expect(ageOf('2026-11-26T11:59:30Z', now)).toEqual({ unit: 'now' });
    expect(ageOf('2026-11-26T11:15:00Z', now)).toEqual({ unit: 'minutes', value: 45 });
    expect(ageOf('2026-11-26T07:00:00Z', now)).toEqual({ unit: 'hours', value: 5 });
    expect(ageOf('2026-11-24T12:00:00Z', now)).toEqual({ unit: 'date' });
  });
  it('treats a clock a little ahead as just now, and junk as a date', () => {
    expect(ageOf('2026-11-26T12:00:30Z', now)).toEqual({ unit: 'now' });
    expect(ageOf(undefined, now)).toEqual({ unit: 'date' });
    expect(ageOf('x', now)).toEqual({ unit: 'date' });
  });
  it('dates a post by when it went live', () => {
    expect(postInstant({ publishedAt: 'p', createdAt: 'c' })).toBe('p');
    expect(postInstant({ createdAt: 'c' })).toBe('c');
  });
});

describe('overflowActions', () => {
  const post = (status: PostView['status'], r: Partial<PostView['rights']>, pinned = false) => ({ status, pinned, rights: rights(r) });
  it('lets the author of a published post edit, pin, take down and delete: not report', () => {
    expect(overflowActions(post('PUBLISHED', { canEdit: true, canDelete: true, canPin: true }))).toEqual(['edit', 'pin', 'archive', 'delete']);
  });
  it('offers unpin for a pinned post', () => {
    expect(overflowActions(post('PUBLISHED', { canPin: true }, true))).toContain('unpin');
  });
  it('lets a moderator hide somebody else\'s post and unhide a hidden one', () => {
    expect(overflowActions(post('PUBLISHED', { canModerate: true, canDelete: true, canPin: true }))).toEqual(['pin', 'archive', 'hide', 'report', 'delete']);
    expect(overflowActions(post('HIDDEN', { canModerate: true, canDelete: true }))).toEqual(['unhide', 'delete']);
  });
  it('lets anybody else only report', () => {
    expect(overflowActions(post('PUBLISHED', {}))).toEqual(['report']);
  });
  it('offers publish for a draft and unschedule for a scheduled post', () => {
    expect(overflowActions(post('DRAFT', { canEdit: true, canPublish: true, canDelete: true }))).toEqual(['edit', 'publish', 'delete']);
    expect(overflowActions(post('SCHEDULED', { canEdit: true, canPublish: true, canDelete: true }))).toEqual(['edit', 'publish', 'unschedule', 'delete']);
  });
  it('offers nothing for a post the viewer has no rights to that is not public', () => {
    expect(overflowActions(post('ARCHIVED', {}))).toEqual([]);
  });
});

describe('small rules', () => {
  it('knows the viewer\'s own posts by id', () => {
    expect(isMine({ author: { role: 'TEACHER', userId: 'u1' } }, 'u1')).toBe(true);
    expect(isMine({ author: { role: 'TEACHER' } }, 'u1')).toBe(false);
    expect(isMine({ author: { role: 'TEACHER', userId: 'u1' } }, undefined)).toBe(false);
  });
  it('badges only what is not public', () => {
    expect(statusBadge({ status: 'PUBLISHED', contribution: false })).toBeNull();
    expect(statusBadge({ status: 'PENDING_APPROVAL', contribution: true })).toBe('PENDING_APPROVAL');
  });
  it('hides photos of a withheld post, and warns its author', () => {
    expect(photosHidden({ mediaWithheld: true, media: [] })).toBe(true);
    expect(photosHidden({ mediaWithheld: false, media: [] })).toBe(false);
    expect(photosWarning({ mediaWithheld: true, media: [{} as never] })).toBe(true);
    expect(photosHidden({ mediaWithheld: true, media: [{} as never] })).toBe(false);
  });
  it('offers translation only when on, with text in another language', () => {
    expect(canOfferTranslation({ body: 'Hello', bodyLanguageCode: 'en' }, true, 'am')).toBe(true);
    expect(canOfferTranslation({ body: 'Hello', bodyLanguageCode: 'en-GB' }, true, 'en')).toBe(false);
    expect(canOfferTranslation({ body: 'Hello' }, true, 'en')).toBe(true);
    expect(canOfferTranslation({ body: 'Hello', bodyLanguageCode: 'en' }, false, 'am')).toBe(false);
    expect(canOfferTranslation({ body: '  ', bodyLanguageCode: 'en' }, true, 'am')).toBe(false);
  });
});

import { describe, expect, it } from 'vitest';

import { overflowActions } from '@/lib/feed/post-model';
import type { PostView } from '@/lib/feed/types';

import { canReportComment, canReportPerson, canReportPost, canReportStory } from './visibility';

// Each case is the answer the API gives to that person: the menu follows the item's own flags, never the viewer's role.
const rights = (over: Partial<PostView['rights']> = {}): PostView['rights'] => ({ ...over });
const author = (userId?: string) => ({ role: 'TEACHER' as const, ...(userId ? { userId } : {}) });

describe('posts', () => {
  it('a guardian, a student and another teacher (no edit right) can report a published post', () => {
    expect(canReportPost({ status: 'PUBLISHED', rights: rights({ canComment: true }) })).toBe(true);
    expect(canReportPost({ status: 'PUBLISHED', rights: rights() })).toBe(true);
  });
  it('the author (who can edit) is not offered it: the API would answer 409', () => {
    expect(canReportPost({ status: 'PUBLISHED', rights: rights({ canEdit: true, canDelete: true }) })).toBe(false);
  });
  it('a moderator who is not the author can still report, next to "hide"', () => {
    expect(canReportPost({ status: 'PUBLISHED', rights: rights({ canModerate: true }) })).toBe(true);
    expect(overflowActions({ status: 'PUBLISHED', rights: rights({ canModerate: true }), pinned: false })).toEqual(['hide', 'report']);
  });
  it.each(['DRAFT', 'PENDING_APPROVAL', 'SCHEDULED', 'HIDDEN', 'ARCHIVED', 'REJECTED'])('nothing is offered on a %s post', (status) => {
    expect(canReportPost({ status: status as PostView['status'], rights: rights() })).toBe(false);
  });
});

describe('comments', () => {
  it("somebody else's shown comment can be reported by anybody who sees it", () => {
    expect(canReportComment({ status: 'VISIBLE', mine: false })).toBe(true);
    expect(canReportComment({ status: 'VISIBLE' })).toBe(true);
  });
  it('your own comment cannot, nor one that is hidden or deleted', () => {
    expect(canReportComment({ status: 'VISIBLE', mine: true })).toBe(false);
    expect(canReportComment({ status: 'HIDDEN', mine: false })).toBe(false);
    expect(canReportComment({ status: 'DELETED', mine: false })).toBe(false);
  });
});

describe('stories', () => {
  it("another person's published story is reportable, also by a student", () => {
    expect(canReportStory({ status: 'PUBLISHED', author: author('teacher-1') }, 'student-1')).toBe(true);
  });
  it("the viewer's own story is not offered", () => {
    expect(canReportStory({ status: 'PUBLISHED', author: author('me') }, 'me')).toBe(false);
  });
  it('an author the API did not name to this viewer is offered, and the API answers for itself', () => {
    expect(canReportStory({ status: 'PUBLISHED', author: author() }, 'me')).toBe(true);
    expect(canReportStory({ status: 'PUBLISHED', author: author('x') }, undefined)).toBe(true);
  });
  it.each(['HIDDEN', 'EXPIRED', 'DELETED'] as const)('a %s story is not offered', (status) => {
    expect(canReportStory({ status, author: author('x') }, 'me')).toBe(false);
  });
});

describe('people', () => {
  it('somebody else yes, yourself or nobody no', () => {
    expect(canReportPerson('other', 'me')).toBe(true);
    expect(canReportPerson('me', 'me')).toBe(false);
    expect(canReportPerson(undefined, 'me')).toBe(false);
    expect(canReportPerson('other', undefined)).toBe(false);
  });
});

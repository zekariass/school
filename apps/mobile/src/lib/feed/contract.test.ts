import { describe, expect, it } from 'vitest';

import type { ProblemDetail } from '@school/api-client';

import { ApiRequestError } from '@/lib/api-error';

import { gateProblemOf, namedBlocked, withoutStudents } from './gate';
import { gridLayout, listVariant, pickVariant } from './media-layout';
import { flattenPages } from './pages';
import { authorLabel, overflowActions, photosHidden, photosWarning } from './post-model';
import { applyReaction } from './reactions';
import { durationOf, isLive, remaining } from './story-timing';
import type { PostView, StoryView } from './types';

/**
 * Payloads as the M8a API really sent them (captured from a local run with fictional names), so that the screens' pure logic
 * is checked against the actual shape: primitives that are false/zero are present, optional members are absent.
 */

const gate422 = {
  detail: 'The photos cannot be published yet: 1 tagged student(s) do not have a media-publication consent from their guardians.',
  status: 422,
  title: "Request can't be processed",
  type: 'urn:school:problem:feed.media_consent_missing',
  code: 'feed.media_consent_missing',
  requestId: '01a216d2-7aba-71cc-9d0e-5fe1b77f4700',
  students: [{ studentId: '00000000-0000-4000-8000-000000000202', displayName: 'Bethlehem Tadesse' }],
} as unknown as ProblemDetail;

const teacherPost = {
  id: '01a216c8-366c-772f-8028-9a56519f1266',
  scope: 'CLASS',
  schoolId: '00000000-0000-4000-8000-0000000000a1',
  classId: '00000000-0000-4000-8000-0000000000c1',
  status: 'PUBLISHED',
  postType: 'ACTIVITY',
  author: { userId: '00000000-0000-4000-8000-000000000101', role: 'TEACHER', displayName: 'Tigist Alemu' },
  body: 'Our trip to the science museum.',
  bodyLanguageCode: 'en',
  contribution: false,
  publishedAt: '2026-11-26T07:35:49.768475Z',
  createdAt: '2026-11-26T07:35:49.612988Z',
  pinned: false,
  commentsEnabled: true,
  media: [0, 1, 2, 3, 4].map((n) => ({ mediaId: `m${n}`, sortOrder: n, kind: 'IMAGE', width: 640, height: 480, variants: ['THUMB', 'SMALL', 'MEDIUM'] })),
  mediaWithheld: false,
  tags: [{ studentId: '00000000-0000-4000-8000-000000000201', displayName: 'Abel Kebede' }],
  commentCount: 0,
  reactionCounts: {},
  seenCount: 0,
  rights: { canEdit: true, canDelete: true, canModerate: true, canPin: true, canComment: true, canPublish: false },
} as unknown as PostView;

const familyPostForOthers = {
  ...teacherPost,
  id: 'family',
  contribution: true,
  author: { role: 'GUARDIAN' },
  tags: [],
  seenCount: undefined,
  rights: { canEdit: false, canDelete: false, canModerate: false, canPin: false, canComment: true, canPublish: false },
} as unknown as PostView;

const pending = {
  ...teacherPost,
  id: 'pending',
  status: 'PENDING_APPROVAL',
  contribution: true,
  author: { userId: 'u', role: 'GUARDIAN', displayName: 'Almaz Worku' },
  rights: { canEdit: false, canDelete: true, canModerate: true, canPin: false, canComment: false, canPublish: false },
} as unknown as PostView;

const withheld = { ...familyPostForOthers, id: 'withheld', media: [], mediaWithheld: true } as unknown as PostView;

const story = {
  id: 's1',
  scope: 'CLASS',
  status: 'PUBLISHED',
  author: { userId: 'u', role: 'TEACHER', displayName: 'Tigist Alemu' },
  caption: 'Remember your hats tomorrow!',
  mediaWithheld: false,
  tags: [],
  publishedAt: '2026-11-26T07:35:53.630210Z',
  expiresAt: '2026-11-27T07:35:53.630210Z',
  viewedByMe: false,
  canDelete: false,
} as unknown as StoryView;

describe('the consent gate answer of the API', () => {
  it('is parsed with the student names the teacher may see', () => {
    const gate = gateProblemOf(new ApiRequestError(422, gate422));
    expect(gate).toEqual({ kind: 'consent_missing', students: [{ studentId: '00000000-0000-4000-8000-000000000202', displayName: 'Bethlehem Tadesse' }] });
    if (gate?.kind !== 'consent_missing') throw new Error('unreachable');
    expect(withoutStudents(['00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000202'], gate.students)).toEqual(['00000000-0000-4000-8000-000000000201']);
    expect(namedBlocked(gate.students, new Map())).toEqual({ named: ['Bethlehem Tadesse'], unnamed: 0 });
  });
});

describe('a teacher\'s post', () => {
  it('has the whole menu its rights allow, and nothing else', () => {
    expect(overflowActions(teacherPost)).toEqual(['edit', 'pin', 'archive', 'hide', 'delete']);
  });
  it('is named, shows five photos as 2 x 2 plus "+1" using THUMB or SMALL only', () => {
    expect(authorLabel(teacherPost.author)).toEqual({ kind: 'name', name: 'Tigist Alemu' });
    const layout = gridLayout(teacherPost.media.length);
    expect(layout.rows.flatMap((row) => row.cells.map((cell) => cell.index))).toEqual([0, 1, 2, 3]);
    expect(layout.more).toBe(1);
    expect(listVariant(180)).toBe('SMALL');
    expect(pickVariant(teacherPost.media[0]!.variants, listVariant(120))).toBe('THUMB');
  });
  it('takes a reaction optimistically from the real counts shape (empty object)', () => {
    const after = applyReaction(teacherPost, 'CLAP');
    expect(after.reactionCounts).toEqual({ CLAP: 1 });
    expect(after.myReaction).toBe('CLAP');
  });
});

describe('what other families see', () => {
  it('a family contribution shows a role label only, and a post can be reported', () => {
    expect(authorLabel(familyPostForOthers.author)).toEqual({ kind: 'role', role: 'GUARDIAN' });
    expect(overflowActions(familyPostForOthers)).toEqual(['report']);
  });
  it('a post with withheld photos has none to show and says why', () => {
    expect(photosHidden(withheld)).toBe(true);
    expect(photosWarning(withheld)).toBe(false);
    expect(overflowActions(withheld)).toEqual(['report']);
  });
  it('the author of a withheld post keeps the photos and is warned', () => {
    expect(photosWarning({ ...teacherPost, mediaWithheld: true })).toBe(true);
    expect(photosHidden({ ...teacherPost, mediaWithheld: true })).toBe(false);
  });
});

describe('the approval queue', () => {
  it('lets the teacher delete but not edit a contribution, and shows the contributor by name to staff', () => {
    expect(overflowActions(pending)).toEqual(['delete']);
    expect(authorLabel(pending.author)).toEqual({ kind: 'name', name: 'Almaz Worku' });
  });
  it('lists pages without repeating a post', () => {
    const page = { items: [pending, teacherPost] };
    expect(flattenPages({ pages: [page, { items: [teacherPost] }], pageParams: [undefined, 'c'] }).map((post) => post.id)).toEqual(['pending', teacherPost.id]);
  });
});

describe('a story', () => {
  it('lives 24 hours, plays eight seconds as text, and says how long is left', () => {
    const published = Date.parse(story.publishedAt);
    expect(Date.parse(story.expiresAt) - published).toBe(24 * 3600 * 1000);
    expect(isLive(story, published + 3600_000)).toBe(true);
    expect(isLive(story, Date.parse(story.expiresAt))).toBe(false);
    expect(durationOf(story)).toBe(8000);
    expect(remaining(story.expiresAt, published + 3600_000)).toEqual({ unit: 'hours', value: 23 });
  });
});

import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '@/lib/api-error';

import { HISTORY_FALLBACK, HISTORY_LABEL, STATUS_HINT, STATUS_LABEL } from './labels';
import {
  authorLabel,
  commentProblem,
  creatableTypes,
  defaultVisibility,
  historyLabelKey,
  isLoweredByCap,
  isTombstone,
  listState,
  lowerVisibilities,
  needsConnection,
  noteRequired,
  takesPhotos,
  visibilityChoices,
  withheldNotice,
} from './model';

describe('visibility', () => {
  it('offers the audiences up to the school cap', () => {
    expect(visibilityChoices('GUARDIANS')).toEqual(['STUDENT_TEACHER', 'GUARDIANS']);
    expect(visibilityChoices('SCHOOL')).toEqual(['STUDENT_TEACHER', 'GUARDIANS', 'CLASS', 'SCHOOL']);
    expect(visibilityChoices('STUDENT_TEACHER')).toEqual(['STUDENT_TEACHER']);
  });
  it('defaults to the family, or to less when the cap is lower', () => {
    expect(defaultVisibility('SCHOOL')).toBe('GUARDIANS');
    expect(defaultVisibility('GUARDIANS')).toBe('GUARDIANS');
    expect(defaultVisibility('STUDENT_TEACHER')).toBe('STUDENT_TEACHER');
  });
  it('lets a reviewer lower the audience, never raise it', () => {
    expect(lowerVisibilities('CLASS')).toEqual(['STUDENT_TEACHER', 'GUARDIANS', 'CLASS']);
    expect(lowerVisibilities('STUDENT_TEACHER')).toEqual(['STUDENT_TEACHER']);
  });
  it('notices a cap that narrowed the item', () => {
    expect(isLoweredByCap({ visibility: 'SCHOOL', effectiveVisibility: 'GUARDIANS' })).toBe(true);
    expect(isLoweredByCap({ visibility: 'GUARDIANS', effectiveVisibility: 'GUARDIANS' })).toBe(false);
  });
});

describe('withheldNotice', () => {
  it('is hidden when the API sent no files for an item that has them', () => {
    expect(withheldNotice({ mediaWithheld: true, media: [] })).toBe('hidden');
  });
  it('is a warning for the student and the reviewers, who still get the files', () => {
    const media = [{ mediaId: 'm', kind: 'IMAGE' as const, mimeType: 'image/jpeg', variants: ['THUMB' as const] }];
    expect(withheldNotice({ mediaWithheld: true, media })).toBe('warning');
  });
  it('is nothing when the files are not withheld, or there are none', () => {
    expect(withheldNotice({ mediaWithheld: false, media: [] })).toBeNull();
    expect(withheldNotice({ media: [] } as never)).toBeNull();
  });
});

describe('review decisions', () => {
  it('needs a note for changes and rejection only', () => {
    expect(noteRequired('APPROVE')).toBe(false);
    expect(noteRequired('REQUEST_CHANGES')).toBe(true);
    expect(noteRequired('REJECT')).toBe(true);
  });
});

describe('comments', () => {
  it('checks the text lightly (the API checks it again)', () => {
    expect(commentProblem('   ')).toBe('empty');
    expect(commentProblem('Well done')).toBeNull();
    expect(commentProblem('x'.repeat(500))).toBeNull();
    expect(commentProblem('x'.repeat(501))).toBe('too_long');
  });
  it('shows a reviewer by name and anyone else by role, never a guess', () => {
    expect(authorLabel({ authorName: ' Ato Bekele ', authorRole: 'TEACHER' })).toEqual({ kind: 'name', name: 'Ato Bekele' });
    expect(authorLabel({ authorRole: 'GUARDIAN' })).toEqual({ kind: 'role', labelKey: 'mobile.portfolio.role.guardian' });
    expect(authorLabel({ authorName: '  ', authorRole: 'STUDENT' })).toEqual({ kind: 'role', labelKey: 'mobile.portfolio.role.student' });
  });
  it('knows a tombstone', () => {
    expect(isTombstone({ status: 'HIDDEN' })).toBe(true);
    expect(isTombstone({ status: 'VISIBLE' })).toBe(false);
  });
});

describe('history', () => {
  it('words every action the API writes, and has a neutral word for the rest', () => {
    for (const action of Object.keys(HISTORY_LABEL)) expect(historyLabelKey({ action })).toBe(HISTORY_LABEL[action as keyof typeof HISTORY_LABEL]);
    expect(historyLabelKey({ action: 'SOMETHING_NEW' })).toBe(HISTORY_FALLBACK);
  });
});

describe('what a phone can create', () => {
  it('offers photo, scan, drawing and text, cut down to what the API says', () => {
    expect(creatableTypes(['TEXT', 'PHOTO', 'DRAWING', 'SCAN', 'DOCUMENT'])).toEqual(['PHOTO', 'SCAN', 'DRAWING', 'TEXT']);
    expect(creatableTypes(['TEXT'])).toEqual(['TEXT']);
    expect(creatableTypes(['DOCUMENT', 'AUDIO', 'VIDEO'])).toEqual([]);
  });
  it('knows which types take pictures', () => {
    expect(takesPhotos('PHOTO')).toBe(true);
    expect(takesPhotos('SCAN')).toBe(true);
    expect(takesPhotos('DRAWING')).toBe(true);
    expect(takesPhotos('TEXT')).toBe(false);
  });
});

describe('the connection', () => {
  it('needs a connection only for a failure without an answer', () => {
    expect(needsConnection(new ApiRequestError(undefined))).toBe(true);
    expect(needsConnection(new ApiRequestError(500))).toBe(false);
    expect(needsConnection(new ApiRequestError(404))).toBe(false);
    expect(needsConnection(new Error('x'))).toBe(false);
  });
  const base = { isPending: false, isError: false, error: null, fetchStatus: 'idle' as const, hasItems: false };
  it('says "offline" for a paused first load: nothing old is shown instead', () => {
    expect(listState({ ...base, isPending: true, fetchStatus: 'paused' })).toBe('offline');
  });
  it('says "offline" for a first load that failed without an answer', () => {
    expect(listState({ ...base, isError: true, error: new ApiRequestError(undefined) })).toBe('offline');
  });
  it('loads, errors, is empty or ready', () => {
    expect(listState({ ...base, isPending: true, fetchStatus: 'fetching' })).toBe('loading');
    expect(listState({ ...base, isError: true, error: new ApiRequestError(403) })).toBe('error');
    expect(listState(base)).toBe('empty');
    expect(listState({ ...base, hasItems: true })).toBe('ready');
  });
  it('keeps showing what is in memory when a refresh fails', () => {
    expect(listState({ ...base, isError: true, error: new ApiRequestError(undefined), hasItems: true })).toBe('ready');
  });
});

describe('labels', () => {
  it('words every status twice: its name and what it means', () => {
    expect(Object.keys(STATUS_LABEL).sort()).toEqual(Object.keys(STATUS_HINT).sort());
    expect(Object.keys(STATUS_LABEL)).toHaveLength(6);
  });
});

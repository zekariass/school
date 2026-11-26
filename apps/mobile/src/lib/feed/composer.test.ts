import { describe, expect, it } from 'vitest';

import {
  MAX_PHOTOS,
  blockedReason,
  composerFromPost,
  composerReducer,
  isDirty,
  initialComposer,
  keyAfterFailure,
  keyFor,
  needsConfirmation,
  toContribution,
  toCreatePost,
  toCreateStory,
  toUpdatePost,
  type ComposerAction,
  type ComposerState,
} from './composer';
import type { PostView } from './types';

function run(state: ComposerState, ...actions: ComposerAction[]): ComposerState {
  return actions.reduce(composerReducer, state);
}

const withPhoto = (state: ComposerState, key = 'a', mediaId = 'm-a') =>
  run(state, { type: 'attach', key, uri: `file:///${key}.jpg` }, { type: 'attachStatus', key, status: 'uploading' }, { type: 'attachReady', key, mediaId });

describe('attachments', () => {
  const start = initialComposer('post', true);
  it('walks preparing, uploading, ready', () => {
    let state = run(start, { type: 'attach', key: 'a', uri: 'u' });
    expect(state.attachments[0]).toMatchObject({ status: 'preparing', percent: 0 });
    state = run(state, { type: 'attachStatus', key: 'a', status: 'uploading' }, { type: 'attachProgress', key: 'a', percent: 40.9 });
    expect(state.attachments[0]).toMatchObject({ status: 'uploading', percent: 40 });
    state = run(state, { type: 'attachReady', key: 'a', mediaId: 'm1' });
    expect(state.attachments[0]).toMatchObject({ status: 'ready', percent: 100, mediaId: 'm1' });
  });
  it('keeps a failed photo for a retry, with its caption', () => {
    let state = run(start, { type: 'attach', key: 'a', uri: 'u' }, { type: 'caption', key: 'a', value: 'Painting' }, { type: 'attachFailed', key: 'a', message: 'No signal' });
    expect(state.attachments[0]).toMatchObject({ status: 'failed', failure: 'No signal', caption: 'Painting' });
    expect(blockedReason(state)).toBe('failed_upload');
    state = run(state, { type: 'attachRetry', key: 'a' });
    expect(state.attachments[0]).toMatchObject({ status: 'preparing', caption: 'Painting' });
    expect(state.attachments[0]?.failure).toBeUndefined();
  });
  it('ignores progress that is not uploading and unknown keys', () => {
    const state = run(start, { type: 'attach', key: 'a', uri: 'u' });
    expect(run(state, { type: 'attachProgress', key: 'a', percent: 50 })).toBe(state);
    expect(run(state, { type: 'attachReady', key: 'zzz', mediaId: 'x' })).toBe(state);
  });
  it('stops at ten photos and does not add the same one twice', () => {
    let state = start;
    for (let index = 0; index < MAX_PHOTOS + 3; index++) state = run(state, { type: 'attach', key: `k${index}`, uri: 'u' });
    expect(state.attachments).toHaveLength(MAX_PHOTOS);
    expect(run(state, { type: 'attach', key: 'k0', uri: 'u' })).toBe(state);
  });
  it('reorders and removes', () => {
    let state = run(withPhoto(withPhoto(start, 'a', 'm-a'), 'b', 'm-b'), { type: 'move', key: 'b', direction: -1 });
    expect(state.attachments.map((item) => item.key)).toEqual(['b', 'a']);
    expect(run(state, { type: 'move', key: 'b', direction: -1 })).toBe(state);
    state = run(state, { type: 'detach', key: 'b' });
    expect(state.attachments.map((item) => item.key)).toEqual(['a']);
  });
  it('limits a caption', () => {
    const state = run(start, { type: 'attach', key: 'a', uri: 'u' }, { type: 'caption', key: 'a', value: 'x'.repeat(400) });
    expect(state.attachments[0]?.caption).toHaveLength(300);
  });
});

describe('the consent confirmation', () => {
  it('is needed for photos with nobody tagged, and asks again after any change', () => {
    let state = withPhoto(initialComposer('post', true));
    expect(needsConfirmation(state)).toBe(true);
    state = run(state, { type: 'confirm', value: true });
    expect(state.confirmNoUnconsented).toBe(true);
    expect(run(state, { type: 'toggleStudent', studentId: 's1' }).confirmNoUnconsented).toBe(false);
    expect(run(state, { type: 'attach', key: 'b', uri: 'u' }).confirmNoUnconsented).toBe(false);
    expect(run(state, { type: 'detach', key: 'a' }).confirmNoUnconsented).toBe(false);
    expect(run(state, { type: 'setStudents', studentIds: ['s1'] }).confirmNoUnconsented).toBe(false);
  });
  it('is not needed with a tag, without photos, or for a family contribution', () => {
    expect(needsConfirmation(run(withPhoto(initialComposer('post', true)), { type: 'toggleStudent', studentId: 's1' }))).toBe(false);
    expect(needsConfirmation(run(initialComposer('post', true), { type: 'body', value: 'Hello' }))).toBe(false);
    expect(needsConfirmation(withPhoto(initialComposer('contribution', true)))).toBe(false);
  });
});

describe('blockedReason', () => {
  const start = initialComposer('post', true);
  it('wants something to post', () => {
    expect(blockedReason(start)).toBe('empty');
    expect(blockedReason(run(start, { type: 'body', value: '   ' }))).toBe('empty');
    expect(blockedReason(run(start, { type: 'body', value: 'Hi' }))).toBeNull();
    expect(blockedReason(withPhoto(start))).toBeNull();
  });
  it('waits for uploads', () => {
    expect(blockedReason(run(start, { type: 'attach', key: 'a', uri: 'u' }))).toBe('uploading');
  });
  it('limits the text: 5000 for a post, 500 for a story', () => {
    expect(blockedReason(run(start, { type: 'body', value: 'x'.repeat(5001) }))).toBe('too_long');
    expect(blockedReason(run(start, { type: 'body', value: 'x'.repeat(5000) }))).toBeNull();
    expect(blockedReason(run(initialComposer('story', true), { type: 'body', value: 'x'.repeat(501) }))).toBe('too_long');
  });
  it('wants a time to schedule', () => {
    const scheduled = run(start, { type: 'body', value: 'Hi' }, { type: 'mode', value: 'SCHEDULE' });
    expect(blockedReason(scheduled)).toBe('schedule_missing');
    expect(blockedReason(run(scheduled, { type: 'schedule', value: { date: '2026-12-01', minutes: 600 } }))).toBeNull();
  });
});

describe('request bodies', () => {
  it('builds a post with ready photos only, trimmed text and no stray confirmation', () => {
    let state = run(initialComposer('post', false), { type: 'body', value: '  Field trip  ' }, { type: 'toggleStudent', studentId: 's1' });
    state = withPhoto(state, 'a', 'm1');
    state = run(state, { type: 'caption', key: 'a', value: ' By the river ' }, { type: 'attach', key: 'b', uri: 'u' });
    const body = toCreatePost(state, 'en');
    expect(body).toEqual({
      body: 'Field trip',
      bodyLanguageCode: 'en',
      postType: 'POST',
      media: [{ mediaId: 'm1', caption: 'By the river' }],
      studentIds: ['s1'],
      commentsEnabled: false,
      mode: 'PUBLISH',
    });
  });
  it('sends the confirmation only for untagged photos, and only when given', () => {
    let state = withPhoto(run(initialComposer('post', true), { type: 'body', value: 'Hi' }));
    expect(toCreatePost(state, 'en').confirmNoUnconsentedStudents).toBeUndefined();
    state = run(state, { type: 'confirm', value: true });
    expect(toCreatePost(state, 'en').confirmNoUnconsentedStudents).toBe(true);
    expect(toUpdatePost(state, 'en').confirmNoUnconsentedStudents).toBe(true);
    // a tag replaces the statement
    const tagged = run(state, { type: 'toggleStudent', studentId: 's1' }, { type: 'confirm', value: true });
    expect(toCreatePost(tagged, 'en').confirmNoUnconsentedStudents).toBeUndefined();
  });
  it('schedules in the instant given, never without one', () => {
    const state = run(initialComposer('post', true), { type: 'body', value: 'Hi' }, { type: 'mode', value: 'SCHEDULE' });
    expect(toCreatePost(state, 'am', '2026-12-01T04:30:00.000Z')).toMatchObject({ mode: 'SCHEDULE', scheduledFor: '2026-12-01T04:30:00.000Z', bodyLanguageCode: 'am' });
    expect('scheduledFor' in toCreatePost(state, 'am')).toBe(false);
    expect('scheduledFor' in toCreatePost(run(state, { type: 'mode', value: 'DRAFT' }), 'am', '2026-12-01T04:30:00.000Z')).toBe(false);
  });
  it('a contribution never carries a confirmation', () => {
    const state = run(withPhoto(initialComposer('contribution', true)), { type: 'confirm', value: true }, { type: 'body', value: 'Our dance' }, { type: 'toggleStudent', studentId: 'kid' });
    expect(toContribution(state, 'en')).toEqual({ body: 'Our dance', bodyLanguageCode: 'en', media: [{ mediaId: 'm-a' }], studentIds: ['kid'] });
  });
  it('a story has one photo and an optional caption', () => {
    let state = withPhoto(initialComposer('story', true), 'a', 'm1');
    state = run(withPhoto(state, 'b', 'm2'), { type: 'confirm', value: true });
    expect(toCreateStory(state, 'en')).toEqual({ mediaId: 'm1', studentIds: [], confirmNoUnconsentedStudents: true });
    expect(toCreateStory(run(initialComposer('story', true), { type: 'body', value: 'Sports day!' }), 'am')).toEqual({
      caption: 'Sports day!',
      captionLanguageCode: 'am',
      studentIds: [],
    });
  });
});

describe('composerFromPost', () => {
  it('fills the editor from the post', () => {
    const post = {
      body: 'Hello',
      postType: 'ACTIVITY',
      commentsEnabled: false,
      contribution: false,
      status: 'SCHEDULED',
      tags: [{ studentId: 's1', displayName: 'A' }],
      media: [{ mediaId: 'm1', caption: 'c', sortOrder: 0, kind: 'IMAGE', variants: [] }],
    } as unknown as PostView;
    const state = composerFromPost(post);
    expect(state).toMatchObject({ kind: 'post', body: 'Hello', postType: 'ACTIVITY', commentsEnabled: false, studentIds: ['s1'], mode: 'SCHEDULE' });
    expect(state.attachments[0]).toMatchObject({ mediaId: 'm1', status: 'ready', caption: 'c' });
  });
});

describe('idempotency keys', () => {
  let counter = 0;
  const fresh = () => `key-${++counter}`;
  it('reuses the key for the same content and starts a new one for a change', () => {
    const first = keyFor(null, { a: 1 }, fresh);
    expect(keyFor(first, { a: 1 }, fresh)).toBe(first);
    expect(keyFor(first, { a: 2 }, fresh).key).not.toBe(first.key);
  });
  it('keeps the key only when the server never answered', () => {
    const key = keyFor(null, { a: 1 }, fresh);
    expect(keyAfterFailure(key, undefined)).toBe(key);
    expect(keyAfterFailure(key, 503)).toBe(key);
    expect(keyAfterFailure(key, 422)).toBeNull();
    expect(keyAfterFailure(key, 409)).toBeNull();
  });
});

describe('isDirty', () => {
  const start = initialComposer('post', true);
  it('is clean until something changes', () => {
    expect(isDirty(start, start)).toBe(false);
    expect(isDirty(start, run(start, { type: 'mode', value: 'PUBLISH' }))).toBe(false);
    expect(isDirty(start, run(start, { type: 'body', value: '   ' }))).toBe(false);
  });
  it('asks when words, photos, tags or timing are there', () => {
    expect(isDirty(start, run(start, { type: 'body', value: 'Hi' }))).toBe(true);
    expect(isDirty(start, withPhoto(start))).toBe(true);
    expect(isDirty(start, run(start, { type: 'toggleStudent', studentId: 's1' }))).toBe(true);
    expect(isDirty(start, run(start, { type: 'mode', value: 'DRAFT' }))).toBe(true);
  });
  it('is clean again when the change is undone', () => {
    const typed = run(start, { type: 'body', value: 'Hi' });
    expect(isDirty(start, run(typed, { type: 'body', value: '' }))).toBe(false);
  });
});

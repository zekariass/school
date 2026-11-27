import { describe, expect, it } from 'vitest';

import type { PortfolioItem } from './types';
import {
  blockedReason,
  composerFromItem,
  composerReducer,
  initialComposer,
  isDirty,
  keyAfterFailure,
  keyFor,
  needsConfirmation,
  overLimitField,
  toCreateRequest,
  toQuickCapture,
  toUpdateRequest,
  type ComposerAction,
  type ComposerState,
} from './composer';

function run(state: ComposerState, ...actions: ComposerAction[]): ComposerState {
  return actions.reduce(composerReducer, state);
}

const student = () => initialComposer({ kind: 'student', itemType: 'PHOTO', visibility: 'GUARDIANS' });
const withPhoto = (state: ComposerState, key = 'k1', mediaId = 'm1') =>
  run(state, { type: 'attach', key, uri: 'file:///x.jpg' }, { type: 'attachStatus', key, status: 'uploading' }, { type: 'attachReady', key, mediaId });

describe('the reducer', () => {
  it('starts as a child\'s own work (nobody else in it) or as a teacher\'s capture (others may appear)', () => {
    expect(student().showsOtherChildren).toBe(false);
    expect(initialComposer({ kind: 'capture', itemType: 'PHOTO', visibility: 'GUARDIANS' }).showsOtherChildren).toBe(true);
  });
  it('sets the fields and takes the subject of the class chosen', () => {
    const state = run(student(), { type: 'title', value: 'My drawing' }, { type: 'class', classId: 'c1', subjectId: 'sub1' }, { type: 'visibility', value: 'CLASS' });
    expect(state).toMatchObject({ title: 'My drawing', classId: 'c1', subjectId: 'sub1', visibility: 'CLASS' });
  });
  it('keeps the pictures when a photo becomes a drawing, and drops them for a text item', () => {
    const state = withPhoto(student());
    expect(run(state, { type: 'itemType', value: 'DRAWING' }).attachments).toHaveLength(1);
    const text = run(state, { type: 'itemType', value: 'TEXT' });
    expect(text.attachments).toEqual([]);
    expect(text.itemType).toBe('TEXT');
    expect(run(state, { type: 'itemType', value: 'PHOTO' })).toBe(state);
  });
  it('follows a photo from picked to ready', () => {
    const state = withPhoto(student());
    expect(state.attachments).toEqual([{ key: 'k1', uri: 'file:///x.jpg', caption: '', status: 'ready', percent: 100, mediaId: 'm1' }]);
  });
  it('asks for the confirmation again when a file is added or removed, not when one finishes uploading', () => {
    let state = withPhoto(student());
    state = run(state, { type: 'confirm', value: true });
    state = run(state, { type: 'attachProgress', key: 'k1', percent: 50 });
    expect(state.confirm).toBe(true);
    expect(run(state, { type: 'attach', key: 'k2', uri: 'u' }).confirm).toBe(false);
    expect(run(state, { type: 'detach', key: 'k1' }).confirm).toBe(false);
  });
  it('asks again when the tags change', () => {
    const state = run(withPhoto(student()), { type: 'confirm', value: true }, { type: 'toggleStudent', studentId: 'a' });
    expect(state.confirm).toBe(false);
    expect(state.studentIds).toEqual(['a']);
    expect(run(state, { type: 'toggleStudent', studentId: 'a' }).studentIds).toEqual([]);
  });
  it('keeps at most ten files', () => {
    let state = student();
    for (let n = 0; n < 12; n++) state = run(state, { type: 'attach', key: `k${n}`, uri: 'u' });
    expect(state.attachments).toHaveLength(10);
  });
  it('moves a file earlier or later and ignores a move off the end', () => {
    const state = run(withPhoto(withPhoto(student(), 'a', 'ma'), 'b', 'mb'), { type: 'move', key: 'b', direction: -1 });
    expect(state.attachments.map((item) => item.key)).toEqual(['b', 'a']);
    expect(run(state, { type: 'move', key: 'b', direction: -1 })).toBe(state);
  });
});

describe('blockedReason', () => {
  it('lets a draft go with only a title', () => {
    expect(blockedReason(student(), 'draft')).toBe('title_missing');
    expect(blockedReason(run(student(), { type: 'title', value: 'x' }), 'draft')).toBeNull();
  });
  it('wants the work itself to submit: a picture, or text for a text item', () => {
    const titled = run(student(), { type: 'title', value: 'x' });
    expect(blockedReason(titled, 'submit')).toBe('photo_missing');
    expect(blockedReason(withPhoto(titled), 'submit')).toBeNull();
    const text = initialComposer({ kind: 'student', itemType: 'TEXT', visibility: 'GUARDIANS' });
    expect(blockedReason(run(text, { type: 'title', value: 'x' }), 'submit')).toBe('text_missing');
    expect(blockedReason(run(text, { type: 'title', value: 'x' }, { type: 'text', value: 'hello' }), 'submit')).toBeNull();
  });
  it('waits for uploads and refuses failed ones', () => {
    const titled = run(student(), { type: 'title', value: 'x' }, { type: 'attach', key: 'k', uri: 'u' });
    expect(blockedReason(titled, 'draft')).toBe('uploading');
    expect(blockedReason(run(titled, { type: 'attachFailed', key: 'k', message: 'no' }), 'draft')).toBe('failed_upload');
  });
  it('refuses text over the limits, naming the field', () => {
    const long = run(student(), { type: 'title', value: 'x' }, { type: 'reflection', value: 'y'.repeat(1001) });
    expect(overLimitField(long)).toBe('reflection');
    expect(blockedReason(long, 'draft')).toBe('too_long');
    expect(overLimitField(run(student(), { type: 'title', value: 'x'.repeat(121) }))).toBe('title');
  });
});

describe('needsConfirmation', () => {
  it('is for pictures with nobody tagged', () => {
    expect(needsConfirmation(student())).toBe(false);
    expect(needsConfirmation(withPhoto(student()))).toBe(true);
    expect(needsConfirmation(run(withPhoto(student()), { type: 'toggleStudent', studentId: 'a' }))).toBe(false);
  });
  it('never applies to a text item', () => {
    expect(needsConfirmation(initialComposer({ kind: 'student', itemType: 'TEXT', visibility: 'GUARDIANS' }))).toBe(false);
  });
});

describe('requests', () => {
  const ready = () => run(withPhoto(student()), { type: 'title', value: '  Sunset  ' }, { type: 'reflection', value: ' I mixed orange and red ' }, { type: 'class', classId: 'c1', subjectId: 'sub1' });

  it('creates a draft or a submission with trimmed words and the file ids', () => {
    expect(toCreateRequest(ready(), 'DRAFT')).toEqual({
      itemType: 'PHOTO',
      title: 'Sunset',
      reflection: 'I mixed orange and red',
      subjectId: 'sub1',
      visibility: 'GUARDIANS',
      mediaIds: ['m1'],
      showsOtherChildren: false,
      classId: 'c1',
      mode: 'DRAFT',
    });
    expect(toCreateRequest(ready(), 'SUBMIT').mode).toBe('SUBMIT');
  });
  it('sends the creator\'s statement only when it was given and applies', () => {
    expect(toCreateRequest(ready(), 'SUBMIT').confirmNoUnconsentedStudents).toBeUndefined();
    expect(toCreateRequest(run(ready(), { type: 'confirm', value: true }), 'SUBMIT').confirmNoUnconsentedStudents).toBe(true);
    // Tagged children make the statement moot: it is not sent.
    const tagged = run(ready(), { type: 'toggleStudent', studentId: 'a' }, { type: 'confirm', value: true });
    expect(toCreateRequest(tagged, 'SUBMIT').confirmNoUnconsentedStudents).toBeUndefined();
  });
  it('sends text for a text item and no files', () => {
    const text = run(initialComposer({ kind: 'student', itemType: 'TEXT', visibility: 'GUARDIANS' }), { type: 'title', value: 'Story' }, { type: 'text', value: ' Once upon a time ' });
    const body = toCreateRequest(text, 'SUBMIT');
    expect(body).toMatchObject({ itemType: 'TEXT', textContent: 'Once upon a time' });
    expect(body).not.toHaveProperty('mediaIds');
    expect(body).not.toHaveProperty('showsOtherChildren');
  });
  it('captures for a student with the tags and the "others appear" flag', () => {
    const capture = run(
      withPhoto(initialComposer({ kind: 'capture', itemType: 'PHOTO', visibility: 'GUARDIANS' })),
      { type: 'title', value: 'Group work' },
      { type: 'toggleStudent', studentId: 'a' },
    );
    expect(toQuickCapture(capture, 'stu')).toEqual({
      itemType: 'PHOTO',
      studentId: 'stu',
      title: 'Group work',
      visibility: 'GUARDIANS',
      mediaIds: ['m1'],
      showsOtherChildren: true,
      taggedStudentIds: ['a'],
    });
  });
});

describe('editing an item', () => {
  const item = {
    id: 'i1',
    schoolId: 's1',
    itemType: 'PHOTO',
    title: 'Sunset',
    description: 'In class',
    reflection: 'I learned colours',
    visibility: 'GUARDIANS',
    effectiveVisibility: 'GUARDIANS',
    status: 'CHANGES_REQUESTED',
    classId: 'c1',
    subjectId: 'sub1',
    showsOtherChildren: false,
    mediaConfirmed: true,
    media: [{ mediaId: 'm1', kind: 'IMAGE', mimeType: 'image/jpeg', variants: ['THUMB'] }],
    student: { studentId: 'st' },
    capabilities: {},
    createdByRole: 'STUDENT',
  } as unknown as PortfolioItem;

  it('opens filled with what the item holds, and is not dirty', () => {
    const state = composerFromItem(item);
    expect(state).toMatchObject({ title: 'Sunset', reflection: 'I learned colours', classId: 'c1', confirm: true });
    expect(state.attachments).toHaveLength(1);
    expect(isDirty(state, state)).toBe(false);
    expect(isDirty(state, composerReducer(state, { type: 'title', value: 'Sunset ' }))).toBe(false);
    expect(isDirty(state, composerReducer(state, { type: 'title', value: 'Sunrise' }))).toBe(true);
  });
  it('sends every field again, clearing an emptied one, and no confirmation when nothing changed', () => {
    const opened = composerFromItem(item);
    const edited = composerReducer(opened, { type: 'description', value: '' });
    const body = toUpdateRequest(edited, opened, true);
    expect(body).toMatchObject({ title: 'Sunset', description: '', reflection: 'I learned colours', mediaIds: ['m1'], classId: 'c1' });
    expect(body.confirmNoUnconsentedStudents).toBeUndefined();
  });
  it('sends the confirmation again when the files changed (the API dropped the old one)', () => {
    const opened = composerFromItem(item);
    let edited = composerReducer(opened, { type: 'detach', key: 'm1' });
    edited = withPhoto(edited, 'n', 'm2');
    edited = composerReducer(edited, { type: 'confirm', value: true });
    const body = toUpdateRequest(edited, opened, true);
    expect(body.mediaIds).toEqual(['m2']);
    expect(body.confirmNoUnconsentedStudents).toBe(true);
  });
});

describe('the idempotency key', () => {
  it('is reused for the same request and renewed when the content changes', () => {
    let n = 0;
    const make = () => `key${++n}`;
    const first = keyFor(null, { a: 1 }, make);
    expect(keyFor(first, { a: 1 }, make)).toBe(first);
    expect(keyFor(first, { a: 2 }, make).key).toBe('key2');
  });
  it('is kept only after a failure without an answer', () => {
    const key = { key: 'k', fingerprint: 'f' };
    expect(keyAfterFailure(key, undefined)).toBe(key);
    expect(keyAfterFailure(key, 503)).toBe(key);
    expect(keyAfterFailure(key, 422)).toBeNull();
  });
});

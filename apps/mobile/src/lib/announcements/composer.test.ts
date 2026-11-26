import { describe, expect, it } from 'vitest';

import { caps, view } from './fixtures';
import {
  MAX_BODY_CHARS,
  MAX_TITLE_CHARS,
  blockedReason,
  composerFromAnnouncement,
  composerReducer,
  confirmationFor,
  editBlockedReason,
  editFromView,
  initialComposer,
  isDirty,
  keyAfterFailure,
  keyFor,
  requestChannels,
  toDraftRequest,
  toDraftUpdate,
  toMaterialEdit,
  type ComposerState,
} from './composer';

const teacher = caps();
const manager = caps({ schoolWide: true, classes: [], urgentAllowed: true, emailAvailable: true, smsAvailable: true, smsTypes: ['URGENT', 'SAFETY'] });

function filled(over: Partial<ComposerState> = {}): ComposerState {
  return { ...initialComposer(teacher, 'c1'), title: ' Field trip ', body: ' We go on Friday. ', ...over };
}

describe('the composer state', () => {
  it('starts as a normal announcement to the guardians of the class, by push, sent now', () => {
    const state = initialComposer(teacher, 'c1');
    expect(state).toMatchObject({ type: 'NORMAL', requiresAcknowledgement: false, channels: ['PUSH'], mode: 'PUBLISH', schedule: null });
    expect(state.audience).toEqual({ kind: 'CLASS', gradeLevelIds: [], classIds: ['c1'], roles: ['GUARDIAN'] });
  });
  it('a normal announcement cannot ask for a confirmation; a safer type asks by default', () => {
    let state = filled();
    state = composerReducer(state, { type: 'acknowledgement', value: true });
    expect(state.requiresAcknowledgement).toBe(false);
    state = composerReducer(state, { type: 'kind', value: 'URGENT' });
    expect(state.requiresAcknowledgement).toBe(true);
    state = composerReducer(state, { type: 'kind', value: 'IMPORTANT' });
    expect(state.requiresAcknowledgement).toBe(true);
    state = composerReducer(state, { type: 'kind', value: 'NORMAL' });
    expect(state.requiresAcknowledgement).toBe(false);
    expect(composerReducer(initialComposer(teacher), { type: 'kind', value: 'IMPORTANT' }).requiresAcknowledgement).toBe(false);
  });
  it('switches channels on and off', () => {
    let state = composerReducer(filled(), { type: 'channel', channel: 'EMAIL', on: true });
    expect(state.channels).toEqual(['PUSH', 'EMAIL']);
    state = composerReducer(state, { type: 'channel', channel: 'PUSH', on: false });
    expect(state.channels).toEqual(['EMAIL']);
  });
});

describe('blockedReason', () => {
  it('lets a complete announcement go', () => {
    expect(blockedReason(filled(), teacher)).toBeNull();
  });
  it('wants a title and a message', () => {
    expect(blockedReason(filled({ title: '   ' }), teacher)).toBe('title_empty');
    expect(blockedReason(filled({ body: '' }), teacher)).toBe('body_empty');
  });
  it('knows the limits', () => {
    expect(blockedReason(filled({ title: 'x'.repeat(MAX_TITLE_CHARS + 1) }), teacher)).toBe('title_too_long');
    expect(blockedReason(filled({ body: 'x'.repeat(MAX_BODY_CHARS + 1) }), teacher)).toBe('body_too_long');
    expect(blockedReason(filled({ body: 'x'.repeat(MAX_BODY_CHARS) }), teacher)).toBeNull();
  });
  it('blocks a type the person may not send, an audience the capabilities do not allow, and a schedule without a time', () => {
    expect(blockedReason(filled({ type: 'URGENT' }), teacher)).toBe('type_not_allowed');
    expect(blockedReason(filled({ type: 'URGENT' }), manager)).toBeNull();
    expect(blockedReason(filled({ audience: { kind: 'SCHOOL', gradeLevelIds: [], classIds: [], roles: ['GUARDIAN'] } }), teacher)).toBe('audience_kind_not_allowed');
    expect(blockedReason(filled({ audience: { kind: 'CLASS', gradeLevelIds: [], classIds: [], roles: ['GUARDIAN'] } }), teacher)).toBe('audience_no_targets');
    expect(blockedReason(filled({ mode: 'SCHEDULE' }), teacher)).toBe('schedule_missing');
    expect(blockedReason(filled({ mode: 'SCHEDULE', schedule: { date: '2026-12-01', minutes: 450 } }), teacher)).toBeNull();
  });
});

describe('the create request', () => {
  it('always asks for a DRAFT (publishing and scheduling come after the confirmation), with trimmed plain text', () => {
    expect(toDraftRequest(filled(), teacher, 'en')).toEqual({
      title: 'Field trip',
      body: 'We go on Friday.',
      bodyLanguageCode: 'en',
      type: 'NORMAL',
      requiresAcknowledgement: false,
      channels: ['IN_APP', 'PUSH'],
      audiences: [{ type: 'CLASS', classId: 'c1', roles: ['GUARDIAN'] }],
      mode: 'DRAFT',
    });
  });
  it('sends the text as typed: markup is not interpreted or stripped by the app', () => {
    expect(toDraftRequest(filled({ title: '<b>Hi</b>', body: '<script>alert(1)</script>' }), teacher, 'am')).toMatchObject({ title: '<b>Hi</b>', body: '<script>alert(1)</script>', bodyLanguageCode: 'am' });
  });
  it('drops channels the school does not offer for the type, always keeps in-app', () => {
    const state = filled({ type: 'URGENT', channels: ['PUSH', 'EMAIL', 'SMS'], requiresAcknowledgement: true });
    expect(requestChannels(state, teacher)).toEqual(['IN_APP', 'PUSH']);
    expect(requestChannels(state, manager)).toEqual(['IN_APP', 'PUSH', 'EMAIL', 'SMS']);
    expect(requestChannels({ ...state, type: 'NORMAL' }, manager)).toEqual(['IN_APP', 'PUSH', 'EMAIL']);
    expect(requestChannels({ ...state, channels: [] }, teacher)).toEqual(['IN_APP']);
  });
  it('never asks for an acknowledgement on a normal announcement', () => {
    expect(toDraftRequest(filled({ type: 'NORMAL', requiresAcknowledgement: true }), teacher, 'en').requiresAcknowledgement).toBe(false);
    expect(toDraftRequest(filled({ type: 'SAFETY', requiresAcknowledgement: true }), manager, 'en').requiresAcknowledgement).toBe(true);
  });
  it('the update of a draft is the same body without the mode', () => {
    const update = toDraftUpdate(filled(), teacher, 'en');
    expect(update).not.toHaveProperty('mode');
    expect(update).toMatchObject({ title: 'Field trip', audiences: [{ type: 'CLASS', classId: 'c1', roles: ['GUARDIAN'] }] });
  });
});

describe('editing a draft', () => {
  it('fills the composer from the announcement', () => {
    const draft = view({
      status: 'DRAFT',
      type: 'URGENT',
      channels: ['IN_APP', 'PUSH', 'SMS'],
      audiences: [{ type: 'CLASS', classId: 'c1', roles: ['GUARDIAN'] }],
    });
    expect(composerFromAnnouncement(draft, manager)).toMatchObject({ type: 'URGENT', title: 'Field trip', channels: ['PUSH', 'SMS'], mode: 'PUBLISH' });
    expect(composerFromAnnouncement({ ...draft, status: 'SCHEDULED' }, manager)?.mode).toBe('SCHEDULE');
  });
  it('refuses an audience only the web can show', () => {
    expect(composerFromAnnouncement(view({ status: 'DRAFT', audiences: [{ type: 'USER', userId: 'u', roles: ['GUARDIAN'] }] }), manager)).toBeNull();
  });
});

describe('isDirty', () => {
  it('is false for the state it opened with and for whitespace-only differences', () => {
    const state = filled();
    expect(isDirty(state, state)).toBe(false);
    expect(isDirty(state, { ...state, title: 'Field trip' })).toBe(false);
  });
  it('is true once anything the person chose changed', () => {
    const state = filled();
    for (const next of [
      { ...state, title: 'Other' },
      { ...state, body: 'Other' },
      { ...state, type: 'IMPORTANT' as const },
      { ...state, requiresAcknowledgement: true },
      { ...state, channels: [] },
      { ...state, mode: 'DRAFT' as const },
      { ...state, audience: { ...state.audience, roles: ['GUARDIAN' as const, 'STUDENT' as const] } },
    ]) {
      expect(isDirty(state, next)).toBe(true);
    }
  });
});

describe('editing a published announcement (material or not)', () => {
  const published = view({ title: 'Field trip', body: 'We go on Friday.' });
  it('starts unchanged and without a choice', () => {
    const edit = editFromView(published);
    expect(edit).toEqual({ title: 'Field trip', body: 'We go on Friday.', material: null });
    expect(editBlockedReason(edit, edit)).toBe('unchanged');
  });
  it('waits for the material choice before it can be sent', () => {
    const initial = editFromView(published);
    expect(editBlockedReason(initial, { ...initial, body: 'We go on Saturday.' })).toBe('material_missing');
    expect(toMaterialEdit({ ...initial, body: 'We go on Saturday.' })).toBeNull();
  });
  it('sends `material` true or false with the trimmed text and nothing else', () => {
    const initial = editFromView(published);
    expect(toMaterialEdit({ title: ' Field trip ', body: 'We go on Saturday. ', material: true })).toEqual({ title: 'Field trip', body: 'We go on Saturday.', material: true });
    expect(toMaterialEdit({ ...initial, body: 'We go on Fridays.', material: false })).toEqual({ title: 'Field trip', body: 'We go on Fridays.', material: false });
    expect(editBlockedReason(initial, { ...initial, body: 'We go on Fridays.', material: false })).toBeNull();
  });
  it('still checks the text', () => {
    const initial = editFromView(published);
    expect(editBlockedReason(initial, { ...initial, title: '', material: true })).toBe('title_empty');
    expect(editBlockedReason(initial, { ...initial, body: ' ', material: true })).toBe('body_empty');
    expect(editBlockedReason(initial, { ...initial, body: 'x'.repeat(MAX_BODY_CHARS + 1), material: true })).toBe('body_too_long');
  });
});

describe('the confirmation before sending', () => {
  const preview = { recipients: 40, guardians: 35, students: 0, staff: 5 };
  it('names the people, leaves zero groups out', () => {
    const confirmation = confirmationFor(preview, { type: 'NORMAL', requiresAcknowledgement: false, mode: 'PUBLISH' });
    expect(confirmation).toEqual({ total: 40, parts: [{ role: 'GUARDIAN', count: 35 }, { role: 'STAFF', count: 5 }], urgent: false, requiresAcknowledgement: false, scheduled: false });
  });
  it('warns for urgent and safety that quiet hours and mute are ignored', () => {
    expect(confirmationFor(preview, { type: 'URGENT', requiresAcknowledgement: true, mode: 'PUBLISH' }).urgent).toBe(true);
    expect(confirmationFor(preview, { type: 'SAFETY', requiresAcknowledgement: false, mode: 'SCHEDULE' })).toMatchObject({ urgent: true, scheduled: true });
    expect(confirmationFor(preview, { type: 'IMPORTANT', requiresAcknowledgement: true, mode: 'PUBLISH' }).urgent).toBe(false);
  });
});

describe('idempotency of the create call', () => {
  let n = 0;
  const newKey = () => `key-${++n}`;
  it('reuses the key for a retry of the same content only', () => {
    const body = toDraftRequest(filled(), teacher, 'en');
    const first = keyFor(null, body, newKey);
    expect(keyFor(first, toDraftRequest(filled(), teacher, 'en'), newKey)).toBe(first);
    expect(keyFor(first, toDraftRequest(filled({ title: 'Changed' }), teacher, 'en'), newKey).key).not.toBe(first.key);
  });
  it('keeps the key after a failure without an answer, drops it after an answer', () => {
    const key = keyFor(null, { a: 1 }, newKey);
    expect(keyAfterFailure(key, undefined)).toBe(key);
    expect(keyAfterFailure(key, 503)).toBe(key);
    expect(keyAfterFailure(key, 422)).toBeNull();
    expect(keyAfterFailure(key, 429)).toBeNull();
  });
});

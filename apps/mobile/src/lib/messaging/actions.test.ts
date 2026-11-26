import { describe, expect, it } from 'vitest';

import { messageActions, type ActionContext } from './actions';
import { accessOf } from './derive';
import { message, person, summary } from './fixtures';
import type { OutboxEntry } from './outbox';
import { buildTimeline } from './timeline';

const NOW = Date.parse('2026-11-26T10:05:00Z');
const open = accessOf(summary());
const ctx = (over: Partial<ActionContext> = {}): ActionContext => ({ access: open, canModerate: false, viewer: 'GUARDIAN', editWindowMinutes: 15, now: NOW, ...over });
const theirs = (over = {}) => message({ id: 'm1', sender: person(), ...over });
const mine = (over = {}) => message({ id: 'm2', mine: true, sender: person({ self: true }), sentAt: '2026-11-26T10:00:00Z', ...over });

describe('message actions', () => {
  it("another person's message: reply, react, report, block", () => {
    expect(messageActions(theirs(), ctx())).toEqual(['reply', 'react', 'report', 'block']);
  });

  it('an observer can only report: no reply, no reaction, no block, no edit', () => {
    const observer = accessOf(summary({ myRole: 'OBSERVER' }));
    expect(messageActions(theirs(), ctx({ access: observer }))).toEqual(['report']);
    expect(messageActions(theirs({ sender: person({ role: 'MEMBER' }) }), ctx({ access: observer, canModerate: false }))).not.toContain('block');
  });

  it('a closed conversation can still be reported but not answered', () => {
    const closed = accessOf(summary({ status: 'LOCKED' }));
    expect(messageActions(theirs(), ctx({ access: closed }))).toEqual(['report', 'block']);
  });

  it('a tombstone offers nothing, except "show again" to a moderator for a hidden message', () => {
    for (const status of ['DELETED', 'HIDDEN']) expect(messageActions(theirs({ status }), ctx())).toEqual([]);
    expect(messageActions(theirs({ status: 'HIDDEN' }), ctx({ canModerate: true }))).toEqual(['unhide']);
    expect(messageActions(theirs({ status: 'DELETED' }), ctx({ canModerate: true }))).toEqual([]);
  });

  it('edit is offered only inside the window and only when the API says so; delete when canDelete', () => {
    expect(messageActions(mine({ canEdit: true, canDelete: true }), ctx())).toEqual(['reply', 'react', 'edit', 'delete']);
    // 20 minutes later the window (15) is over
    expect(messageActions(mine({ canEdit: true, canDelete: true }), ctx({ now: NOW + 20 * 60_000 }))).toEqual(['reply', 'react', 'delete']);
    expect(messageActions(mine({ canEdit: false, canDelete: false }), ctx())).toEqual(['reply', 'react']);
    // an adult's message in an adult-student conversation: the API sends canEdit/canDelete false (record kept)
    expect(messageActions(mine({ canEdit: false, canDelete: false }), ctx({ viewer: 'TEACHER' }))).not.toContain('delete');
  });

  it('you never report or block yourself', () => {
    const actions = messageActions(mine({ canEdit: true, canDelete: true }), ctx());
    expect(actions).not.toContain('report');
    expect(actions).not.toContain('block');
  });

  it("a student is never offered a block of a guardian, and nobody a block of an observer", () => {
    const guardian = person({ actingAs: 'GUARDIAN' });
    expect(messageActions(theirs({ sender: guardian }), ctx({ viewer: 'STUDENT' }))).not.toContain('block');
    expect(messageActions(theirs({ sender: person({ role: 'OBSERVER' }) }), ctx())).not.toContain('block');
    expect(messageActions(theirs({ sender: undefined }), ctx())).not.toContain('block');
  });

  it('moderators may hide', () => {
    expect(messageActions(theirs(), ctx({ canModerate: true }))).toEqual(['reply', 'react', 'report', 'block', 'hide']);
  });

  it('system messages have no actions', () => {
    expect(messageActions(theirs({ messageType: 'SYSTEM' }), ctx({ canModerate: true }))).toEqual([]);
  });
});

describe('timeline', () => {
  const entry = (seq: number): OutboxEntry => ({
    clientMessageId: `c${seq}`,
    conversationId: 'c1',
    body: 'x',
    mediaIds: [],
    replyToMessageId: undefined,
    createdAt: 0,
    seq,
    status: 'waiting',
    attempts: 1,
    nextAttemptAt: 1,
    problem: undefined,
    gaveUp: false,
  });
  it('puts the unsent messages first, newest first, then the stored ones', () => {
    const items = buildTimeline([message({ id: 'a' }), message({ id: 'b' })], [entry(1), entry(2)]);
    expect(items.map((item) => item.key)).toEqual(['pending-c2', 'pending-c1', 'a', 'b']);
    expect(items.map((item) => item.kind)).toEqual(['pending', 'pending', 'message', 'message']);
  });
});

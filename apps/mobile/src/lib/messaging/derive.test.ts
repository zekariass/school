import { describe, expect, it } from 'vitest';

import {
  accessOf,
  badgeText,
  editMinutesLeft,
  editRemainingMs,
  groupByChild,
  incomingFromOthers,
  inboxTitle,
  isTombstone,
  lastSeenMessageId,
  messageImages,
  messageText,
  participantLabel,
  previewOf,
  safeguardingNotice,
  seenAtOf,
  sendable,
  unreadText,
} from './derive';
import { message, person, summary } from './fixtures';

describe('tombstones', () => {
  it('deleted and hidden messages never show text or images, whatever the payload carries', () => {
    for (const status of ['DELETED', 'HIDDEN']) {
      const tomb = message({ status, body: 'should not be here', attachments: [{ mediaId: 'x', sortOrder: 0 }] });
      expect(isTombstone(tomb)).toBe(true);
      expect(messageText(tomb)).toBeNull();
      expect(messageImages(tomb)).toEqual([]);
      expect(seenAtOf({ ...tomb, mine: true, seenAt: '2026-11-26T11:00:00Z' })).toBeNull();
    }
  });
  it('a normal message keeps its text and its images in order', () => {
    const m = message({
      body: 'hi',
      attachments: [
        { mediaId: 'b', sortOrder: 1 },
        { mediaId: 'a', sortOrder: 0 },
      ],
    });
    expect(messageText(m)).toBe('hi');
    expect(messageImages(m).map((image) => image.mediaId)).toEqual(['a', 'b']);
    expect(messageText(message({ body: '' }))).toBeNull();
  });
});

describe('observers and read-only conversations', () => {
  it('an observer never gets a composer, even when the payload says canPost', () => {
    expect(accessOf(summary({ myRole: 'OBSERVER', canPost: true }))).toEqual({ canCompose: false, banner: 'observer' });
  });
  it('a closed or pending conversation is read-only', () => {
    expect(accessOf(summary({ status: 'LOCKED' }))).toEqual({ canCompose: false, banner: 'closed' });
    expect(accessOf(summary({ status: 'ARCHIVED' }))).toEqual({ canCompose: false, banner: 'closed' });
    expect(accessOf(summary({ status: 'REJECTED' }))).toEqual({ canCompose: false, banner: 'closed' });
    expect(accessOf(summary({ status: 'PENDING_APPROVAL' }))).toEqual({ canCompose: false, banner: 'pending' });
    expect(accessOf(summary({ status: 'something new' }))).toEqual({ canCompose: false, banner: 'closed' });
  });
  it('moderators-only posting shows its own notice', () => {
    expect(accessOf(summary({ canPost: false }))).toEqual({ canCompose: false, banner: 'restricted' });
  });
  it('a member of an open conversation can compose', () => {
    expect(accessOf(summary())).toEqual({ canCompose: true, banner: null });
  });
});

describe('safeguarding notices', () => {
  it('tells a student only when the API says a guardian can see the conversation', () => {
    expect(safeguardingNotice(summary({ myActingAs: 'STUDENT', directKind: 'TEACHER_STUDENT', guardiansCanSee: true }))).toBe('guardiansCanSee');
    expect(safeguardingNotice(summary({ myActingAs: 'STUDENT', directKind: 'TEACHER_STUDENT', guardiansCanSee: false }))).toBeNull();
    expect(safeguardingNotice(summary({ myActingAs: 'STUDENT', directKind: 'TEACHER_STUDENT' }))).toBeNull();
  });
  it('always reminds the teacher of a thread with a student', () => {
    expect(safeguardingNotice(summary({ myActingAs: 'TEACHER', directKind: 'TEACHER_STUDENT' }))).toBe('teacherStudent');
    expect(safeguardingNotice(summary({ myActingAs: 'TEACHER', directKind: 'GUARDIAN_TEACHER' }))).toBeNull();
  });
});

describe('who is writing', () => {
  it('yourself is "you"; a teacher or student by name', () => {
    expect(participantLabel(person({ self: true }), 'TEACHER')).toEqual({ kind: 'you' });
    expect(participantLabel(person(), 'GUARDIAN')).toEqual({ kind: 'name', name: 'Ms Hanna' });
    expect(participantLabel(person({ displayName: undefined }), 'GUARDIAN')).toEqual({ kind: 'member' });
    expect(participantLabel(undefined, 'GUARDIAN')).toEqual({ kind: 'member' });
  });
  it('a guardian is only a role label, never named, and the label depends on who looks', () => {
    const mother = person({ actingAs: 'GUARDIAN', displayName: 'Real Name', guardian: { relationshipType: 'MOTHER', studentGivenName: 'Abebe' } });
    expect(participantLabel(mother, 'TEACHER')).toEqual({ kind: 'guardianOf', relationship: 'MOTHER', child: 'Abebe' });
    // another guardian sees only "Parent"; a student sees a relation or "Your guardian"
    expect(participantLabel(person({ actingAs: 'GUARDIAN', guardian: {} }), 'GUARDIAN')).toEqual({ kind: 'parent' });
    expect(participantLabel(person({ actingAs: 'GUARDIAN' }), 'GUARDIAN')).toEqual({ kind: 'parent' });
    expect(participantLabel(person({ actingAs: 'GUARDIAN', guardian: { relationshipType: 'FATHER' } }), 'STUDENT')).toEqual({ kind: 'yourRelation', relationship: 'FATHER' });
    expect(participantLabel(person({ actingAs: 'GUARDIAN', guardian: {} }), 'STUDENT')).toEqual({ kind: 'yourGuardian' });
    // the displayName a payload might carry for a guardian is never used
    expect(JSON.stringify(participantLabel(mother, 'GUARDIAN'))).not.toContain('Real Name');
  });
});

describe('edit window', () => {
  const sent = '2026-11-26T10:00:00Z';
  const at = (minutes: number) => Date.parse(sent) + minutes * 60_000;
  it('counts down from the send time', () => {
    const m = message({ canEdit: true, sentAt: sent });
    expect(editRemainingMs(m, 15, at(0))).toBe(15 * 60_000);
    expect(editRemainingMs(m, 15, at(10))).toBe(5 * 60_000);
    expect(editRemainingMs(m, 15, at(15))).toBe(0);
    expect(editRemainingMs(m, 15, at(30))).toBe(0);
    expect(editMinutesLeft(editRemainingMs(m, 15, at(14.5)))).toBe(1);
    expect(editMinutesLeft(editRemainingMs(m, 15, at(10)))).toBe(5);
    expect(editMinutesLeft(0)).toBeNull();
  });
  it('is closed when the API says canEdit is false, editing is off (0 minutes) or the message is a tombstone', () => {
    expect(editRemainingMs(message({ canEdit: false, sentAt: sent }), 15, at(1))).toBe(0);
    expect(editRemainingMs(message({ canEdit: true, sentAt: sent }), 0, at(1))).toBe(0);
    expect(editRemainingMs(message({ canEdit: true, sentAt: sent, status: 'DELETED' }), 15, at(1))).toBe(0);
    expect(editRemainingMs(message({ canEdit: true }), 15, at(1))).toBeGreaterThan(0);
    expect(editRemainingMs(message({ canEdit: true, sentAt: undefined }), 15, at(1))).toBe(0);
  });
});

describe('the inbox', () => {
  it('previews text but never a body for a tombstone', () => {
    expect(previewOf(summary({ lastMessage: { messageId: 'm', messageType: 'TEXT', status: 'SENT', preview: 'See you' } }))).toEqual({ kind: 'text', text: 'See you' });
    for (const status of ['DELETED', 'HIDDEN']) {
      expect(previewOf(summary({ lastMessage: { messageId: 'm', messageType: 'TEXT', status, preview: 'leaked' } }))).toEqual({ kind: 'tombstone' });
    }
    expect(previewOf(summary({ lastMessage: { messageId: 'm', messageType: 'MEDIA', status: 'SENT' } }))).toEqual({ kind: 'image' });
    expect(previewOf(summary())).toEqual({ kind: 'none' });
  });

  it('titles a direct thread by the other person and a group by its title or kind', () => {
    const counterpart = person();
    expect(inboxTitle(summary({ counterpart }))).toEqual({ kind: 'person', person: counterpart });
    expect(inboxTitle(summary({ type: 'GROUP', groupKind: 'STAFF', title: 'Grade 4 staff' }))).toEqual({ kind: 'title', title: 'Grade 4 staff' });
    expect(inboxTitle(summary({ type: 'CLASS', groupKind: 'CLASS_PARENTS', title: '  ' }))).toEqual({ kind: 'group', groupKind: 'CLASS_PARENTS' });
  });

  it('groups a guardian inbox by child in order of first appearance, conversations about nobody last', () => {
    const a1 = summary({ id: 'a1', onBehalfOfStudentId: 'kid-a', contextStudent: { studentId: 'kid-a', displayName: 'Abebe' } });
    const none = summary({ id: 'n1' });
    const b1 = summary({ id: 'b1', contextStudent: { studentId: 'kid-b', displayName: 'Bethel' } });
    const a2 = summary({ id: 'a2', onBehalfOfStudentId: 'kid-a' });
    const sections = groupByChild([a1, none, b1, a2]);
    expect(sections.map((section) => [section.key, section.childName, section.items.map((item) => item.id)])).toEqual([
      ['kid-a', 'Abebe', ['a1', 'a2']],
      ['kid-b', 'Bethel', ['b1']],
      ['none', null, ['n1']],
    ]);
  });

  it('formats badges', () => {
    expect(badgeText(undefined)).toBeUndefined();
    expect(badgeText(0)).toBeUndefined();
    expect(badgeText(3)).toBe('3');
    expect(badgeText(100)).toBe('99+');
    expect(unreadText(0)).toBeNull();
    expect(unreadText(120)).toBe('99+');
  });
});

describe('Seen', () => {
  it('only the newest of my own seen messages carries the line; observers and tombstones never', () => {
    const list = [
      message({ id: 'm4', mine: true }),
      message({ id: 'm3', mine: true, seenAt: '2026-11-26T11:00:00Z', status: 'DELETED' }),
      message({ id: 'm2', mine: true, seenAt: '2026-11-26T11:00:00Z' }),
      message({ id: 'm1', mine: true, seenAt: '2026-11-26T10:30:00Z' }),
    ];
    expect(lastSeenMessageId(list)).toBe('m2');
    expect(lastSeenMessageId([message({ id: 'x', mine: false, seenAt: '2026-11-26T11:00:00Z' })])).toBeNull();
    expect(lastSeenMessageId([])).toBeNull();
  });
});

describe('composing and announcing', () => {
  it('needs text or an image, within the limit', () => {
    expect(sendable('', 0, 2000)).toBe(false);
    expect(sendable('   ', 0, 2000)).toBe(false);
    expect(sendable('hi', 0, 2000)).toBe(true);
    expect(sendable('', 1, 2000)).toBe(true);
    expect(sendable('x'.repeat(2001), 0, 2000)).toBe(false);
  });
  it('announces only new messages from other people', () => {
    const old = message({ id: 'm1' });
    const fresh = [old, message({ id: 'm2' }), message({ id: 'm3', mine: true }), message({ id: 'm4', status: 'DELETED' }), message({ id: 'm5', messageType: 'SYSTEM' })];
    expect(incomingFromOthers([old], fresh).map((m) => m.id)).toEqual(['m2']);
  });
});

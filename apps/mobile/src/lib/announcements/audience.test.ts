import { describe, expect, it } from 'vitest';

import {
  allowedKinds,
  allowedRoles,
  allowedTypes,
  audienceProblem,
  choiceFromAudiences,
  defaultChoice,
  mayRequireAcknowledgement,
  optionalChannels,
  smsOffered,
  toAudiences,
  toggle,
  type AudienceChoice,
} from './audience';
import { caps } from './fixtures';

const manager = caps({ schoolWide: true, classes: [], urgentAllowed: true, studentsEnabled: true });
const teacher = caps();

describe('what the capabilities allow', () => {
  it('a manager addresses the school, grade levels and classes, and the staff', () => {
    expect(allowedKinds(manager)).toEqual(['SCHOOL', 'GRADE_LEVEL', 'CLASS']);
    expect(allowedRoles(manager)).toEqual(['GUARDIAN', 'STAFF', 'STUDENT']);
  });
  it('a teacher addresses only their own classes, guardians only unless students are enabled', () => {
    expect(allowedKinds(teacher)).toEqual(['CLASS']);
    expect(allowedRoles(teacher)).toEqual(['GUARDIAN']);
    expect(allowedRoles(caps({ studentsEnabled: true }))).toEqual(['GUARDIAN', 'STUDENT']);
  });
  it('someone with no classes and no school-wide right addresses nobody', () => {
    expect(allowedKinds(caps({ classes: [] }))).toEqual([]);
  });
  it('urgent and safety need the urgent permission', () => {
    expect(allowedTypes(caps({ urgentAllowed: false }))).toEqual(['NORMAL', 'IMPORTANT']);
    expect(allowedTypes(caps({ urgentAllowed: true }))).toEqual(['NORMAL', 'IMPORTANT', 'URGENT', 'SAFETY']);
  });
  it('an acknowledgement is asked above NORMAL only', () => {
    expect(mayRequireAcknowledgement('NORMAL')).toBe(false);
    expect(mayRequireAcknowledgement('IMPORTANT')).toBe(true);
  });
  it('offers email when the school has it and SMS only for the types it texts', () => {
    const c = caps({ emailAvailable: true, smsAvailable: true, smsTypes: ['URGENT', 'SAFETY'] });
    expect(optionalChannels(c, 'NORMAL')).toEqual(['PUSH', 'EMAIL']);
    expect(optionalChannels(c, 'URGENT')).toEqual(['PUSH', 'EMAIL', 'SMS']);
    expect(smsOffered(c, 'IMPORTANT')).toBe(false);
    expect(optionalChannels(caps(), 'URGENT')).toEqual(['PUSH']);
  });
});

describe('defaultChoice', () => {
  it('opens on the class it was started from', () => {
    expect(defaultChoice(teacher, 'c2')).toEqual({ kind: 'CLASS', gradeLevelIds: [], classIds: ['c2'], roles: ['GUARDIAN'] });
  });
  it('ignores a class a teacher may not address', () => {
    expect(defaultChoice(teacher, 'other').classIds).toEqual([]);
  });
  it('takes the only class of a teacher, and the whole school for a manager', () => {
    expect(defaultChoice(caps({ classes: [{ id: 'c9', name: 'X' }] })).classIds).toEqual(['c9']);
    expect(defaultChoice(manager).kind).toBe('SCHOOL');
    expect(defaultChoice(manager, 'any-class').classIds).toEqual(['any-class']);
  });
});

describe('toAudiences', () => {
  it('writes one row for the school and one per class or grade level, with the roles in a stable order', () => {
    expect(toAudiences({ kind: 'SCHOOL', gradeLevelIds: [], classIds: [], roles: ['STAFF', 'GUARDIAN'] })).toEqual([{ type: 'SCHOOL', roles: ['GUARDIAN', 'STAFF'] }]);
    expect(toAudiences({ kind: 'CLASS', gradeLevelIds: ['ignored'], classIds: ['c1', 'c2', 'c1'], roles: ['GUARDIAN'] })).toEqual([
      { type: 'CLASS', classId: 'c1', roles: ['GUARDIAN'] },
      { type: 'CLASS', classId: 'c2', roles: ['GUARDIAN'] },
    ]);
    expect(toAudiences({ kind: 'GRADE_LEVEL', gradeLevelIds: ['g1'], classIds: ['ignored'], roles: ['STUDENT', 'GUARDIAN'] })).toEqual([
      { type: 'GRADE_LEVEL', gradeLevelId: 'g1', roles: ['GUARDIAN', 'STUDENT'] },
    ]);
  });
});

describe('audienceProblem', () => {
  const choice = (over: Partial<AudienceChoice>): AudienceChoice => ({ kind: 'CLASS', gradeLevelIds: [], classIds: ['c1'], roles: ['GUARDIAN'], ...over });
  it('accepts a teacher addressing an own class', () => {
    expect(audienceProblem(choice({}), teacher)).toBeNull();
  });
  it('wants roles and targets', () => {
    expect(audienceProblem(choice({ roles: [] }), teacher)).toBe('no_roles');
    expect(audienceProblem(choice({ classIds: [] }), teacher)).toBe('no_targets');
    expect(audienceProblem(choice({ kind: 'GRADE_LEVEL' }), manager)).toBe('no_targets');
  });
  it('refuses what the capabilities do not allow: another class, the school, staff, students', () => {
    expect(audienceProblem(choice({ classIds: ['c1', 'zzz'] }), teacher)).toBe('class_not_allowed');
    expect(audienceProblem(choice({ kind: 'SCHOOL' }), teacher)).toBe('kind_not_allowed');
    expect(audienceProblem(choice({ roles: ['STAFF'] }), teacher)).toBe('role_not_allowed');
    expect(audienceProblem(choice({ roles: ['STUDENT'] }), teacher)).toBe('role_not_allowed');
    expect(audienceProblem(choice({ roles: ['STUDENT'] }), caps({ studentsEnabled: true }))).toBeNull();
  });
  it('lets a manager address any class of the school', () => {
    expect(audienceProblem(choice({ classIds: ['anything'] }), manager)).toBeNull();
    expect(audienceProblem({ kind: 'SCHOOL', gradeLevelIds: [], classIds: [], roles: ['GUARDIAN', 'STAFF'] }, manager)).toBeNull();
  });
});

describe('choiceFromAudiences (editing a draft)', () => {
  it('rebuilds what the phone writes', () => {
    expect(choiceFromAudiences([{ type: 'CLASS', classId: 'c1', roles: ['GUARDIAN'] }, { type: 'CLASS', classId: 'c2', roles: ['GUARDIAN'] }])).toEqual({
      kind: 'CLASS',
      gradeLevelIds: [],
      classIds: ['c1', 'c2'],
      roles: ['GUARDIAN'],
    });
    expect(choiceFromAudiences([{ type: 'SCHOOL', roles: ['GUARDIAN', 'STAFF'] }])?.roles).toEqual(['GUARDIAN', 'STAFF']);
  });
  it('gives up on audiences the phone cannot show (chosen people, a campus, mixed kinds, differing roles)', () => {
    expect(choiceFromAudiences([])).toBeNull();
    expect(choiceFromAudiences([{ type: 'USER', userId: 'u', roles: ['GUARDIAN'] }])).toBeNull();
    expect(choiceFromAudiences([{ type: 'CAMPUS', campusId: 'k', roles: ['GUARDIAN'] }])).toBeNull();
    expect(choiceFromAudiences([{ type: 'SCHOOL', roles: ['GUARDIAN'] }, { type: 'CLASS', classId: 'c', roles: ['GUARDIAN'] }])).toBeNull();
    expect(choiceFromAudiences([{ type: 'CLASS', classId: 'a', roles: ['GUARDIAN'] }, { type: 'CLASS', classId: 'b', roles: ['STAFF'] }])).toBeNull();
  });
});

describe('toggle', () => {
  it('adds and removes', () => {
    expect(toggle(['a'], 'b')).toEqual(['a', 'b']);
    expect(toggle(['a', 'b'], 'a')).toEqual(['b']);
  });
});

import type { AnnouncementAudienceInput, AnnouncementAudienceView, AnnouncementCapabilities, AnnouncementType, AudienceRole, Channel } from './types';

/**
 * What a person may address, taken only from `GET .../announcements/capabilities` (the API stays the authority and checks it all
 * again at publish): a manager may address the school, a grade level or any class and the staff; a teacher only their own classes
 * and only guardians (and students when the school allows). People chosen one by one are a web feature.
 */

export type AudienceKind = 'SCHOOL' | 'GRADE_LEVEL' | 'CLASS';

export interface AudienceChoice {
  kind: AudienceKind;
  gradeLevelIds: string[];
  classIds: string[];
  roles: AudienceRole[];
}

type Caps = Pick<AnnouncementCapabilities, 'schoolWide' | 'classes' | 'studentsEnabled' | 'urgentAllowed' | 'emailAvailable' | 'smsAvailable' | 'smsTypes'>;

export function allowedKinds(caps: Caps): AudienceKind[] {
  if (caps.schoolWide) return ['SCHOOL', 'GRADE_LEVEL', 'CLASS'];
  return caps.classes.length > 0 ? ['CLASS'] : [];
}

/** Guardians always; staff only for a manager; students only when the school addresses them. */
export function allowedRoles(caps: Caps): AudienceRole[] {
  const roles: AudienceRole[] = ['GUARDIAN'];
  if (caps.schoolWide) roles.push('STAFF');
  if (caps.studentsEnabled) roles.push('STUDENT');
  return roles;
}

/** The classes a person may pick: all of the school for a manager (searched on the server), else the list in the capabilities. */
export function ownClasses(caps: Pick<AnnouncementCapabilities, 'schoolWide' | 'classes'>): { id: string; name: string }[] {
  return caps.schoolWide ? [] : caps.classes;
}

export function defaultChoice(caps: Caps, classId?: string | null): AudienceChoice {
  const kinds = allowedKinds(caps);
  const single = !caps.schoolWide && caps.classes.length === 1 ? caps.classes[0]!.id : null;
  const preselected = classId && (caps.schoolWide || caps.classes.some((cls) => cls.id === classId)) ? classId : single;
  const kind: AudienceKind = preselected ? 'CLASS' : (kinds[0] ?? 'CLASS');
  return { kind, gradeLevelIds: [], classIds: preselected ? [preselected] : [], roles: ['GUARDIAN'] };
}

export type AudienceProblem = 'no_roles' | 'no_targets' | 'role_not_allowed' | 'kind_not_allowed' | 'class_not_allowed' | 'too_many';

/** Most audience rows one announcement carries (the API bounds them too). */
export const MAX_TARGETS = 50;

export function audienceProblem(choice: AudienceChoice, caps: Caps): AudienceProblem | null {
  if (!allowedKinds(caps).includes(choice.kind)) return 'kind_not_allowed';
  if (choice.roles.length === 0) return 'no_roles';
  const allowed = allowedRoles(caps);
  if (choice.roles.some((role) => !allowed.includes(role))) return 'role_not_allowed';
  if (choice.kind === 'GRADE_LEVEL' && choice.gradeLevelIds.length === 0) return 'no_targets';
  if (choice.kind === 'CLASS') {
    if (choice.classIds.length === 0) return 'no_targets';
    if (!caps.schoolWide && choice.classIds.some((id) => !caps.classes.some((cls) => cls.id === id))) return 'class_not_allowed';
  }
  if (choice.gradeLevelIds.length > MAX_TARGETS || choice.classIds.length > MAX_TARGETS) return 'too_many';
  return null;
}

/** The audience rows of the request. Roles go in a stable order (guardians, students, staff). */
export function toAudiences(choice: AudienceChoice): AnnouncementAudienceInput[] {
  const roles = ROLE_ORDER.filter((role) => choice.roles.includes(role));
  switch (choice.kind) {
    case 'SCHOOL':
      return [{ type: 'SCHOOL', roles }];
    case 'GRADE_LEVEL':
      return unique(choice.gradeLevelIds).map((gradeLevelId) => ({ type: 'GRADE_LEVEL' as const, gradeLevelId, roles }));
    case 'CLASS':
      return unique(choice.classIds).map((classId) => ({ type: 'CLASS' as const, classId, roles }));
  }
}

const ROLE_ORDER: AudienceRole[] = ['GUARDIAN', 'STUDENT', 'STAFF'];

function unique(ids: string[]): string[] {
  return [...new Set(ids)];
}

export function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((item) => item !== value) : [...list, value];
}

/**
 * The audience of an existing announcement turned back into a choice (editing a draft). Rows the mobile builder cannot show
 * (chosen people, a campus) come back as `null`: the draft is then edited on the web.
 */
export function choiceFromAudiences(audiences: readonly AnnouncementAudienceView[]): AudienceChoice | null {
  const first = audiences[0];
  if (!first) return null;
  if (audiences.some((audience) => audience.type === 'USER' || audience.type === 'CAMPUS' || audience.type !== first.type)) return null;
  const roles = ROLE_ORDER.filter((role) => audiences.every((audience) => audience.roles.includes(role)));
  if (roles.length === 0) return null;
  // Rows of one kind share their roles in everything the mobile builder writes; anything else is the web's.
  if (audiences.some((audience) => audience.roles.length !== roles.length)) return null;
  switch (first.type) {
    case 'SCHOOL':
      return { kind: 'SCHOOL', gradeLevelIds: [], classIds: [], roles };
    case 'GRADE_LEVEL':
      return { kind: 'GRADE_LEVEL', gradeLevelIds: audiences.flatMap((a) => (a.gradeLevelId ? [a.gradeLevelId] : [])), classIds: [], roles };
    case 'CLASS':
      return { kind: 'CLASS', gradeLevelIds: [], classIds: audiences.flatMap((a) => (a.classId ? [a.classId] : [])), roles };
    default:
      return null;
  }
}

// --- types and channels ---------------------------------------------------------------------------

/** URGENT and SAFETY need the urgent permission. */
export function allowedTypes(caps: Pick<AnnouncementCapabilities, 'urgentAllowed'>): AnnouncementType[] {
  return caps.urgentAllowed ? ['NORMAL', 'IMPORTANT', 'URGENT', 'SAFETY'] : ['NORMAL', 'IMPORTANT'];
}

/** An acknowledgement can be asked above NORMAL only. */
export function mayRequireAcknowledgement(type: AnnouncementType): boolean {
  return type !== 'NORMAL';
}

/** Whether the school offers SMS for this type (it names the types it texts). */
export function smsOffered(caps: Pick<AnnouncementCapabilities, 'smsAvailable' | 'smsTypes'>, type: AnnouncementType): boolean {
  return caps.smsAvailable && caps.smsTypes.includes(type);
}

/** The optional channels the composer can offer for this type: push is the default one, email and SMS when the school has them. */
export function optionalChannels(caps: Pick<AnnouncementCapabilities, 'emailAvailable' | 'smsAvailable' | 'smsTypes'>, type: AnnouncementType): Channel[] {
  const channels: Channel[] = ['PUSH'];
  if (caps.emailAvailable) channels.push('EMAIL');
  if (smsOffered(caps, type)) channels.push('SMS');
  return channels;
}

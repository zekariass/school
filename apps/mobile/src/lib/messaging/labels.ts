/**
 * Catalog keys by enum value. Written out (not built with a template string) so the type checker verifies that every key exists
 * in `mobile.messaging.*` and a test checks that each exists in en and am.
 */

export const RELATIONSHIP_LABEL = {
  MOTHER: 'mobile.messaging.relationship.mother',
  FATHER: 'mobile.messaging.relationship.father',
  GRANDPARENT: 'mobile.messaging.relationship.grandparent',
  SIBLING: 'mobile.messaging.relationship.sibling',
  AUNT_UNCLE: 'mobile.messaging.relationship.auntUncle',
  LEGAL_GUARDIAN: 'mobile.messaging.relationship.legalGuardian',
  OTHER: 'mobile.messaging.relationship.other',
} as const;

export const GROUP_KIND_LABEL = {
  CLASS_STUDENTS: 'mobile.messaging.groupKind.classStudents',
  CLASS_PARENTS: 'mobile.messaging.groupKind.classParents',
  STAFF: 'mobile.messaging.groupKind.staff',
  STUDENT_GROUP: 'mobile.messaging.groupKind.studentGroup',
  SCHOOL_STAFF: 'mobile.messaging.groupKind.schoolStaff',
  GROUP: 'mobile.messaging.groupKind.group',
} as const;

export const GROUP_KIND_HINT = {
  CLASS_STUDENTS: 'mobile.messaging.groupKindHint.classStudents',
  CLASS_PARENTS: 'mobile.messaging.groupKindHint.classParents',
  STAFF: 'mobile.messaging.groupKindHint.staff',
  STUDENT_GROUP: 'mobile.messaging.groupKindHint.studentGroup',
  SCHOOL_STAFF: 'mobile.messaging.groupKindHint.schoolStaff',
} as const;

export const GROUP_STATUS_LABEL = {
  ACTIVE: 'mobile.messaging.groupStatus.active',
  PENDING_APPROVAL: 'mobile.messaging.groupStatus.pending',
  LOCKED: 'mobile.messaging.groupStatus.locked',
  ARCHIVED: 'mobile.messaging.groupStatus.archived',
  REJECTED: 'mobile.messaging.groupStatus.rejected',
} as const;

export const ROLE_LABEL = {
  OWNER: 'mobile.messaging.people.role.owner',
  MODERATOR: 'mobile.messaging.people.role.moderator',
  MEMBER: 'mobile.messaging.people.role.member',
  OBSERVER: 'mobile.messaging.people.role.observer',
} as const;

export const ACTING_AS_LABEL = {
  TEACHER: 'mobile.messaging.people.actingAs.teacher',
  GUARDIAN: 'mobile.messaging.people.actingAs.guardian',
  STUDENT: 'mobile.messaging.people.actingAs.student',
  STAFF: 'mobile.messaging.people.actingAs.staff',
} as const;

/** The messaging keys that are built from API codes rather than enums: `error.<code>` problems come localized from the API. */
export const MUTE_CHOICES = [
  { key: 'mobile.messaging.mute.hour', hours: 1 },
  { key: 'mobile.messaging.mute.eightHours', hours: 8 },
  { key: 'mobile.messaging.mute.week', hours: 24 * 7 },
] as const;

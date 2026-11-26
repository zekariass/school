/**
 * Catalog keys by enum value. Written out (not built with a template string) so the type checker can verify that every key
 * exists in `mobile.announcements.*`.
 */

export const TYPE_LABEL = {
  NORMAL: 'mobile.announcements.type.normal',
  IMPORTANT: 'mobile.announcements.type.important',
  URGENT: 'mobile.announcements.type.urgent',
  SAFETY: 'mobile.announcements.type.safety',
} as const;

export const TYPE_HINT = {
  NORMAL: 'mobile.announcements.typeHint.normal',
  IMPORTANT: 'mobile.announcements.typeHint.important',
  URGENT: 'mobile.announcements.typeHint.urgent',
  SAFETY: 'mobile.announcements.typeHint.safety',
} as const;

export const ROLE_LABEL = {
  GUARDIAN: 'mobile.announcements.audience.role.guardian',
  STUDENT: 'mobile.announcements.audience.role.student',
  STAFF: 'mobile.announcements.audience.role.staff',
} as const;

export const ROLE_HINT = {
  GUARDIAN: 'mobile.announcements.audience.roleHint.guardian',
  STUDENT: 'mobile.announcements.audience.roleHint.student',
  STAFF: 'mobile.announcements.audience.roleHint.staff',
} as const;

export const KIND_LABEL = {
  SCHOOL: 'mobile.announcements.audience.kind.school',
  GRADE_LEVEL: 'mobile.announcements.audience.kind.grade_level',
  CLASS: 'mobile.announcements.audience.kind.class',
} as const;

export const AUDIENCE_LINE = {
  SCHOOL: 'mobile.announcements.audience.line.school',
  CAMPUS: 'mobile.announcements.audience.line.campus',
  GRADE_LEVEL: 'mobile.announcements.audience.line.grade_level',
  CLASS: 'mobile.announcements.audience.line.class',
  USER: 'mobile.announcements.audience.line.user',
} as const;

export const MANAGE_STATUS = {
  DRAFT: 'mobile.announcements.manage.status.draft',
  SCHEDULED: 'mobile.announcements.manage.status.scheduled',
  PUBLISHED: 'mobile.announcements.manage.status.published',
  WITHDRAWN: 'mobile.announcements.manage.status.withdrawn',
} as const;

export const MINE_STATUS = {
  ALL: 'mobile.announcements.mine.status.all',
  DRAFT: 'mobile.announcements.mine.status.draft',
  SCHEDULED: 'mobile.announcements.mine.status.scheduled',
  PUBLISHED: 'mobile.announcements.mine.status.published',
  WITHDRAWN: 'mobile.announcements.mine.status.withdrawn',
} as const;

export const REPORT_FILTER = {
  UNACKNOWLEDGED: 'mobile.announcements.report.filter.unacknowledged',
  UNREAD: 'mobile.announcements.report.filter.unread',
  ACKNOWLEDGED: 'mobile.announcements.report.filter.acknowledged',
  FAILED: 'mobile.announcements.report.filter.failed',
} as const;

export const REPORT_NONE = {
  UNACKNOWLEDGED: 'mobile.announcements.report.none.unacknowledged',
  UNREAD: 'mobile.announcements.report.none.unread',
  ACKNOWLEDGED: 'mobile.announcements.report.none.acknowledged',
  FAILED: 'mobile.announcements.report.none.failed',
} as const;

export const CONFIRM_PART = {
  GUARDIAN: 'mobile.announcements.confirm.part.guardian',
  STUDENT: 'mobile.announcements.confirm.part.student',
  STAFF: 'mobile.announcements.confirm.part.staff',
} as const;

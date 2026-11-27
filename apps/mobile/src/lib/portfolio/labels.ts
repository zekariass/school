/**
 * Catalog keys by enum value. Written out (not built with a template string) so the type checker verifies the shape and a
 * test checks that every key exists in `mobile.portfolio.*` in en and am.
 */
import type { PortfolioItemType, PortfolioStatus, PortfolioVisibility } from './types';

export const STATUS_LABEL = {
  DRAFT: 'mobile.portfolio.status.draft',
  SUBMITTED: 'mobile.portfolio.status.submitted',
  CHANGES_REQUESTED: 'mobile.portfolio.status.changesRequested',
  APPROVED: 'mobile.portfolio.status.approved',
  REJECTED: 'mobile.portfolio.status.rejected',
  ARCHIVED: 'mobile.portfolio.status.archived',
} as const satisfies Record<PortfolioStatus, string>;

/** What each status means for the student, in one sentence. */
export const STATUS_HINT = {
  DRAFT: 'mobile.portfolio.statusHint.draft',
  SUBMITTED: 'mobile.portfolio.statusHint.submitted',
  CHANGES_REQUESTED: 'mobile.portfolio.statusHint.changesRequested',
  APPROVED: 'mobile.portfolio.statusHint.approved',
  REJECTED: 'mobile.portfolio.statusHint.rejected',
  ARCHIVED: 'mobile.portfolio.statusHint.archived',
} as const satisfies Record<PortfolioStatus, string>;

export const ITEM_TYPE_LABEL = {
  TEXT: 'mobile.portfolio.type.text',
  PHOTO: 'mobile.portfolio.type.photo',
  DRAWING: 'mobile.portfolio.type.drawing',
  SCAN: 'mobile.portfolio.type.scan',
  DOCUMENT: 'mobile.portfolio.type.document',
  AUDIO: 'mobile.portfolio.type.audio',
  VIDEO: 'mobile.portfolio.type.video',
} as const satisfies Record<PortfolioItemType, string>;

export const ITEM_TYPE_HINT = {
  TEXT: 'mobile.portfolio.typeHint.text',
  PHOTO: 'mobile.portfolio.typeHint.photo',
  DRAWING: 'mobile.portfolio.typeHint.drawing',
  SCAN: 'mobile.portfolio.typeHint.scan',
  DOCUMENT: 'mobile.portfolio.typeHint.document',
  AUDIO: 'mobile.portfolio.typeHint.audio',
  VIDEO: 'mobile.portfolio.typeHint.video',
} as const satisfies Record<PortfolioItemType, string>;

export const VISIBILITY_LABEL = {
  STUDENT_TEACHER: 'mobile.portfolio.visibility.studentTeacher',
  GUARDIANS: 'mobile.portfolio.visibility.guardians',
  CLASS: 'mobile.portfolio.visibility.class',
  SCHOOL: 'mobile.portfolio.visibility.school',
} as const satisfies Record<PortfolioVisibility, string>;

export const VISIBILITY_HINT = {
  STUDENT_TEACHER: 'mobile.portfolio.visibilityHint.studentTeacher',
  GUARDIANS: 'mobile.portfolio.visibilityHint.guardians',
  CLASS: 'mobile.portfolio.visibilityHint.class',
  SCHOOL: 'mobile.portfolio.visibilityHint.school',
} as const satisfies Record<PortfolioVisibility, string>;

/** The `action` of a history row (the API's wording is a code; the screen words it). */
export const HISTORY_LABEL = {
  CAPTURED: 'mobile.portfolio.history.captured',
  SUBMITTED: 'mobile.portfolio.history.submitted',
  APPROVED: 'mobile.portfolio.history.approved',
  CHANGES_REQUESTED: 'mobile.portfolio.history.changesRequested',
  REJECTED: 'mobile.portfolio.history.rejected',
  ARCHIVED: 'mobile.portfolio.history.archived',
  UNARCHIVED: 'mobile.portfolio.history.unarchived',
  FEATURED: 'mobile.portfolio.history.featured',
  UNFEATURED: 'mobile.portfolio.history.unfeatured',
  DELETED: 'mobile.portfolio.history.deleted',
} as const;

export const HISTORY_FALLBACK = 'mobile.portfolio.history.other';

export const AUTHOR_ROLE_LABEL = {
  TEACHER: 'mobile.portfolio.role.teacher',
  GUARDIAN: 'mobile.portfolio.role.guardian',
  STUDENT: 'mobile.portfolio.role.student',
} as const;

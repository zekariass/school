/** Class cards and class-related decisions. */

import { avatarColor } from './avatar';

/** The API's `MyClass` (only the fields we read). */
export interface MyClassLike {
  id: string;
  schoolId: string;
  name: string;
  status: 'ACTIVE' | 'ARCHIVED';
  role: 'STUDENT' | 'TEACHER';
  color?: string | undefined;
  icon?: string | undefined;
}

const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

/** The class colour when the school set a valid one, otherwise a stable friendly colour. */
export function classColor(cls: Pick<MyClassLike, 'id' | 'color'>): string {
  return cls.color && HEX_COLOR.test(cls.color) ? cls.color : avatarColor(cls.id);
}

/**
 * What to draw on the class badge: a short icon (an emoji or a letter) when the class has one,
 * otherwise the first letter of the name. Icon names longer than two characters (symbol names the
 * web may use) fall back to the letter.
 */
export function classGlyph(cls: Pick<MyClassLike, 'name' | 'icon'>): string {
  const icon = cls.icon?.trim();
  if (icon && Array.from(icon).length <= 2) return icon;
  return Array.from(cls.name.trim())[0]?.toLocaleUpperCase() ?? '?';
}

/** "Anna Bekele, Dawit Hailu" for the student's class cards (lead first, as the API returns them). */
export function teacherNames(teachers: readonly { displayName: string }[], separator = ', '): string {
  return teachers.map((teacher) => teacher.displayName).join(separator);
}

/** Classes a student can be moved to: the caller's other active classes of the same school. */
export function moveTargets<T extends MyClassLike>(mine: readonly T[], current: Pick<MyClassLike, 'id' | 'schoolId'>): T[] {
  return mine.filter(
    (cls) => cls.role === 'TEACHER' && cls.status === 'ACTIVE' && cls.schoolId === current.schoolId && cls.id !== current.id,
  );
}

/** A teacher may change the roster of an active class they teach (the API decides in the end). */
export function canEditRoster(cls: Pick<MyClassLike, 'role' | 'status'> | undefined): boolean {
  return cls?.role === 'TEACHER' && cls.status === 'ACTIVE';
}

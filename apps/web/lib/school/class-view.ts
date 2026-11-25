import { SP } from "./access";

/** Emoji presets for the class icon (stored as the `icon` text, so every client can show it). */
export const CLASS_ICONS = ["📚", "🔢", "🔬", "🌍", "🎨", "🎵", "⚽", "💻", "🗣️", "🌱", "🚀", "⭐"] as const;

/** Friendly colors for the class card and avatar ring (stored as `#rrggbb`). */
export const CLASS_COLORS = [
  "#2F80ED",
  "#27AE60",
  "#F2994A",
  "#EB5757",
  "#9B51E0",
  "#E056A0",
  "#2D9CDB",
  "#BB6BD9",
] as const;

export const DEFAULT_CLASS_ICON = CLASS_ICONS[0];
export const DEFAULT_CLASS_COLOR = CLASS_COLORS[0];

const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

export function isHexColor(value: string | null | undefined): value is string {
  return !!value && HEX_COLOR.test(value);
}

export interface ClassLike {
  id: string;
  name: string;
  icon?: string | null;
  color?: string | null;
  gradeLevelName?: string | null;
  academicYearName?: string | null;
  studentCount: number;
  teacherCount: number;
  status: "ACTIVE" | "ARCHIVED";
  myMembership?: "STUDENT" | "TEACHER" | null;
  myTeacherRole?: "LEAD" | "CO_TEACHER" | "SUBJECT_TEACHER" | "ASSISTANT" | null;
}

/** What a class card shows. */
export interface ClassCardModel {
  id: string;
  name: string;
  icon: string;
  color: string;
  grade: string | null;
  year: string | null;
  studentCount: number;
  teacherCount: number;
  archived: boolean;
  myTeacherRole: NonNullable<ClassLike["myTeacherRole"]> | null;
}

export function toClassCard(c: ClassLike): ClassCardModel {
  return {
    id: c.id,
    name: c.name,
    icon: c.icon?.trim() || DEFAULT_CLASS_ICON,
    color: isHexColor(c.color) ? c.color : DEFAULT_CLASS_COLOR,
    grade: c.gradeLevelName?.trim() || null,
    year: c.academicYearName?.trim() || null,
    studentCount: c.studentCount,
    teacherCount: c.teacherCount,
    archived: c.status === "ARCHIVED",
    myTeacherRole: c.myMembership === "TEACHER" ? (c.myTeacherRole ?? null) : null,
  };
}

/** What the caller may do in a class: shows or hides buttons; the API still answers 403 when this is wrong. */
export interface ClassAbilities {
  /** Teachers of the class and class administrators: roster, groups, login codes. */
  canTeach: boolean;
  /** The lead teacher and class administrators: rename, teachers, archive, delete. */
  canLead: boolean;
  /** Campus and academic year are administrator-only changes. */
  canManage: boolean;
  /** An archived class keeps its members, but its roster is frozen. */
  rosterOpen: boolean;
}

export function classAbilities(c: ClassLike, permissions: ReadonlySet<string>): ClassAbilities {
  const manager = permissions.has(SP.classManage);
  const teaches = c.myMembership === "TEACHER";
  return {
    canTeach: manager || teaches,
    canLead: manager || (teaches && c.myTeacherRole === "LEAD"),
    canManage: manager,
    rosterOpen: c.status === "ACTIVE",
  };
}

/** Whether a 403 on creating a class is the "teachers may not create classes here" case. */
export function canTryCreateClass(permissions: ReadonlySet<string>, isTeacher: boolean): boolean {
  return permissions.has(SP.classManage) || isTeacher;
}

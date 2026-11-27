import type { RollSnapshot, RollStudent } from '@/lib/attendance/roll-model';
import { isAttendanceStatus, isUuid } from '@/lib/sync/types';
import type { RosterEntry } from '@/lib/people/roster';
import { firstNameOf } from '@/lib/people/roster';

/**
 * The offline read cache: what a teacher's class screens and the quick roll show with no connection.
 *
 * Privacy rules, enforced here rather than in the screens:
 * - Entries are keyed by the signed-in user and are wiped on sign-out and when the session ends; entries of
 *   another user are removed at start.
 * - Per class: the roster as id, display name and avatar media id (+ a placeholder colour); never date of birth,
 *   gender, username or phone. The roll as ids, marks and versions (no teacher notes, no parents' words, only the
 *   absence reason CODE). Points totals as numbers per student id.
 * - No signed media link is ever stored (only media ids; the app signs them on demand).
 * - Every kind has a maximum age: older entries are deleted when read or at start. Rolls older than a few days are
 *   also not trusted for editing (see `rollTrust`).
 * - A class the teacher no longer teaches is purged on the next successful refresh of their class list.
 */

export type CacheKind = 'classes' | 'roster' | 'roll' | 'rollmeta' | 'categories' | 'totals' | 'templates' | 'subjects' | 'groups';

const DAY = 24 * 60 * 60 * 1000;

/** How long an entry may be read at all. */
export const MAX_AGE_MS: Readonly<Record<CacheKind, number>> = {
  classes: 30 * DAY,
  roster: 30 * DAY,
  roll: 14 * DAY,
  rollmeta: 30 * DAY,
  categories: 30 * DAY,
  totals: 7 * DAY,
  templates: 30 * DAY,
  subjects: 30 * DAY,
  groups: 30 * DAY,
};

/** Rolls kept per class (the newest ones). */
export const ROLLS_PER_CLASS = 8;

export interface CacheRow {
  readonly ownerId: string;
  readonly kind: CacheKind;
  /** The class id (empty for the class list). */
  readonly scope: string;
  readonly entryKey: string;
  readonly data: string;
  readonly fetchedAt: number;
}

export interface CacheStorage {
  get(ownerId: string, kind: CacheKind, scope: string, entryKey: string): Promise<CacheRow | null>;
  put(row: CacheRow): Promise<void>;
  delete(ownerId: string, kind: CacheKind, scope: string, entryKey: string): Promise<void>;
  /** Everything of a class, all kinds. */
  deleteScope(ownerId: string, scope: string): Promise<void>;
  /** Everything of the owner's classes whose scope is not in `keep` (the class list itself stays). */
  deleteScopesExcept(ownerId: string, keep: readonly string[]): Promise<void>;
  deleteOlderThan(kind: CacheKind, cutoff: number): Promise<void>;
  /** Keeps the `count` newest entries of a kind in a class. */
  keepNewest(ownerId: string, kind: CacheKind, scope: string, count: number): Promise<void>;
  scopes(ownerId: string): Promise<string[]>;
  deleteOtherOwners(ownerId: string): Promise<void>;
  clear(): Promise<void>;
}

export interface CacheHit<T> {
  readonly data: T;
  readonly fetchedAt: number;
}

export class OfflineCache {
  constructor(
    private readonly storage: CacheStorage,
    private readonly getOwnerId: () => string | null,
    private readonly now: () => number,
  ) {}

  /** The entry, or null when there is none, it is too old (it is deleted then) or nobody is signed in. */
  async get(kind: CacheKind, scope: string, entryKey = ''): Promise<CacheHit<unknown> | null> {
    const ownerId = this.getOwnerId();
    if (ownerId === null) return null;
    const row = await this.storage.get(ownerId, kind, scope, entryKey);
    if (!row) return null;
    if (this.now() - row.fetchedAt > MAX_AGE_MS[kind]) {
      await this.storage.delete(ownerId, kind, scope, entryKey);
      return null;
    }
    try {
      return { data: JSON.parse(row.data) as unknown, fetchedAt: row.fetchedAt };
    } catch {
      await this.storage.delete(ownerId, kind, scope, entryKey);
      return null;
    }
  }

  async put(kind: CacheKind, scope: string, entryKey: string, data: unknown, fetchedAt = this.now()): Promise<void> {
    const ownerId = this.getOwnerId();
    if (ownerId === null) return;
    await this.storage.put({ ownerId, kind, scope, entryKey, data: JSON.stringify(data), fetchedAt });
    if (kind === 'roll') await this.storage.keepNewest(ownerId, 'roll', scope, ROLLS_PER_CLASS);
  }

  /** The class is gone for this user (403/404): nothing of it stays. */
  async forgetClass(scope: string): Promise<void> {
    const ownerId = this.getOwnerId();
    if (ownerId !== null) await this.storage.deleteScope(ownerId, scope);
  }

  /** After a successful refresh of the class list: classes the teacher no longer teaches are purged. */
  async purgeClassesExcept(classIds: readonly string[]): Promise<void> {
    const ownerId = this.getOwnerId();
    if (ownerId !== null) await this.storage.deleteScopesExcept(ownerId, classIds);
  }

  /** At start: entries past their age and entries of other users. */
  async tidy(): Promise<void> {
    const ownerId = this.getOwnerId();
    if (ownerId === null) return;
    await this.storage.deleteOtherOwners(ownerId);
    const now = this.now();
    for (const kind of Object.keys(MAX_AGE_MS) as CacheKind[]) await this.storage.deleteOlderThan(kind, now - MAX_AGE_MS[kind]);
  }

  /** Sign-out and session end: nothing of the cache survives. */
  async clear(): Promise<void> {
    await this.storage.clear();
  }
}

// --- what is allowed on the disk ---------------------------------------------------------------------------

const COLOR = /^#[0-9a-fA-F]{6}$/;
const asRecord = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};

export interface CachedStudent {
  readonly id: string;
  readonly name: string;
  readonly avatarMediaId: string | null;
  readonly avatarColor: string | null;
}

/** The roster as kept offline: id, display name, avatar media id and the placeholder colour. Nothing else. */
export function rosterToCache(roster: readonly RosterEntry[]): CachedStudent[] {
  return roster.map((entry) => {
    const color = entry.avatarConfig?.color;
    return {
      id: entry.id,
      name: entry.name,
      avatarMediaId: entry.avatarMediaId,
      avatarColor: typeof color === 'string' && COLOR.test(color) ? color : null,
    };
  });
}

export function cachedStudents(data: unknown): CachedStudent[] {
  if (!Array.isArray(data)) return [];
  return data.flatMap((raw): CachedStudent[] => {
    const entry = asRecord(raw);
    if (!isUuid(entry.id) || typeof entry.name !== 'string') return [];
    return [
      {
        id: entry.id,
        name: entry.name,
        avatarMediaId: typeof entry.avatarMediaId === 'string' ? entry.avatarMediaId : null,
        avatarColor: typeof entry.avatarColor === 'string' && COLOR.test(entry.avatarColor) ? entry.avatarColor : null,
      },
    ];
  });
}

/** A cached student as the screens' roster entry (private fields are simply not there: BASIC access). */
export function rosterFromCache(data: unknown): RosterEntry[] {
  return cachedStudents(data).map((student) => ({
    id: student.id,
    name: student.name,
    firstName: firstNameOf(student.name),
    avatarMediaId: student.avatarMediaId,
    avatarConfig: student.avatarColor ? { color: student.avatarColor } : null,
    accessLevel: 'BASIC' as const,
  }));
}

/** Points totals per student id. */
export function totalsToCache(students: readonly { studentId: string; total?: number | undefined }[]): Record<string, number> {
  return Object.fromEntries(students.map((student) => [student.studentId, student.total ?? 0]));
}

export function totalsFromCache(data: unknown): Record<string, number> | null {
  const raw = asRecord(data);
  const entries = Object.entries(raw).filter((entry): entry is [string, number] => typeof entry[1] === 'number' && Number.isFinite(entry[1]));
  return entries.length > 0 || Object.keys(raw).length === 0 ? Object.fromEntries(entries) : null;
}

function rollStudentToCache(student: RollStudent): RollStudent {
  return {
    studentId: student.studentId,
    name: student.name,
    avatarMediaId: student.avatarMediaId,
    avatarColor: student.avatarColor,
    record: student.record
      ? { status: student.record.status, minutesLate: student.record.minutesLate, version: student.record.version, recordedBy: student.record.recordedBy }
      : null,
    suggestedStatus: student.suggestedStatus,
    absence: student.absence
      ? {
          reportId: student.absence.reportId,
          status: student.absence.status,
          reasonCode: student.absence.reasonCode,
          fromDate: student.absence.fromDate,
          toDate: student.absence.toDate,
        }
      : null,
  };
}

/** The roll as kept offline: explicit fields only, so a field added to the model later is not stored by accident. */
export function rollToCache(roll: RollSnapshot): RollSnapshot {
  return {
    schoolId: roll.schoolId,
    classId: roll.classId,
    date: roll.date,
    slot: roll.slot,
    slots: [...roll.slots],
    timeZone: roll.timeZone,
    sessionVersion: roll.sessionVersion,
    locked: roll.locked,
    locksAt: roll.locksAt,
    mayEditLocked: roll.mayEditLocked,
    synthetic: false,
    // A group's view of the roll is never cached on its own (see `loadRoll`): `groupId` is always null here.
    subjectId: roll.subjectId,
    subjectName: roll.subjectName,
    groupId: null,
    students: roll.students.map(rollStudentToCache),
  };
}

const numberOrNull = (value: unknown): number | null => (typeof value === 'number' && Number.isFinite(value) ? value : null);
const stringOrNull = (value: unknown): string | null => (typeof value === 'string' ? value : null);

/** A cached roll, re-validated: anything malformed makes the whole entry unusable (null). */
export function rollFromCache(data: unknown): RollSnapshot | null {
  const raw = asRecord(data);
  if (!isUuid(raw.schoolId) || !isUuid(raw.classId) || typeof raw.date !== 'string' || typeof raw.slot !== 'string') return null;
  if (typeof raw.timeZone !== 'string' || !Array.isArray(raw.students)) return null;
  const students: RollStudent[] = [];
  for (const item of raw.students) {
    const student = asRecord(item);
    if (!isUuid(student.studentId) || typeof student.name !== 'string') return null;
    const record = asRecord(student.record);
    const absence = asRecord(student.absence);
    students.push({
      studentId: student.studentId,
      name: student.name,
      avatarMediaId: stringOrNull(student.avatarMediaId),
      avatarColor: typeof student.avatarColor === 'string' && COLOR.test(student.avatarColor) ? student.avatarColor : null,
      record: isAttendanceStatus(record.status)
        ? { status: record.status, minutesLate: numberOrNull(record.minutesLate), version: numberOrNull(record.version), recordedBy: stringOrNull(record.recordedBy) }
        : null,
      suggestedStatus: isAttendanceStatus(student.suggestedStatus) ? student.suggestedStatus : null,
      absence:
        typeof absence.reportId === 'string'
          ? {
              reportId: absence.reportId,
              status: typeof absence.status === 'string' ? absence.status : 'ACKNOWLEDGED',
              reasonCode: stringOrNull(absence.reasonCode),
              fromDate: typeof absence.fromDate === 'string' ? absence.fromDate : raw.date,
              toDate: typeof absence.toDate === 'string' ? absence.toDate : raw.date,
            }
          : null,
    });
  }
  return {
    schoolId: raw.schoolId,
    classId: raw.classId,
    date: raw.date,
    slot: raw.slot,
    slots: Array.isArray(raw.slots) ? raw.slots.filter((slot): slot is string => typeof slot === 'string') : [raw.slot],
    timeZone: raw.timeZone,
    sessionVersion: numberOrNull(raw.sessionVersion),
    locked: raw.locked === true,
    locksAt: stringOrNull(raw.locksAt),
    mayEditLocked: raw.mayEditLocked === true,
    synthetic: false,
    subjectId: isUuid(raw.subjectId) ? raw.subjectId : null,
    subjectName: stringOrNull(raw.subjectName),
    groupId: null,
    students,
  };
}

export interface RollMeta {
  readonly timeZone: string;
  readonly slots: readonly string[];
}

export function rollMetaFromCache(data: unknown): RollMeta | null {
  const raw = asRecord(data);
  if (typeof raw.timeZone !== 'string' || !Array.isArray(raw.slots)) return null;
  const slots = raw.slots.filter((slot): slot is string => typeof slot === 'string');
  return slots.length > 0 ? { timeZone: raw.timeZone, slots } : null;
}

/**
 * The cache entry of a roll: one per class, date and slot (the server's identity of a roll). A saved group is only a
 * filter on that roll, so a group's view is never stored under a key of its own: it would drift from the real roll.
 */
export const rollEntryKey = (date: string, slot: string): string => `${date}|${slot}`;

// --- what a period roll needs besides the roll: the class's subjects and its saved groups ---------------------

/** A subject a period roll may be taken for (catalogue data: an id and a name, nothing about a child). */
export interface CachedSubject {
  readonly id: string;
  readonly name: string;
}

export function subjectsToCache(subjects: readonly CachedSubject[]): CachedSubject[] {
  return subjects.map((subject) => ({ id: subject.id, name: subject.name }));
}

export function subjectsFromCache(data: unknown): CachedSubject[] | null {
  if (!Array.isArray(data)) return null;
  return data.flatMap((raw): CachedSubject[] => {
    const entry = asRecord(raw);
    return isUuid(entry.id) && typeof entry.name === 'string' ? [{ id: entry.id, name: entry.name }] : [];
  });
}

/**
 * A saved student group of the class (an elective's roster). Kept as id, name and member ids so that an elective's roll
 * can be taken offline from the class roll; the ids alone say nothing without the roster.
 */
export interface CachedGroup {
  readonly id: string;
  readonly name: string;
  readonly studentIds: readonly string[];
}

export function groupsToCache(groups: readonly CachedGroup[]): CachedGroup[] {
  return groups.map((group) => ({ id: group.id, name: group.name, studentIds: [...group.studentIds] }));
}

export function groupsFromCache(data: unknown): CachedGroup[] | null {
  if (!Array.isArray(data)) return null;
  return data.flatMap((raw): CachedGroup[] => {
    const entry = asRecord(raw);
    if (!isUuid(entry.id) || typeof entry.name !== 'string' || !Array.isArray(entry.studentIds)) return [];
    return [{ id: entry.id, name: entry.name, studentIds: entry.studentIds.filter(isUuid) }];
  });
}

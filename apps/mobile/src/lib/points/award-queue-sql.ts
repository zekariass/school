import type { AwardQueueStorage, QueuedAward } from './award-queue';

/** The subset of expo-sqlite's database the queue needs (so a test can back it with another SQLite). */
export interface QueueDatabase {
  runAsync(sql: string, params: (string | number | null)[]): Promise<unknown>;
  getAllAsync<T>(sql: string, params: (string | number | null)[]): Promise<T[]>;
}

/** Migration 2 of the local database. Ids, numbers and timestamps only: no notes, names or tokens. */
export const AWARD_QUEUE_STATEMENTS: readonly string[] = [
  `CREATE TABLE points_award_queue (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    op_id TEXT NOT NULL UNIQUE,
    owner_id TEXT NOT NULL,
    school_id TEXT NOT NULL,
    class_id TEXT NOT NULL,
    category_id TEXT NOT NULL,
    student_ids TEXT NOT NULL,
    whole_class INTEGER NOT NULL,
    points INTEGER,
    created_at INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at INTEGER NOT NULL DEFAULT 0
  )`,
];

interface Row {
  op_id: string;
  owner_id: string;
  school_id: string;
  class_id: string;
  category_id: string;
  student_ids: string;
  whole_class: number;
  points: number | null;
  created_at: number;
  attempts: number;
  next_attempt_at: number;
}

function parseIds(text: string): string[] {
  try {
    const value: unknown = JSON.parse(text);
    return Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string') : [];
  } catch {
    return [];
  }
}

function toItem(row: Row): QueuedAward {
  return {
    opId: row.op_id,
    ownerId: row.owner_id,
    schoolId: row.school_id,
    classId: row.class_id,
    categoryId: row.category_id,
    studentIds: parseIds(row.student_ids),
    wholeClass: row.whole_class === 1,
    points: row.points,
    createdAt: row.created_at,
    attempts: row.attempts,
    nextAttemptAt: row.next_attempt_at,
  };
}

export function sqlAwardQueueStorage(db: () => Promise<QueueDatabase>): AwardQueueStorage {
  return {
    async load() {
      const rows = await (await db()).getAllAsync<Row>('SELECT * FROM points_award_queue ORDER BY seq', []);
      return rows.map(toItem);
    },
    async insert(item) {
      await (
        await db()
      ).runAsync(
        `INSERT OR IGNORE INTO points_award_queue
          (op_id, owner_id, school_id, class_id, category_id, student_ids, whole_class, points, created_at, attempts, next_attempt_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          item.opId,
          item.ownerId,
          item.schoolId,
          item.classId,
          item.categoryId,
          JSON.stringify(item.studentIds),
          item.wholeClass ? 1 : 0,
          item.points,
          item.createdAt,
          item.attempts,
          item.nextAttemptAt,
        ],
      );
    },
    async update(opId, patch) {
      await (
        await db()
      ).runAsync('UPDATE points_award_queue SET attempts = ?, next_attempt_at = ? WHERE op_id = ?', [
        patch.attempts,
        patch.nextAttemptAt,
        opId,
      ]);
    },
    async remove(opId) {
      await (await db()).runAsync('DELETE FROM points_award_queue WHERE op_id = ?', [opId]);
    },
    async clear() {
      await (await db()).runAsync('DELETE FROM points_award_queue', []);
    },
  };
}

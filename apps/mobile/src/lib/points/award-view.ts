import type { QueuedAward } from '@/lib/sync/types';

/** The fields of a queued award the points screens work with (the outbox keeps them under `payload`). */
export interface AwardView {
  readonly opId: string;
  readonly classId: string;
  readonly categoryId: string;
  readonly studentIds: readonly string[];
  readonly wholeClass: boolean;
  readonly points: number | null;
}

export function awardView(op: QueuedAward): AwardView {
  return {
    opId: op.opId,
    classId: op.classId,
    categoryId: op.payload.categoryId,
    studentIds: op.payload.studentIds,
    wholeClass: op.payload.wholeClass,
    points: op.payload.points,
  };
}

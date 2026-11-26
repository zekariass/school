import * as Haptics from 'expo-haptics';
import { useCallback, useState } from 'react';

import { newOpId } from '@/lib/points/op-id';
import { requestFlush, syncEngine } from '@/lib/sync/runtime';
import type { AttendanceMark, AttendanceStatus, EnqueueResult } from '@/lib/sync/types';

import {
  editOperation,
  markAllPresentMarks,
  markFor,
  nextStatus,
  type EffectiveStudent,
  type Editability,
  type RollSnapshot,
} from './roll-model';

export type EditOutcome = EnqueueResult | 'blocked' | 'needs-reason';

export interface RollEditor {
  /** One tap: the next status of the cycle. */
  tap: (student: EffectiveStudent, reason?: string) => Promise<EditOutcome>;
  /** The details sheet: a chosen status, minutes late and an optional note (the note stays in memory). */
  set: (
    student: EffectiveStudent,
    status: AttendanceStatus,
    extra?: { minutesLate?: number | null; note?: string },
    reason?: string,
  ) => Promise<EditOutcome>;
  /** Everybody unmarked: present (or excused where a parent said so). Returns how many were marked. */
  markAllPresent: (students: readonly EffectiveStudent[], reason?: string) => Promise<{ marked: number; outcome: EditOutcome }>;
  /** The roll is locked and this teacher may override it: the reason is asked once, then sent with every edit. */
  needsReason: boolean;
  overrideReason: string;
  setOverrideReason: (reason: string) => void;
}

const light = () => void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
const success = () => void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success).catch(() => {});

/**
 * What a teacher does on the roll: every change becomes (or joins) a queued operation at once, so it shows
 * immediately, survives the app closing, and goes out as soon as the network allows.
 */
export function useRollEditor(input: { roll: RollSnapshot | undefined; ownerId: string | undefined; editability: Editability }): RollEditor {
  const { roll, ownerId, editability } = input;
  const [overrideReason, setOverrideReason] = useState('');
  const known = overrideReason.trim();
  const needsReason = editability.kind === 'override' && known.length === 0;

  // `reasonNow` is a reason given a moment ago, before React has handed the new state back.
  const enqueue = useCallback(
    async (marks: readonly AttendanceMark[], notes?: Record<string, string>, reasonNow?: string): Promise<EditOutcome> => {
      if (!roll || !ownerId || marks.length === 0) return 'blocked';
      if (editability.kind !== 'open' && editability.kind !== 'override') return 'blocked';
      const reason = (reasonNow ?? known).trim();
      if (editability.kind === 'override' && reason.length === 0) return 'needs-reason';
      const outcome = await syncEngine.enqueue(
        editOperation({ opId: newOpId(), ownerId, roll, marks, ...(editability.kind === 'override' ? { overrideReason: reason.slice(0, 500) } : {}) }),
        notes ? { volatile: { notes } } : {},
      );
      if (outcome === 'queued' || outcome === 'merged') requestFlush();
      return outcome;
    },
    [roll, ownerId, editability.kind, known],
  );

  const tap = useCallback(
    async (student: EffectiveStudent, reason?: string) => {
      light();
      const status = nextStatus(student.status, student.suggestedStatus);
      // A new mark has no note: it belonged to the old one.
      return enqueue([markFor(student, status)], { [student.studentId]: '' }, reason);
    },
    [enqueue],
  );

  const set = useCallback(
    async (student: EffectiveStudent, status: AttendanceStatus, extra?: { minutesLate?: number | null; note?: string }, reason?: string) => {
      light();
      return enqueue([markFor(student, status, status === 'LATE' ? extra?.minutesLate : null)], { [student.studentId]: extra?.note?.trim() ?? '' }, reason);
    },
    [enqueue],
  );

  const markAllPresent = useCallback(
    async (students: readonly EffectiveStudent[], reason?: string) => {
      const marks = markAllPresentMarks(students);
      const outcome = await enqueue(marks, undefined, reason);
      if (outcome === 'queued' || outcome === 'merged') success();
      return { marked: marks.length, outcome };
    },
    [enqueue],
  );

  return { tap, set, markAllPresent, needsReason, overrideReason, setOverrideReason };
}


import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'use-intl';

import type { Totals } from '@school/classroom-toolkit';

import { problemOf } from '@/lib/api-error';
import type { RosterEntry } from '@/lib/people/roster';

import type { QueueEvent } from './award-queue';
import { canUndoAt } from './award-outcome';
import { awardQueue, useAwardQueueEvents, useQueuedAwards } from './award-runtime';
import { formatSigned, signedPoints } from './categories';
import { playFeedback } from './feedback';
import type { FeedbackKind } from './feedback-plan';
import { newOpId } from './op-id';
import { useReverseAward, type ClassPointsSnapshot, type PointCategory } from './queries';
import {
  addPending,
  classTotal,
  displayedTotals,
  EMPTY_TOTALS_MODEL,
  isSettled,
  pendingFromQueued,
  replaceBase,
  SETTLE_MS,
  type PendingAward,
  type TotalsModel,
} from './totals-model';

export interface Burst {
  key: number;
  text: string;
  negative: boolean;
}

/** The award the snackbar talks about. */
export interface LastAward {
  opId: string;
  message: string;
  studentIds: readonly string[];
  delta: number;
  state: 'waiting' | 'confirmed';
  batchId: string | null;
  transactionIds: readonly string[];
  undoableUntil: string | null;
  /** The teacher tapped undo while the call was in the air: take it back as soon as it is confirmed. */
  undoWhenConfirmed: boolean;
  undoing: boolean;
}

export interface UseClassAwardsInput {
  schoolId: string | undefined;
  classId: string;
  ownerId: string | undefined;
  roster: readonly RosterEntry[];
  categories: readonly PointCategory[];
  overview: ClassPointsSnapshot | undefined;
  refetchOverview: () => void;
  reduceMotion: boolean;
}

export type AwardTarget = { kind: 'students'; ids: readonly string[] } | { kind: 'class' };

/**
 * Everything the class points screen does with awards: queue them (offline-safe), show the optimistic totals,
 * play the feedback, keep the undo snackbar, and settle with the server's overview.
 *
 * The shown totals are derived, not stored: the last overview, plus the awards still in the queue (so they also
 * count after an app restart), plus the ones the server confirmed but a later overview may not contain yet.
 */
export function useClassAwards({ schoolId, classId, ownerId, roster, categories, overview, refetchOverview, reduceMotion }: UseClassAwardsInput) {
  const t = useTranslations();
  // Confirmed awards (and undos) waiting for an overview that contains them.
  const [confirmed, setConfirmed] = useState<TotalsModel>(EMPTY_TOTALS_MODEL);
  const [bursts, setBursts] = useState<Record<string, Burst>>({});
  const [last, setLast] = useState<LastAward | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const burstCounter = useRef(0);
  const queued = useQueuedAwards();
  const reverse = useReverseAward(schoolId);
  const refetchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const rosterIds = useMemo(() => roster.map((student) => student.id), [roster]);
  const requestedAt = overview?.requestedAt;

  const base = useMemo<Totals | null>(
    () => (overview ? Object.fromEntries(overview.points.students.map((student) => [student.studentId, student.total ?? 0])) : null),
    [overview],
  );

  const queuedPending = useMemo<PendingAward[]>(
    () =>
      queued.flatMap((item) => {
        if (item.classId !== classId) return [];
        const category = categories.find((candidate) => candidate.id === item.categoryId);
        const pending = pendingFromQueued(
          item,
          category ? { polarity: category.polarity, defaultPoints: category.defaultPoints ?? 1 } : undefined,
          rosterIds,
        );
        return pending ? [pending] : [];
      }),
    [queued, categories, classId, rosterIds],
  );

  const model = useMemo<TotalsModel>(() => {
    const merged: TotalsModel = { base, pending: [...confirmed.pending, ...queuedPending] };
    return base && requestedAt !== undefined ? replaceBase(merged, base, requestedAt) : merged;
  }, [base, requestedAt, confirmed, queuedPending]);

  useEffect(
    () => () => {
      if (refetchTimer.current) clearTimeout(refetchTimer.current);
    },
    [],
  );

  const showBursts = useCallback((ids: readonly string[], delta: number) => {
    const text = formatSigned(delta);
    setBursts((current) => {
      const next = { ...current };
      for (const id of ids) next[id] = { key: ++burstCounter.current, text, negative: delta < 0 };
      return next;
    });
  }, []);

  /** Remembers a confirmed (or undone) change until an overview that contains it arrives. */
  const rememberConfirmed = useCallback(
    (entry: PendingAward) => {
      setConfirmed((current) => {
        const kept: TotalsModel = {
          base: null,
          pending: requestedAt === undefined ? current.pending : current.pending.filter((candidate) => !isSettled(candidate, requestedAt)),
        };
        return addPending(kept, entry);
      });
    },
    [requestedAt],
  );

  const runReverse = useCallback(
    async (award: LastAward) => {
      const target =
        award.batchId !== null
          ? ({ kind: 'batch', batchId: award.batchId } as const)
          : award.transactionIds[0]
            ? ({ kind: 'transaction', transactionId: award.transactionIds[0] } as const)
            : null;
      if (!target) return;
      setLast((current) => (current?.opId === award.opId ? { ...current, undoing: true } : current));
      try {
        await reverse.mutateAsync({ target, clientOpId: newOpId() });
        rememberConfirmed({
          opId: `undo-${award.opId}`,
          studentIds: award.studentIds,
          delta: -award.delta,
          state: 'confirmed',
          confirmedAt: Date.now(),
        });
        showBursts(award.studentIds, -award.delta);
        playFeedback('undo', reduceMotion);
        setLast(null);
      } catch (error) {
        // Most often points.undo_window_expired or points.already_reversed: the API's own words.
        setProblem(problemOf(error)?.detail ?? t('mobile.points.snackbar.undoFailed'));
        setLast(null);
      }
    },
    [reverse, reduceMotion, rememberConfirmed, showBursts, t],
  );

  const onEvent = useCallback(
    (event: QueueEvent) => {
      if (event.item.classId !== classId) return;
      if (event.type === 'confirmed') {
        const category = categories.find((candidate) => candidate.id === event.item.categoryId);
        const entry = pendingFromQueued(
          event.item,
          category ? { polarity: category.polarity, defaultPoints: category.defaultPoints ?? 1 } : undefined,
          rosterIds,
        );
        if (entry) rememberConfirmed({ ...entry, state: 'confirmed', confirmedAt: Date.now() });

        let toUndo: LastAward | null = null;
        setLast((current) => {
          if (current?.opId !== event.item.opId) return current;
          const updated: LastAward = {
            ...current,
            state: 'confirmed',
            batchId: event.confirmation.batchId,
            transactionIds: event.confirmation.transactionIds,
            undoableUntil: event.confirmation.undoableUntil,
          };
          if (current.undoWhenConfirmed) toUndo = updated;
          return updated;
        });
        if (toUndo) void runReverse(toUndo);
        // Ask for the overview once the projection has had time to catch up.
        if (refetchTimer.current) clearTimeout(refetchTimer.current);
        refetchTimer.current = setTimeout(refetchOverview, SETTLE_MS + 500);
      } else if (event.type === 'rejected' || event.type === 'expired') {
        setLast((current) => (current?.opId === event.item.opId ? null : current));
      }
    },
    [classId, categories, rosterIds, rememberConfirmed, refetchOverview, runReverse],
  );
  useAwardQueueEvents(onEvent);

  const award = useCallback(
    async (target: AwardTarget, category: PointCategory, pointsOverride: number | null) => {
      if (!schoolId || !ownerId) return;
      const studentIds = target.kind === 'class' ? rosterIds : target.ids;
      if (studentIds.length === 0) return;
      const opId = newOpId();
      const delta = signedPoints({ polarity: category.polarity, defaultPoints: category.defaultPoints }, pointsOverride);

      const result = await awardQueue.enqueue({
        opId,
        ownerId,
        schoolId,
        classId,
        categoryId: category.id,
        studentIds: target.kind === 'class' ? [] : [...target.ids],
        wholeClass: target.kind === 'class',
        points: pointsOverride,
      });
      if (result === 'full') {
        setProblem(t('mobile.points.queue.full'));
        return;
      }
      if (result !== 'queued') return;

      setProblem(null);
      const kind: FeedbackKind = target.kind === 'class' ? 'wholeClass' : delta < 0 ? 'needsWork' : 'award';
      const animate = playFeedback(kind, reduceMotion);
      if (animate) showBursts(studentIds, delta);

      const who =
        target.kind === 'class'
          ? t('mobile.points.snackbar.wholeClass')
          : target.ids.length === 1
            ? (roster.find((student) => student.id === target.ids[0])?.firstName ?? '')
            : t('mobile.points.snackbar.students', { count: target.ids.length });
      setLast({
        opId,
        message: t('mobile.points.snackbar.awarded', { skill: category.name, points: formatSigned(delta), who }),
        studentIds,
        delta,
        state: 'waiting',
        batchId: null,
        transactionIds: [],
        undoableUntil: null,
        undoWhenConfirmed: false,
        undoing: false,
      });
      void awardQueue.flush(true);
    },
    [schoolId, ownerId, classId, rosterIds, roster, reduceMotion, showBursts, t],
  );

  const undo = useCallback(async () => {
    if (!last || last.undoing) return;
    if (last.state === 'confirmed') {
      await runReverse(last);
      return;
    }
    // Not confirmed yet: take it out of the queue; if it is being sent right now, undo it as soon as it lands.
    const result = await awardQueue.cancel(last.opId);
    if (result === 'cancelled') {
      playFeedback('undo', reduceMotion);
      setLast(null);
    } else if (result === 'in-flight') {
      setLast((current) => (current?.opId === last.opId ? { ...current, undoWhenConfirmed: true, undoing: true } : current));
    }
  }, [last, runReverse, reduceMotion]);

  const totals = useMemo(() => displayedTotals(model), [model]);
  const total = useMemo(() => classTotal(totals, rosterIds), [totals, rosterIds]);

  return {
    totals,
    totalsKnown: model.base !== null,
    classTotal: total,
    bursts,
    last,
    problem,
    clearProblem: () => setProblem(null),
    dismissLast: () => setLast(null),
    award,
    undo,
    canUndo: (now: number) => last !== null && (last.state === 'waiting' || canUndoAt(last.undoableUntil, now)),
  };
}

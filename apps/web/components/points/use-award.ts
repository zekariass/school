"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useMemo } from "react";
import { useSchool } from "@/components/school/school-context";
import { ApiRequestError } from "@/lib/api-error";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { errorMessage } from "@/lib/console/errors";
import { createOpTracker, type AwardIntent } from "@/lib/points/award-op";
import { pointsFor, type CategoryLike } from "@/lib/points/categories";
import { applyAwardToOverview, awardDelta, awardTargets } from "@/lib/points/optimistic";
import { overviewRefresher } from "@/lib/points/refresh";
import { reverseTarget } from "@/lib/points/undo";
import { pk, postAward, type AwardResult, type ClassPoints, type PointCategory } from "@/lib/points/queries";
import { usePointsFeedback } from "./points-provider";

export interface AwardRequest {
  category: PointCategory;
  /** Who gets it: some students, or everybody in the class. */
  target: { wholeClass: true } | { studentIds: readonly string[] };
  /** 1-10 override, or null for the category's default. */
  points: number | null;
  note: string;
  /** For the toast and the announcement: a student's first name, or the class. */
  who: string;
}

/**
 * Awards points in one class: one `clientOpId` per user action (reused when the same action is retried after a
 * failure, so a request that did arrive is never applied twice), the optimistic update of the class grid, the
 * quick-undo countdown, the sound, and the screen-reader announcement.
 */
export function useAward(classId: string, className: string, roster: readonly { studentId: string }[]) {
  const t = useTranslations("school.points");
  const { school } = useSchool();
  const queryClient = useQueryClient();
  const { play, announce, addUndo } = usePointsFeedback();
  const tracker = useMemo(() => createOpTracker(newIdempotencyKey), []);

  const mutation = useMutation({
    mutationFn: async (request: AwardRequest): Promise<AwardResult> => {
      const intent: AwardIntent = {
        categoryId: request.category.id,
        studentIds: "wholeClass" in request.target ? [] : request.target.studentIds,
        wholeClass: "wholeClass" in request.target,
        points: request.points,
        note: request.note,
      };
      // The same intent after a failed attempt gets the same clientOpId.
      return postAward(school.id, classId, tracker.begin(intent));
    },
    onSuccess: async (result, request) => {
      tracker.succeeded();
      const points = pointsFor(request.category, request.points);
      const delta = awardDelta(request.category.polarity, points);
      const targets = awardTargets(request.target, roster);
      // A fetch that started before this award would overwrite the new numbers with older ones: drop it first.
      await queryClient.cancelQueries({ queryKey: pk.overview(school.id, classId) });
      queryClient.setQueryData<ClassPoints>(pk.overview(school.id, classId), (old) =>
        old ? applyAwardToOverview(old, targets, delta) : old,
      );
      // The projection is eventually consistent: look again shortly and let the server's numbers replace ours.
      overviewRefresher.schedule(`${school.id}:${classId}`, () => void queryClient.invalidateQueries({ queryKey: pk.overview(school.id, classId) }));
      const needsWork = request.category.polarity === "NEEDS_WORK";
      play(needsWork ? "needsWork" : "award");
      announce(
        t(needsWork ? "announce.needsWork" : "announce.awarded", {
          who: request.who,
          skill: request.category.name,
          points: Math.abs(delta),
        }),
      );
      const target = reverseTarget(result);
      if (result.undoableUntil && target) {
        addUndo({
          id: target.id,
          target,
          classId,
          studentIds: targets,
          delta,
          undoableUntil: result.undoableUntil,
          className,
          label: t("undo.label", { skill: request.category.name, who: request.who, points: delta > 0 ? `+${delta}` : `${delta}` }),
        });
      }
    },
  });

  /** The message under the tiles after a failed award: the API's localized detail, or a hint to just tap again. */
  const failure = mutation.isError
    ? mutation.error instanceof ApiRequestError && mutation.error.problem
      ? errorMessage(mutation.error, t("award.failed"))
      : t("award.networkFailed")
    : null;

  return { mutation, failure };
}

export type CategoryForAward = Pick<CategoryLike, "defaultPoints" | "polarity" | "name">;

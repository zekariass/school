"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { HeartIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { ApiRequestError } from "@/lib/api-error";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { errorMessage } from "@/lib/console/errors";
import { createOpTracker } from "@/lib/points/award-op";
import { compareCategories, pointsFor } from "@/lib/points/categories";
import { fetchHomeCategories, pk, postHomeAward, reverseBatch, type PointCategory } from "@/lib/points/queries";
import { secondsLeft } from "@/lib/points/undo";
import { SkillTile } from "./award-sheet";

/** The button on a child's Home points section: opens the sheet where a guardian with `can_award_points` awards. */
export function HomeAwardButton({ schoolId, studentId, childName }: { schoolId: string; studentId: string; childName: string }) {
  const t = useTranslations("family.points.homeAward");
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button onClick={() => setOpen(true)} data-testid="home-award-open">
        <HeartIcon className="size-4" aria-hidden />
        {t("open")}
      </Button>
      {open ? <HomeAwardSheet schoolId={schoolId} studentId={studentId} childName={childName} onClose={() => setOpen(false)} /> : null}
    </>
  );
}

/**
 * Guardians award HOME skills to their own child. The points are the family's: they are not part of class or
 * school results (said plainly in the sheet). One `clientOpId` per action, reused when the same tap is retried.
 */
function HomeAwardSheet({ schoolId, studentId, childName, onClose }: { schoolId: string; studentId: string; childName: string; onClose: () => void }) {
  const t = useTranslations("family.points.homeAward");
  const queryClient = useQueryClient();
  const tracker = useMemo(() => createOpTracker(newIdempotencyKey), []);
  const [note, setNote] = useState("");
  const categories = useQuery({
    queryKey: pk.homeCategories(schoolId, studentId),
    queryFn: () => fetchHomeCategories(schoolId, studentId),
    retry: false,
  });
  const tiles = (categories.data ?? []).filter((c) => c.active !== false && c.context === "HOME").sort(compareCategories);

  const award = useMutation({
    mutationFn: (category: PointCategory) =>
      postHomeAward(schoolId, studentId, tracker.begin({ categoryId: category.id, studentIds: [studentId], wholeClass: false, points: null, note })),
    onSuccess: (result, category) => {
      tracker.succeeded();
      void queryClient.invalidateQueries({ queryKey: pk.student(schoolId, studentId) });
      void queryClient.invalidateQueries({ queryKey: pk.timeline(schoolId, studentId, "HOME") });
      const points = pointsFor(category, null);
      const window = result.undoableUntil ? secondsLeft(result.undoableUntil, Date.now()) : 0;
      const batchId = result.batchId;
      toast.success(t("awarded", { child: childName, skill: category.name, points }), {
        duration: window > 0 ? Math.min(window, 20) * 1000 : 4000,
        ...(batchId && window > 0
          ? {
              action: {
                label: t("undo"),
                onClick: () => {
                  reverseBatch(schoolId, batchId)
                    .then(() => {
                      toast.success(t("undone"));
                      void queryClient.invalidateQueries({ queryKey: pk.student(schoolId, studentId) });
                      void queryClient.invalidateQueries({ queryKey: pk.timeline(schoolId, studentId, "HOME") });
                    })
                    .catch((error: unknown) => toast.error(errorMessage(error, t("undoFailed"))));
                },
              },
            }
          : {}),
      });
      onClose();
    },
  });

  const failure = award.isError
    ? award.error instanceof ApiRequestError && award.error.problem
      ? errorMessage(award.error, t("failed"))
      : t("networkFailed")
    : null;

  return (
    <Dialog open onOpenChange={(open) => !open && !award.isPending && onClose()}>
      <DialogContent className="max-h-[88dvh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{t("title", { child: childName })}</DialogTitle>
          <DialogDescription>{t("plainNote")}</DialogDescription>
        </DialogHeader>
        {categories.isPending ? (
          <LoadingRows rows={2} />
        ) : categories.isError ? (
          <ErrorNotice message={errorMessage(categories.error, t("loadFailed"))} onRetry={() => void categories.refetch()} />
        ) : tiles.length === 0 ? (
          <p className="rounded-xl border border-dashed p-6 text-center text-muted-foreground">{t("noSkills")}</p>
        ) : (
          <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3" aria-label={t("skills")} data-testid="home-skills">
            {tiles.map((category) => (
              <li key={category.id}>
                <SkillTile category={category} points={null} who={childName} disabled={award.isPending} onPick={(c) => award.mutate(c)} />
              </li>
            ))}
          </ul>
        )}
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          {t("note")}
          <Input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} placeholder={t("notePlaceholder")} />
        </label>
        {award.isPending ? (
          <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
            <Loader2Icon className="size-4 animate-spin" aria-hidden />
            {t("awarding")}
          </p>
        ) : null}
        {failure ? (
          <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="home-award-error">
            {failure}
          </p>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

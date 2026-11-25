"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AwardIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { StudentAvatar } from "@/components/school/student-avatar";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { errorMessage } from "@/lib/console/errors";
import { awardableCategories, categoryColor, pointsFor, readableOn, signedPoints } from "@/lib/points/categories";
import {
  awardBadge,
  fetchBadges,
  fetchCategories,
  pk,
  type ClassPointsStudent,
  type EngagementSettings,
  type PointCategory,
} from "@/lib/points/queries";
import { showBadges } from "@/lib/points/access";
import { cn } from "@/lib/utils";
import { CategoryIcon } from "./category-icon";
import { useAward } from "./use-award";

export type AwardTarget =
  | { kind: "student"; student: ClassPointsStudent }
  | { kind: "students"; students: ClassPointsStudent[] }
  | { kind: "class" };

/**
 * The award sheet: tap a skill tile and the award is made at once. A bottom sheet on phones, a dialog on larger
 * screens. "More options" sets the points (1-10) and a note; a failed award can simply be tapped again (same id).
 */
export function AwardSheet({
  classId,
  className,
  roster,
  settings,
  target,
  canAwardBadges,
  onClose,
  onAwarded,
}: {
  classId: string;
  className: string;
  roster: readonly ClassPointsStudent[];
  settings: EngagementSettings;
  target: AwardTarget;
  canAwardBadges: boolean;
  onClose: () => void;
  /** After an award went through (the sheet closes itself). */
  onAwarded: () => void;
}) {
  const t = useTranslations("school.points.sheet");
  const { school } = useSchool();
  const [points, setPoints] = useState<number | null>(null);
  const [note, setNote] = useState("");
  const { mutation, failure } = useAward(classId, className, roster);

  const categories = useQuery({
    queryKey: pk.categories(school.id, classId),
    queryFn: () => fetchCategories(school.id, classId),
  });
  const tiles = awardableCategories(categories.data ?? [], settings.needsWorkEnabled === true);

  const students = target.kind === "student" ? [target.student] : target.kind === "students" ? target.students : [...roster];
  const single = target.kind === "student" ? target.student : null;
  const who = single ? firstName(single.displayName) : t("whoMany", { count: students.length });
  const title = single ? single.displayName : target.kind === "class" ? t("titleClass") : t("titleMany", { count: students.length });
  const badgesTab = !!single && canAwardBadges && showBadges(settings);

  function award(category: PointCategory) {
    if (mutation.isPending || students.length === 0) return;
    mutation.mutate(
      {
        category,
        target: target.kind === "class" ? { wholeClass: true } : { studentIds: students.map((s) => s.studentId) },
        points,
        note,
        who,
      },
      {
        onSuccess: () => {
          onAwarded();
          onClose();
        },
      },
    );
  }

  const skills = (
    <div className="flex flex-col gap-4">
      {categories.isPending ? (
        <LoadingRows rows={3} />
      ) : categories.isError ? (
        <ErrorNotice message={errorMessage(categories.error, t("loadFailed"))} onRetry={() => void categories.refetch()} />
      ) : tiles.length === 0 ? (
        <p className="rounded-xl border border-dashed p-6 text-center text-muted-foreground">{t("noSkills")}</p>
      ) : (
        <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3" aria-label={t("skills")}>
          {tiles.map((category) => (
            <li key={category.id}>
              <SkillTile category={category} points={points} who={who} disabled={mutation.isPending} onPick={award} />
            </li>
          ))}
        </ul>
      )}

      <details className="rounded-xl border p-3">
        <summary className="min-h-11 cursor-pointer text-sm font-semibold leading-[2.75rem]">{t("more")}</summary>
        <div className="flex flex-col gap-3 pt-2">
          <fieldset className="flex flex-col gap-2">
            <legend className="pb-1 text-sm font-medium">{t("pointsLabel")}</legend>
            <div className="flex flex-wrap gap-2">
              <Button type="button" size="sm" variant={points === null ? "default" : "outline"} aria-pressed={points === null} onClick={() => setPoints(null)}>
                {t("pointsDefault")}
              </Button>
              {Array.from({ length: 10 }, (_, i) => i + 1).map((n) => (
                <Button
                  key={n}
                  type="button"
                  size="sm"
                  variant={points === n ? "default" : "outline"}
                  aria-pressed={points === n}
                  className="w-9 px-0"
                  onClick={() => setPoints(n)}
                >
                  {n}
                </Button>
              ))}
            </div>
          </fieldset>
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            {t("note")}
            <Input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} placeholder={t("notePlaceholder")} />
            <span className="text-xs font-normal text-muted-foreground">{t("noteHint")}</span>
          </label>
        </div>
      </details>

      {mutation.isPending ? (
        <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
          <Loader2Icon className="size-4 animate-spin" aria-hidden />
          {t("awarding")}
        </p>
      ) : null}
      {failure ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
          {failure}
        </p>
      ) : null}
    </div>
  );

  return (
    <Dialog open onOpenChange={(open) => !open && !mutation.isPending && onClose()}>
      <DialogContent
        className={cn(
          "max-h-[88dvh] overflow-y-auto sm:max-w-xl",
          // A bottom sheet on phones.
          "top-auto bottom-0 max-w-full translate-y-0 rounded-b-none rounded-t-3xl sm:top-[50%] sm:bottom-auto sm:max-w-xl sm:translate-y-[-50%] sm:rounded-3xl",
        )}
      >
        <DialogHeader className="items-center gap-2 sm:flex-row sm:items-center">
          {single ? (
            <StudentAvatar id={single.studentId} name={single.displayName} mediaId={single.avatar?.id} config={single.avatarConfig} size={56} />
          ) : null}
          <div className="min-w-0 text-center sm:text-start">
            <DialogTitle className="truncate text-xl">{title}</DialogTitle>
            <DialogDescription>{single ? t("descriptionOne", { total: single.total ?? 0 }) : t("descriptionMany")}</DialogDescription>
          </div>
        </DialogHeader>

        {badgesTab && single ? (
          <Tabs defaultValue="skills">
            <TabsList className="w-full">
              <TabsTrigger value="skills" className="min-h-10 flex-1">
                {t("tabSkills")}
              </TabsTrigger>
              <TabsTrigger value="badges" className="min-h-10 flex-1">
                {t("tabBadges")}
              </TabsTrigger>
            </TabsList>
            <TabsContent value="skills" className="pt-3">
              {skills}
            </TabsContent>
            <TabsContent value="badges" className="pt-3">
              <BadgePicker student={single} onAwarded={onClose} />
            </TabsContent>
          </Tabs>
        ) : (
          skills
        )}
      </DialogContent>
    </Dialog>
  );
}

const firstName = (name: string) => name.trim().split(/\s+/)[0] ?? name;

export function SkillTile({
  category,
  points,
  who,
  disabled,
  onPick,
}: {
  category: PointCategory;
  points: number | null;
  who: string;
  disabled: boolean;
  onPick: (category: PointCategory) => void;
}) {
  const t = useTranslations("school.points.sheet");
  const background = categoryColor(category);
  const amount = signedPoints(category.polarity, pointsFor(category, points));
  const label = `${amount > 0 ? "+" : "−"}${Math.abs(amount)}`;
  return (
    <button
      type="button"
      data-testid="skill-tile"
      data-polarity={category.polarity}
      disabled={disabled}
      aria-label={t("tileLabel", { skill: category.name, points: label, who })}
      onClick={() => onPick(category)}
      style={{ backgroundColor: background, color: readableOn(background) }}
      className={cn(
        "flex min-h-24 w-full flex-col items-center justify-center gap-1 rounded-2xl p-3 text-center font-bold shadow-sm transition-transform",
        "motion-safe:active:scale-95 motion-safe:hover:-translate-y-0.5 focus-visible:ring-[3px] focus-visible:ring-ring focus-visible:outline-none disabled:opacity-60",
        category.polarity === "NEEDS_WORK" && "outline-2 -outline-offset-4 outline-dashed outline-current",
      )}
    >
      <CategoryIcon icon={category.icon} className="size-8 text-3xl" />
      <span className="text-sm leading-tight break-words">{category.name}</span>
      <span aria-hidden className="rounded-full bg-black/15 px-2 py-0.5 text-xs">
        {label}
      </span>
    </button>
  );
}

function BadgePicker({ student, onAwarded }: { student: ClassPointsStudent; onAwarded: () => void }) {
  const t = useTranslations("school.points.sheet");
  const { school } = useSchool();
  const queryClient = useQueryClient();
  const badges = useQuery({ queryKey: pk.badges(school.id), queryFn: () => fetchBadges(school.id) });
  const award = useMutation({
    mutationFn: (badgeId: string) => awardBadge(school.id, student.studentId, badgeId),
    onSuccess: () => {
      toast.success(t("badgeAwarded"));
      void queryClient.invalidateQueries({ queryKey: pk.student(school.id, student.studentId) });
      onAwarded();
    },
  });
  const manual = (badges.data ?? []).filter((b) => b.criteriaType === "MANUAL" && b.active !== false);
  return (
    <div className="flex flex-col gap-3">
      {badges.isPending ? (
        <LoadingRows rows={2} />
      ) : badges.isError ? (
        <ErrorNotice message={errorMessage(badges.error, t("badgesFailed"))} onRetry={() => void badges.refetch()} />
      ) : manual.length === 0 ? (
        <p className="rounded-xl border border-dashed p-6 text-center text-muted-foreground">{t("noBadges")}</p>
      ) : (
        <ul className="flex flex-col gap-2" aria-label={t("tabBadges")}>
          {manual.map((badge) => (
            <li key={badge.id} className="flex items-center gap-3 rounded-xl border p-3">
              <span aria-hidden className="grid size-10 shrink-0 place-items-center rounded-xl bg-muted text-2xl">
                <CategoryIcon icon={badge.icon} fallback="award" className="size-6" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="font-semibold">{badge.name}</p>
                {badge.description ? <p className="text-sm text-muted-foreground">{badge.description}</p> : null}
              </div>
              <Button size="sm" disabled={award.isPending} onClick={() => award.mutate(badge.id)} data-testid="award-badge">
                <AwardIcon className="size-4" aria-hidden />
                {t("giveBadge")}
              </Button>
            </li>
          ))}
        </ul>
      )}
      {award.isError ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
          {errorMessage(award.error, t("badgeFailed"))}
        </p>
      ) : null}
    </div>
  );
}

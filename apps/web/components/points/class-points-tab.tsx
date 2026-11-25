"use client";

import { useQuery } from "@tanstack/react-query";
import { CheckIcon, MegaphoneIcon, SlidersHorizontalIcon, SquareCheckBigIcon, TrophyIcon, Volume2Icon, VolumeXIcon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { StudentAvatar } from "@/components/school/student-avatar";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { errorMessage } from "@/lib/console/errors";
import { classPointsAbilities, showLeaderboard } from "@/lib/points/access";
import { fetchClassPoints, fetchLeaderboard, pk, type ClassPointsStudent } from "@/lib/points/queries";
import type { ClassView } from "@/lib/school/queries";
import { cn } from "@/lib/utils";
import { AwardSheet, type AwardTarget } from "./award-sheet";
import { usePointsFeedback } from "./points-provider";
import { SkillsManager } from "./skills-manager";

/**
 * The signature screen: every student as an avatar with a point bubble. Tap a student to award a skill, "Select"
 * for several students, "Whole class" for everybody. Totals are net points and update at once from the award
 * response (the class overview is eventually consistent and replaces them on the next fetch).
 */
export function ClassPointsTab({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.points");
  const { school, permissions } = useSchool();
  const { muted, setMuted, reducedMotion } = usePointsFeedback();
  const abilities = classPointsAbilities(cls, permissions);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [sheet, setSheet] = useState<AwardTarget | null>(null);
  const [managing, setManaging] = useState(false);
  const [celebrate, setCelebrate] = useState(false);

  const overview = useQuery({
    queryKey: pk.overview(school.id, cls.id),
    queryFn: () => fetchClassPoints(school.id, cls.id),
    enabled: abilities.canUse,
    staleTime: 30_000,
  });

  if (!abilities.canUse) return <EmptyNotice>{t("notForYou")}</EmptyNotice>;
  if (overview.isPending) return <LoadingRows rows={4} />;
  if (overview.isError) {
    return <ErrorNotice message={errorMessage(overview.error, t("loadFailed"))} onRetry={() => void overview.refetch()} />;
  }

  const data = overview.data;
  const students = data.students.slice().sort((a, b) => a.displayName.localeCompare(b.displayName));
  const leaderboard = showLeaderboard(data.settings);

  function toggle(id: string) {
    setSelected((list) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id]));
  }

  function tap(student: ClassPointsStudent) {
    if (!abilities.canAward) return;
    if (selecting) toggle(student.studentId);
    else setSheet({ kind: "student", student });
  }

  function leaveSelecting() {
    setSelecting(false);
    setSelected([]);
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <p className="me-auto text-sm text-muted-foreground" data-testid="class-total">
          {t("classTotal", { total: data.total ?? 0, count: students.length })}
        </p>
        {abilities.canAward ? (
          <>
            <Button onClick={() => setSheet({ kind: "class" })} disabled={students.length === 0} data-testid="award-whole-class">
              <MegaphoneIcon className="size-4" aria-hidden />
              {t("wholeClass")}
            </Button>
            {selecting ? (
              <>
                <Button
                  onClick={() =>
                    setSheet({ kind: "students", students: students.filter((s) => selected.includes(s.studentId)) })
                  }
                  disabled={selected.length === 0}
                  data-testid="award-selected"
                >
                  <CheckIcon className="size-4" aria-hidden />
                  {t("awardSelected", { count: selected.length })}
                </Button>
                <Button variant="outline" onClick={leaveSelecting}>
                  <XIcon className="size-4" aria-hidden />
                  {t("cancelSelect")}
                </Button>
              </>
            ) : (
              <Button variant="outline" onClick={() => setSelecting(true)} disabled={students.length === 0} data-testid="select-mode">
                <SquareCheckBigIcon className="size-4" aria-hidden />
                {t("select")}
              </Button>
            )}
          </>
        ) : null}
        {abilities.canManageSkills ? (
          <Button variant="outline" onClick={() => setManaging(true)} data-testid="manage-skills">
            <SlidersHorizontalIcon className="size-4" aria-hidden />
            {t("manageSkills")}
          </Button>
        ) : null}
        <Button
          variant="ghost"
          size="icon"
          aria-pressed={muted}
          aria-label={muted ? t("unmute") : t("mute")}
          onClick={() => setMuted(!muted)}
          data-testid="mute-toggle"
        >
          {muted ? <VolumeXIcon className="size-5" aria-hidden /> : <Volume2Icon className="size-5" aria-hidden />}
        </Button>
      </div>

      {!abilities.canAward ? <p className="rounded-xl bg-muted p-3 text-sm font-medium">{t("archivedNote")}</p> : null}
      {selecting ? (
        <p role="status" className="rounded-xl bg-primary/10 p-3 text-sm font-medium">
          {t("selectHint")}
        </p>
      ) : null}

      {students.length === 0 ? (
        <EmptyNotice>{t("noStudents")}</EmptyNotice>
      ) : (
        <ul className="grid grid-cols-2 gap-3 min-[420px]:grid-cols-3 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6" aria-label={t("gridLabel")}>
          {students.map((student) => {
            const on = selected.includes(student.studentId);
            return (
              <li key={student.studentId}>
                <button
                  type="button"
                  data-testid="student-card"
                  aria-pressed={selecting ? on : undefined}
                  aria-label={t("studentLabel", { name: student.displayName, total: student.total ?? 0 })}
                  disabled={!abilities.canAward}
                  onClick={() => tap(student)}
                  className={cn(
                    "relative flex min-h-36 w-full flex-col items-center gap-2 rounded-3xl border bg-card p-3 pt-5 text-center shadow-xs transition-all",
                    "focus-visible:ring-[3px] focus-visible:ring-ring focus-visible:outline-none enabled:hover:bg-accent enabled:motion-safe:hover:-translate-y-0.5",
                    on && "border-primary bg-primary/10 ring-2 ring-primary",
                  )}
                >
                  <PointBubble total={student.total ?? 0} animate={celebrate && !reducedMotion} />
                  {selecting ? (
                    <span
                      aria-hidden
                      className={cn(
                        "absolute start-2 top-2 grid size-6 place-items-center rounded-full border-2 bg-background",
                        on && "border-primary bg-primary text-primary-foreground",
                      )}
                    >
                      {on ? <CheckIcon className="size-4" /> : null}
                    </span>
                  ) : null}
                  <StudentAvatar id={student.studentId} name={student.displayName} mediaId={student.avatar?.id} config={student.avatarConfig} size={72} />
                  <span className="w-full text-sm leading-tight font-semibold break-words">{student.displayName}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}

      {leaderboard ? <LeaderboardCard classId={cls.id} /> : null}

      {sheet ? (
        <AwardSheet
          classId={cls.id}
          className={cls.name}
          roster={data.students}
          settings={data.settings}
          target={sheet}
          canAwardBadges={abilities.canAwardBadges}
          onClose={() => {
            setSheet(null);
            leaveSelecting();
          }}
          onAwarded={() => setCelebrate(true)}
        />
      ) : null}
      {managing ? <SkillsManager scope={{ kind: "class", classId: cls.id }} onClose={() => setManaging(false)} /> : null}
    </div>
  );
}

/** The point bubble. It is keyed by the total, so a change replays its short pop (unless motion is reduced). */
function PointBubble({ total, animate }: { total: number; animate: boolean }) {
  const negative = total < 0;
  return (
    <span
      key={total}
      data-testid="point-bubble"
      className={cn(
        "absolute end-2 top-2 grid min-w-8 place-items-center rounded-full px-2 py-0.5 text-sm font-extrabold tabular-nums shadow-sm",
        negative ? "bg-destructive text-destructive-foreground" : "bg-primary text-primary-foreground",
        animate && "points-pop",
      )}
    >
      {total}
    </span>
  );
}

function LeaderboardCard({ classId }: { classId: string }) {
  const t = useTranslations("school.points.leaderboard");
  const { school } = useSchool();
  const board = useQuery({
    queryKey: pk.leaderboard(school.id, classId),
    queryFn: () => fetchLeaderboard(school.id, classId),
    staleTime: 30_000,
    retry: false,
  });
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <TrophyIcon className="size-5 text-primary" aria-hidden />
          {t("title")}
        </CardTitle>
      </CardHeader>
      <CardContent>
        {board.isPending ? (
          <LoadingRows rows={3} />
        ) : board.isError ? (
          <p className="text-sm text-muted-foreground">{errorMessage(board.error, t("failed"))}</p>
        ) : board.data.rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("empty")}</p>
        ) : (
          <ol className="flex flex-col gap-2" data-testid="leaderboard">
            {board.data.rows.map((row) => (
              <li key={row.studentId} className="flex items-center gap-3">
                <span className="w-6 text-center font-bold tabular-nums">{row.rank}</span>
                <StudentAvatar id={row.studentId} name={row.displayName} mediaId={row.avatar?.id} size={36} />
                <span className="min-w-0 flex-1 truncate font-medium">{row.displayName}</span>
                <span className="font-bold tabular-nums">{t("points", { count: row.total ?? 0 })}</span>
              </li>
            ))}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}

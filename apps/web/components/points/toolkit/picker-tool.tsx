"use client";

import { EMPTY_PICKER, pickNext, type PickerState } from "@school/classroom-toolkit";
import { useQuery } from "@tanstack/react-query";
import { DicesIcon, RotateCcwIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSchool } from "@/components/school/school-context";
import { StudentAvatar } from "@/components/school/student-avatar";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { errorMessage } from "@/lib/console/errors";
import { fetchRoster, sk, type ClassView, type RosterStudent } from "@/lib/school/queries";
import { usePointsFeedback } from "../points-provider";

/** Picks a student at random; nobody comes up twice until everybody was picked. The picks stay in this tab only. */
export function PickerTool({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.toolkit.picker");
  const { school } = useSchool();
  const { play, announce, reducedMotion } = usePointsFeedback();
  const roster = useQuery({ queryKey: sk.classRoster(school.id, cls.id), queryFn: () => fetchRoster(school.id, cls.id) });
  const [state, setState] = useState<PickerState>(EMPTY_PICKER);
  const [current, setCurrent] = useState<RosterStudent | null>(null);
  const [round, setRound] = useState(1);

  if (roster.isPending) return <LoadingRows rows={3} />;
  if (roster.isError) return <ErrorNotice message={errorMessage(roster.error, t("loadFailed"))} onRetry={() => void roster.refetch()} />;
  const students = roster.data;
  if (students.length === 0) return <EmptyNotice>{t("empty")}</EmptyNotice>;

  const ids = students.map((s) => s.studentId);
  const stillPresent = state.picked.filter((id) => ids.includes(id)).length;

  function pick() {
    const result = pickNext(ids, state);
    setState(result.state);
    const student = students.find((s) => s.studentId === result.id) ?? null;
    setCurrent(student);
    if (result.newRound) setRound((n) => n + 1);
    if (student) {
      play("pick");
      announce(t("picked", { name: student.displayName }));
    }
  }

  function startOver() {
    setState(EMPTY_PICKER);
    setCurrent(null);
    setRound(1);
  }

  return (
    <Card>
      <CardContent className="flex flex-col items-center gap-5 text-center">
        <div className="flex min-h-56 flex-col items-center justify-center gap-3" aria-live="polite" data-testid="picker-result">
          {current ? (
            <div key={`${current.studentId}-${state.picked.length}`} className={reducedMotion ? "" : "points-pop"}>
              <StudentAvatar id={current.studentId} name={current.displayName} mediaId={current.avatar?.id} config={current.avatarConfig} size={128} />
            </div>
          ) : (
            <DicesIcon className="size-20 text-muted-foreground" aria-hidden />
          )}
          <p className="text-3xl font-extrabold" data-testid="picked-name">
            {current ? current.displayName : t("prompt")}
          </p>
        </div>
        <div className="flex flex-wrap justify-center gap-3">
          <Button size="lg" onClick={pick} data-testid="pick-button">
            <DicesIcon className="size-5" aria-hidden />
            {current ? t("pickAgain") : t("pick")}
          </Button>
          <Button size="lg" variant="outline" onClick={startOver} disabled={state.picked.length === 0}>
            <RotateCcwIcon className="size-5" aria-hidden />
            {t("startOver")}
          </Button>
        </div>
        <p className="text-sm text-muted-foreground" data-testid="picker-progress">
          {t("progress", { picked: stillPresent, total: students.length, round })}
        </p>
      </CardContent>
    </Card>
  );
}

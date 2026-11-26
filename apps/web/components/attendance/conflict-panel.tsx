"use client";

import { AlertTriangleIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import type { Conflicts, RollStudent } from "@/lib/attendance/roll";
import { markOfRecord } from "@/lib/attendance/roll";
import { StatusPill, useMarkText } from "./status";

/**
 * Marks that someone else changed first. Nothing was overwritten: for each student the teacher keeps the saved mark
 * or sends their own again against the version now stored.
 */
export function ConflictPanel({
  students,
  conflicts,
  pending,
  disabled,
  onKeepTheirs,
  onUseMine,
}: {
  students: readonly RollStudent[];
  conflicts: Conflicts;
  pending: boolean;
  disabled: boolean;
  onKeepTheirs: (studentIds: string[]) => void;
  onUseMine: (studentIds: string[]) => void;
}) {
  const t = useTranslations("school.attendance");
  const markText = useMarkText();
  const items = Object.values(conflicts);
  if (items.length === 0) return null;
  const names = new Map(students.map((s) => [s.studentId, s.displayName]));
  const all = items.map((c) => c.studentId);

  return (
    <section aria-labelledby="conflict-title" className="flex flex-col gap-3 rounded-2xl border border-warning bg-warning/10 p-4" data-testid="conflict-panel">
      <div className="flex items-start gap-3">
        <AlertTriangleIcon className="mt-0.5 size-5 shrink-0 text-warning" aria-hidden />
        <div className="min-w-0">
          <h2 id="conflict-title" role="alert" className="font-bold">
            {t("roll.conflict.title", { count: items.length })}
          </h2>
          <p className="text-sm text-muted-foreground">{t("roll.conflict.intro")}</p>
        </div>
      </div>
      <ul className="flex flex-col gap-2">
        {items.map((c) => {
          const stored = markOfRecord(c.stored);
          const name = names.get(c.studentId) ?? "";
          return (
            <li key={c.studentId} className="flex flex-col gap-2 rounded-xl border bg-card p-3" data-testid={`conflict-${c.studentId}`} aria-label={t("roll.conflict.forStudent", { name })}>
              <p className="font-semibold">{name}</p>
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
                <span className="inline-flex flex-wrap items-center gap-2">
                  {t("roll.conflict.stored")}
                  <StatusPill status={stored.status} label={markText(stored.status, stored.minutesLate)} />
                </span>
                <span className="inline-flex flex-wrap items-center gap-2">
                  {t("roll.conflict.mine")}
                  <StatusPill status={c.mine.status} label={markText(c.mine.status, c.mine.minutesLate)} />
                </span>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" className="min-h-11" disabled={pending || disabled} onClick={() => onKeepTheirs([c.studentId])} data-testid={`keep-theirs-${c.studentId}`}>
                  {t("roll.conflict.keepTheirs")}
                </Button>
                <Button className="min-h-11" disabled={pending || disabled} onClick={() => onUseMine([c.studentId])} data-testid={`use-mine-${c.studentId}`}>
                  {pending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
                  {t("roll.conflict.useMine")}
                </Button>
              </div>
            </li>
          );
        })}
      </ul>
      {items.length > 1 ? (
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" className="min-h-11" disabled={pending || disabled} onClick={() => onKeepTheirs(all)}>
            {t("roll.conflict.keepAllTheirs")}
          </Button>
          <Button className="min-h-11" disabled={pending || disabled} onClick={() => onUseMine(all)}>
            {t("roll.conflict.useAllMine")}
          </Button>
        </div>
      ) : null}
    </section>
  );
}

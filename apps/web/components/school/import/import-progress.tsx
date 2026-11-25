"use client";

import { Loader2Icon, SearchCheckIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { percent, type ImportJob } from "@/lib/school/import/state";
import { ProgressBar } from "./import-bits";

/** The check or the import is running in the background: a bar and "N of M rows", announced politely. */
export function ProgressPanel({ job, phase }: { job: ImportJob; phase: "check" | "apply" }) {
  const t = useTranslations("school.import.progress");
  const { done, total } = job.progress;
  const value = percent(done, total);
  return (
    <Card>
      <CardContent className="flex flex-col gap-4 py-8">
        <div className="flex items-center gap-3">
          <Loader2Icon className="size-6 shrink-0 animate-spin text-primary motion-reduce:animate-none" aria-hidden />
          <h2 className="text-xl font-bold">{t(`${phase}.title`)}</h2>
        </div>
        <ProgressBar value={value} label={t("label")} />
        <p role="status" aria-live="polite" className="text-sm font-medium">
          {total > 0 ? t("rows", { done, total }) : t("starting")}
        </p>
        <p className="text-sm text-muted-foreground">{t(`${phase}.body`)}</p>
      </CardContent>
    </Card>
  );
}

/** The check stopped: the API's message (and the row it stopped at), then check again or change the columns. */
export function CheckFailed({
  job,
  onCheckAgain,
  onChangeColumns,
  pending,
  error,
}: {
  job: ImportJob;
  onCheckAgain: () => void;
  onChangeColumns: () => void;
  pending: boolean;
  error: string | null;
}) {
  const t = useTranslations("school.import.checkFailed");
  return (
    <Card>
      <CardContent className="flex flex-col gap-4 py-8">
        <div className="flex items-center gap-3">
          <TriangleAlertIcon className="size-6 shrink-0 text-destructive" aria-hidden />
          <h2 className="text-xl font-bold">{t("title")}</h2>
        </div>
        {job.failure ? (
          <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
            {job.failure.rowNumber ? `${t("row", { row: job.failure.rowNumber })}: ` : ""}
            {job.failure.message}
          </p>
        ) : null}
        {error ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {error}
          </p>
        ) : null}
        <div className="flex flex-wrap gap-2">
          <Button type="button" onClick={onCheckAgain} disabled={pending}>
            {pending ? <Loader2Icon className="animate-spin" aria-hidden /> : <SearchCheckIcon aria-hidden />}
            {t("again")}
          </Button>
          <Button type="button" variant="outline" onClick={onChangeColumns} disabled={pending}>
            {t("changeColumns")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

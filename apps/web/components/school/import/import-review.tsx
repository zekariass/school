"use client";

import { useQueryClient } from "@tanstack/react-query";
import { DownloadIcon, Loader2Icon, PlayIcon, RefreshCwIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { useSubmit } from "@/components/console/use-submit";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ApiRequestError } from "@/lib/api-error";
import { errorMessage } from "@/lib/console/errors";
import { errorReportName } from "@/lib/school/import/files";
import { downloadFile, errorReportPath, ik, startCommit, startDryRun } from "@/lib/school/import/queries";
import { defaultFilter, type RowFilter } from "@/lib/school/import/rows";
import { canCommit, commitPlan, needsNewCheck, type ImportJob } from "@/lib/school/import/state";
import { CheckboxField } from "../fields";
import { useSchool } from "../school-context";
import { ImportRows } from "./import-rows";

/**
 * Step 4: what the check found. Decide the possible duplicates, look at the problems (or download them), then
 * import after a confirmation that names the counts.
 */
export function ReviewStage({ job, onChangeColumns }: { job: ImportJob; onChangeColumns: () => void }) {
  const t = useTranslations("school.import.review");
  const { school } = useSchool();
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<RowFilter>(() => defaultFilter(job.rows));
  const [skipInvalid, setSkipInvalid] = useState(job.options.skipInvalidRows === true);
  const [confirming, setConfirming] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const plan = commitPlan(job.rows, skipInvalid);
  const resume = job.status === "FAILED";
  const purged = job.rowsPurged;

  const recheck = useSubmit({
    mutationFn: () => startDryRun(school.id, job.id),
    invalidate: [ik.list(school.id, {})],
    errorFallback: t("recheckFailed"),
    onSuccess: (started) => {
      queryClient.setQueryData(ik.job(school.id, job.id), started);
      setConfirming(false);
    },
  });

  async function downloadReport() {
    setDownloading(true);
    try {
      await downloadFile(errorReportPath(school.id, job.id), errorReportName(job.filename));
    } catch (error) {
      toast.error(errorMessage(error, t("report.failed")));
    } finally {
      setDownloading(false);
    }
  }

  const counts = job.rows;
  const tiles: { key: "total" | "create" | "update" | "unchanged" | "invalid" | "duplicates"; value: number; tone?: "bad" | "attention" }[] = [
    { key: "total", value: counts?.total ?? 0 },
    { key: "create", value: counts?.toCreate ?? 0 },
    { key: "update", value: counts?.toUpdate ?? 0 },
    { key: "unchanged", value: counts?.unchanged ?? 0 },
    { key: "invalid", value: counts?.invalid ?? 0, tone: (counts?.invalid ?? 0) > 0 ? "bad" : undefined },
    { key: "duplicates", value: counts?.duplicates ?? 0, tone: (counts?.duplicates ?? 0) > 0 ? "attention" : undefined },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="text-xl font-bold">{t("title")}</h2>
        <p className="text-muted-foreground">{t("help")}</p>
      </div>

      {resume && job.failure ? (
        <div role="alert" className="flex items-start gap-3 rounded-xl bg-destructive/10 p-4 text-sm text-destructive">
          <TriangleAlertIcon className="mt-0.5 size-5 shrink-0" aria-hidden />
          <div>
            <p className="font-semibold">{t("failedTitle")}</p>
            <p>
              {job.failure.rowNumber ? `${t("failedRow", { row: job.failure.rowNumber })}: ` : ""}
              {job.failure.message}
            </p>
            <p className="pt-1 text-foreground">{t("failedHelp")}</p>
          </div>
        </div>
      ) : null}

      <dl className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
        {tiles.map((tile) => (
          <div
            key={tile.key}
            className={
              tile.tone === "bad"
                ? "rounded-2xl border border-destructive/40 bg-destructive/5 p-3"
                : tile.tone === "attention"
                  ? "rounded-2xl border border-primary/50 bg-primary/5 p-3"
                  : "rounded-2xl border bg-card p-3"
            }
          >
            <dd className="text-2xl font-bold tabular-nums">{tile.value}</dd>
            <dt className="text-sm text-muted-foreground">{t(`counts.${tile.key}`)}</dt>
          </div>
        ))}
      </dl>

      <Card>
        <CardHeader>
          <CardTitle>{t("commit.title")}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {(counts?.invalid ?? 0) > 0 && !resume ? (
            <CheckboxField
              label={t("commit.skipInvalid", { count: counts?.invalid ?? 0 })}
              hint={t("commit.skipInvalidHint")}
              checked={skipInvalid}
              onChange={setSkipInvalid}
            />
          ) : null}
          {plan.blocked && !resume ? (
            <p role="status" className="text-sm font-medium">
              {t(`commit.blocked.${plan.blocked}`, { count: plan.blocked === "duplicates" ? plan.duplicatesOpen : plan.invalid })}
            </p>
          ) : null}
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              disabled={!canCommit(job) || (plan.blocked !== null && !resume) || recheck.isPending}
              onClick={() => setConfirming(true)}
            >
              <PlayIcon aria-hidden />
              {resume ? t("commit.resume") : t("commit.button")}
            </Button>
            {!resume ? (
              <Button type="button" variant="outline" onClick={() => void downloadReport()} disabled={downloading || purged}>
                {downloading ? <Loader2Icon className="animate-spin" aria-hidden /> : <DownloadIcon aria-hidden />}
                {t("report.download")}
              </Button>
            ) : null}
            {!resume ? (
              <Button type="button" variant="ghost" onClick={onChangeColumns}>
                {t("changeColumns")}
              </Button>
            ) : null}
            <Button type="button" variant="ghost" onClick={() => recheck.mutate()} disabled={recheck.isPending}>
              {recheck.isPending ? <Loader2Icon className="animate-spin" aria-hidden /> : <RefreshCwIcon aria-hidden />}
              {t("checkAgain")}
            </Button>
          </div>
          {recheck.isError ? (
            <p role="alert" className="text-sm font-medium text-destructive">
              {errorMessage(recheck.error, t("recheckFailed"))}
            </p>
          ) : null}
        </CardContent>
      </Card>

      {purged ? (
        <p className="rounded-xl border border-dashed p-6 text-center text-muted-foreground">{t("purged")}</p>
      ) : (
        <ImportRows job={job} filter={filter} onFilter={setFilter} canDecide={canCommit(job)} />
      )}

      {confirming ? (
        <CommitDialog
          job={job}
          skipInvalid={skipInvalid}
          resume={resume}
          onClose={() => setConfirming(false)}
          onCheckAgain={() => recheck.mutate()}
          rechecking={recheck.isPending}
        />
      ) : null}
    </div>
  );
}

/** "Import N rows?": the counts that will be created, updated and left out, then the explicit button. */
function CommitDialog({
  job,
  skipInvalid,
  resume,
  onClose,
  onCheckAgain,
  rechecking,
}: {
  job: ImportJob;
  skipInvalid: boolean;
  resume: boolean;
  onClose: () => void;
  onCheckAgain: () => void;
  rechecking: boolean;
}) {
  const t = useTranslations("school.import.review.commit");
  const { school } = useSchool();
  const queryClient = useQueryClient();
  const plan = commitPlan(job.rows, skipInvalid);

  const commit = useSubmit({
    mutationFn: () => startCommit(school.id, job.id, skipInvalid),
    invalidate: [ik.list(school.id, {})],
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: (started) => {
      queryClient.setQueryData(ik.job(school.id, job.id), started);
      onClose();
    },
  });
  const problemCode = commit.error instanceof ApiRequestError ? commit.error.problem?.code : undefined;
  const stale = needsNewCheck(problemCode);

  /** A refused commit usually means the job moved on (another tab changed it): load it again, and leave this screen if it did. */
  function refreshAfterRefusal(error: unknown) {
    void queryClient.invalidateQueries({ queryKey: ik.job(school.id, job.id), exact: true });
    if (error instanceof ApiRequestError && error.problem?.code === "import.invalid_state") {
      toast.error(errorMessage(error, t("failed")));
      onClose();
    }
  }

  function close() {
    // The counts may have changed on the server (another tab, another decision): look again.
    if (commit.isError) void queryClient.invalidateQueries({ queryKey: ik.job(school.id, job.id), exact: true });
    onClose();
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !commit.isPending && close()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{resume ? t("resumeTitle") : t("confirmTitle")}</DialogTitle>
          <DialogDescription>{resume ? t("resumeBody") : t("confirmBody")}</DialogDescription>
        </DialogHeader>
        {!resume ? (
          <ul className="flex flex-col gap-1 text-sm">
            <li>{t("plan.create", { count: plan.toCreate })}</li>
            <li>{t("plan.update", { count: plan.toUpdate })}</li>
            <li>{t("plan.unchanged", { count: plan.unchanged })}</li>
            <li>{t("plan.skipped", { count: plan.skipped })}</li>
          </ul>
        ) : null}
        {job.importType === "STUDENTS" ? <p className="text-sm text-muted-foreground">{t("contactsNote")}</p> : null}
        {commit.isError ? (
          <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
            {errorMessage(commit.error, t("failed"))}
            {stale ? <span className="block pt-1 text-foreground">{t("checkAgainHelp")}</span> : null}
          </p>
        ) : null}
        <DialogFooter>
          <Button variant="outline" onClick={close} disabled={commit.isPending}>
            {t("cancel")}
          </Button>
          {stale ? (
            <Button onClick={onCheckAgain} disabled={rechecking}>
              {rechecking ? <Loader2Icon className="animate-spin" aria-hidden /> : <RefreshCwIcon aria-hidden />}
              {t("checkAgain")}
            </Button>
          ) : (
            <Button onClick={() => commit.mutate(undefined, { onError: refreshAfterRefusal })} disabled={commit.isPending}>
              {commit.isPending ? <Loader2Icon className="animate-spin" aria-hidden /> : null}
              {resume ? t("resume") : t("confirm")}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

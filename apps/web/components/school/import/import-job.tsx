"use client";

import { useQueryClient } from "@tanstack/react-query";
import { ArrowLeftIcon, BanIcon, Loader2Icon, Trash2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Link, useRouter } from "@/i18n/navigation";
import { ApiRequestError } from "@/lib/api-error";
import { errorMessage } from "@/lib/console/errors";
import { SP } from "@/lib/school/access";
import { cancelImport, deleteImport, ik, startDryRun } from "@/lib/school/import/queries";
import { canCancel, canDelete, effectiveStage, stepOf, type ImportJob } from "@/lib/school/import/state";
import { ConfirmDialog } from "../confirm-dialog";
import { PageHeader } from "../page-header";
import { RequirePermission } from "../require-permission";
import { useSchool } from "../school-context";
import { ColumnsStage } from "./import-columns";
import { DoneStage } from "./import-done";
import { ImportStatusBadge, ImportSteps, useImportJob } from "./import-bits";
import { CheckFailed, ProgressPanel } from "./import-progress";
import { ReviewStage } from "./import-review";

/**
 * One import, at the step its state says (this is where a notification link lands, so any state resumes
 * correctly): columns, checking, review, importing, done, or cancelled. The job is polled while it runs.
 */
export function ImportJobScreen({ jobId }: { jobId: string }) {
  const t = useTranslations("school.import");
  const { school } = useSchool();
  const job = useImportJob(jobId);
  const base = `/s/${encodeURIComponent(school.slug)}/import`;

  return (
    <RequirePermission anyOf={[SP.importManage]}>
      <div className="mx-auto flex max-w-4xl flex-col gap-6">
        <Link href={base} className="inline-flex min-h-11 w-fit items-center gap-2 text-sm font-semibold text-primary hover:underline">
          <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
          {t("backToList")}
        </Link>
        {job.isPending ? (
          <LoadingRows rows={4} />
        ) : job.isError ? (
          job.error instanceof ApiRequestError && job.error.status === 404 ? (
            <Card>
              <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
                <h1 className="text-xl font-bold">{t("notFound.title")}</h1>
                <p className="max-w-sm text-muted-foreground">{errorMessage(job.error, t("notFound.body"))}</p>
                <Button asChild>
                  <Link href={base}>{t("backToList")}</Link>
                </Button>
              </CardContent>
            </Card>
          ) : (
            <ErrorNotice message={errorMessage(job.error, t("loadFailed"))} onRetry={() => void job.refetch()} />
          )
        ) : (
          <JobPage job={job.data} />
        )}
      </div>
    </RequirePermission>
  );
}

function JobPage({ job }: { job: ImportJob }) {
  const t = useTranslations("school.import");
  const { school } = useSchool();
  const queryClient = useQueryClient();
  const formatInstant = useFormatInstant();
  const [editingColumns, setEditingColumns] = useState(false);
  const stage = effectiveStage(job, editingColumns);

  const recheck = useSubmit({
    mutationFn: () => startDryRun(school.id, job.id),
    invalidate: [ik.list(school.id, {})],
    errorFallback: t("checkFailed.failed"),
    toastOnError: false,
    onSuccess: (started) => queryClient.setQueryData(ik.job(school.id, job.id), started),
  });

  return (
    <>
      <PageHeader
        title={job.filename}
        description={`${t(`kind.${job.importType}.name`)} · ${t("startedAt", { date: formatInstant(job.createdAt) })}`}
        actions={
          <>
            <ImportStatusBadge status={job.status} />
            <JobActions job={job} />
          </>
        }
      />
      <ImportSteps current={stepOf(stage)} />

      {/* Status changes (a check that finished, an import that stopped) are announced to assistive technology. */}
      <div aria-live="polite" className="sr-only">
        {t(`status.${job.status}`)}
      </div>

      {stage === "columns" ? (
        <ColumnsStage job={job} onStarted={() => setEditingColumns(false)} />
      ) : stage === "checking" ? (
        <ProgressPanel job={job} phase="check" />
      ) : stage === "checkFailed" ? (
        <CheckFailed
          job={job}
          pending={recheck.isPending}
          error={recheck.isError ? errorMessage(recheck.error, t("checkFailed.failed")) : null}
          onCheckAgain={() => recheck.mutate()}
          onChangeColumns={() => setEditingColumns(true)}
        />
      ) : stage === "review" ? (
        <ReviewStage job={job} onChangeColumns={() => setEditingColumns(true)} />
      ) : stage === "applying" ? (
        <ProgressPanel job={job} phase="apply" />
      ) : stage === "done" ? (
        <DoneStage job={job} />
      ) : (
        <Card>
          <CardContent className="flex flex-col gap-2 py-8">
            <h2 className="text-xl font-bold">{t("cancelled.title")}</h2>
            <p className="text-muted-foreground">{t("cancelled.body")}</p>
          </CardContent>
        </Card>
      )}
    </>
  );
}

/** Cancel (before the import ran) and delete, each behind a confirmation; the API's 409 is shown in the dialog. */
function JobActions({ job }: { job: ImportJob }) {
  const t = useTranslations("school.import.actions");
  const { school } = useSchool();
  const router = useRouter();
  const queryClient = useQueryClient();
  const [confirm, setConfirm] = useState<"cancel" | "delete" | null>(null);

  const cancel = useSubmit({
    mutationFn: () => cancelImport(school.id, job.id),
    invalidate: [ik.list(school.id, {})],
    errorFallback: t("failed"),
    toastOnError: false,
    successMessage: t("cancelled"),
    onSuccess: (updated) => {
      queryClient.setQueryData(ik.job(school.id, job.id), updated);
      queryClient.removeQueries({ queryKey: ik.rowsOf(school.id, job.id) });
      setConfirm(null);
    },
  });
  const remove = useSubmit({
    mutationFn: () => deleteImport(school.id, job.id),
    invalidate: [ik.list(school.id, {})],
    errorFallback: t("failed"),
    toastOnError: false,
    successMessage: t("deleted"),
    onSuccess: () => {
      router.replace(`/s/${encodeURIComponent(school.slug)}/import`);
    },
  });

  return (
    <>
      {canCancel(job.status) ? (
        <Button type="button" variant="outline" size="sm" onClick={() => setConfirm("cancel")}>
          <BanIcon aria-hidden />
          {t("cancel")}
        </Button>
      ) : null}
      {canDelete(job.status) ? (
        <Button type="button" variant="ghost" size="sm" onClick={() => setConfirm("delete")}>
          <Trash2Icon aria-hidden />
          {t("delete")}
        </Button>
      ) : null}
      {confirm === "cancel" ? (
        <ConfirmDialog
          title={t("cancelTitle")}
          description={t("cancelBody")}
          confirmLabel={t("cancel")}
          pending={cancel.isPending}
          error={cancel.isError ? errorMessage(cancel.error, t("failed")) : null}
          onConfirm={() => cancel.mutate()}
          onClose={() => setConfirm(null)}
        />
      ) : null}
      {confirm === "delete" ? (
        <ConfirmDialog
          title={t("deleteTitle")}
          description={job.status === "COMPLETED" ? t("deleteDoneBody") : t("deleteBody")}
          confirmLabel={t("delete")}
          pending={remove.isPending}
          error={remove.isError ? errorMessage(remove.error, t("failed")) : null}
          onConfirm={() => remove.mutate()}
          onClose={() => setConfirm(null)}
        />
      ) : null}
      {remove.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
    </>
  );
}

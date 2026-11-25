"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { BanIcon, FileUpIcon, PlusIcon, Trash2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { SP } from "@/lib/school/access";
import { cancelImport, deleteImport, fetchImports, ik, type ImportFilters } from "@/lib/school/import/queries";
import { canCancel, canDelete, isRunning, type ImportJob, type ImportStatus } from "@/lib/school/import/state";
import { ConfirmDialog } from "../confirm-dialog";
import { SelectField } from "../fields";
import { PageHeader } from "../page-header";
import { RequirePermission } from "../require-permission";
import { useSchool } from "../school-context";
import { ImportStatusBadge } from "./import-bits";

const STATUSES: readonly ImportStatus[] = ["UPLOADED", "PREVIEWED", "VALIDATING", "VALIDATED", "COMMITTING", "COMPLETED", "FAILED", "CANCELLED"];

/** The school's imports, newest first, with a way to start a new one and to cancel or delete old ones. */
export function ImportHistory() {
  const t = useTranslations("school.import");
  const { school } = useSchool();
  const [status, setStatus] = useState("");
  const base = `/s/${encodeURIComponent(school.slug)}/import`;
  const filters: ImportFilters = status ? { status: status as ImportStatus } : {};
  const list = useInfiniteQuery({
    queryKey: ik.list(school.id, filters),
    queryFn: ({ pageParam }) => fetchImports(school.id, filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    // A check or import started elsewhere shows its progress here too.
    refetchInterval: (query) => (query.state.data?.pages.some((p) => p.items.some((j) => isRunning(j.status))) ? 3000 : false),
    staleTime: 0,
  });
  const jobs = list.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <RequirePermission anyOf={[SP.importManage]}>
      <div className="mx-auto flex max-w-4xl flex-col gap-6">
        <PageHeader
          title={t("title")}
          description={t("subtitle")}
          actions={
            <Button asChild>
              <Link href={`${base}/new`}>
                <PlusIcon aria-hidden />
                {t("new.button")}
              </Link>
            </Button>
          }
        />

        <div className="max-w-xs">
          <SelectField
            label={t("history.filterStatus")}
            value={status}
            onChange={setStatus}
            noneLabel={t("history.allStatuses")}
            options={STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }))}
          />
        </div>

        {list.isPending ? (
          <LoadingRows rows={4} />
        ) : list.isError ? (
          <ErrorNotice message={errorMessage(list.error, t("history.loadFailed"))} onRetry={() => void list.refetch()} />
        ) : jobs.length === 0 ? (
          <EmptyNotice>{status ? t("history.emptyFiltered") : t("history.empty")}</EmptyNotice>
        ) : (
          <ul className="flex flex-col gap-2">
            {jobs.map((job) => (
              <li key={job.id}>
                <HistoryItem job={job} base={base} />
              </li>
            ))}
          </ul>
        )}
        <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()} />
      </div>
    </RequirePermission>
  );
}

function HistoryItem({ job, base }: { job: ImportJob; base: string }) {
  const t = useTranslations("school.import");
  const formatInstant = useFormatInstant();
  const { school } = useSchool();
  const [confirm, setConfirm] = useState<"cancel" | "delete" | null>(null);

  const cancel = useSubmit({
    mutationFn: () => cancelImport(school.id, job.id),
    invalidate: [ik.all(school.id)],
    errorFallback: t("actions.failed"),
    toastOnError: false,
    successMessage: t("actions.cancelled"),
    onSuccess: () => setConfirm(null),
  });
  const remove = useSubmit({
    mutationFn: () => deleteImport(school.id, job.id),
    invalidate: [ik.all(school.id)],
    errorFallback: t("actions.failed"),
    toastOnError: false,
    successMessage: t("actions.deleted"),
    onSuccess: () => setConfirm(null),
  });

  const summary = job.result
    ? t("history.result", { created: job.result.created, updated: job.result.updated, skipped: job.result.skipped })
    : job.rows
      ? t("history.rows", { count: job.rows.total })
      : job.progress.total > 0
        ? t("history.rows", { count: job.progress.total })
        : null;

  return (
    <div className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card p-3">
      <Link
        href={`${base}/${job.id}`}
        className="flex min-h-12 min-w-0 flex-1 items-center gap-3 rounded-xl outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
      >
        <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-muted text-muted-foreground">
          <FileUpIcon className="size-5" aria-hidden />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate font-semibold">{job.filename}</span>
          <span className="block truncate text-sm text-muted-foreground">
            {[t(`kind.${job.importType}.name`), formatInstant(job.createdAt), summary].filter(Boolean).join(" · ")}
          </span>
        </span>
        <ImportStatusBadge status={job.status} />
      </Link>
      <div className="flex gap-1">
        {canCancel(job.status) ? (
          <Button type="button" variant="ghost" size="sm" aria-label={`${t("actions.cancel")}: ${job.filename}`} onClick={() => setConfirm("cancel")}>
            <BanIcon aria-hidden />
            <span className="max-sm:sr-only">{t("actions.cancel")}</span>
          </Button>
        ) : null}
        {canDelete(job.status) ? (
          <Button type="button" variant="ghost" size="sm" aria-label={`${t("actions.delete")}: ${job.filename}`} onClick={() => setConfirm("delete")}>
            <Trash2Icon aria-hidden />
            <span className="max-sm:sr-only">{t("actions.delete")}</span>
          </Button>
        ) : null}
      </div>
      {confirm === "cancel" ? (
        <ConfirmDialog
          title={t("actions.cancelTitle")}
          description={t("actions.cancelBody")}
          confirmLabel={t("actions.cancel")}
          pending={cancel.isPending}
          error={cancel.isError ? errorMessage(cancel.error, t("actions.failed")) : null}
          onConfirm={() => cancel.mutate()}
          onClose={() => setConfirm(null)}
        />
      ) : null}
      {confirm === "delete" ? (
        <ConfirmDialog
          title={t("actions.deleteTitle")}
          description={job.status === "COMPLETED" ? t("actions.deleteDoneBody") : t("actions.deleteBody")}
          confirmLabel={t("actions.delete")}
          pending={remove.isPending}
          error={remove.isError ? errorMessage(remove.error, t("actions.failed")) : null}
          onConfirm={() => remove.mutate()}
          onClose={() => setConfirm(null)}
        />
      ) : null}
    </div>
  );
}

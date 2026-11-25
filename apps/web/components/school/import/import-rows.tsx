"use client";

import { useInfiniteQuery, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { CheckIcon, CircleAlertIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { errorMessage } from "@/lib/console/errors";
import { fetchImportRows, ik, resolveRow } from "@/lib/school/import/queries";
import {
  candidatesOf,
  mergeRows,
  resolutionOf,
  rowLabel,
  ROW_FILTERS,
  statusParam,
  type Candidate,
  type Resolution,
  type RowFilter,
} from "@/lib/school/import/rows";
import type { ImportJob, ImportRow } from "@/lib/school/import/state";
import { cn } from "@/lib/utils";
import { useSchool } from "../school-context";
import { useFieldLabels } from "./import-bits";

type RowPages = InfiniteData<{ items: ImportRow[]; nextCursor?: string }, string | undefined>;

/** The checked rows, filterable and paged by row number, with the decisions for possible duplicates. */
export function ImportRows({
  job,
  filter,
  onFilter,
  canDecide,
}: {
  job: ImportJob;
  filter: RowFilter;
  onFilter: (filter: RowFilter) => void;
  /** Decisions are possible while the job is validated (or after a failed commit); never when it ran or was purged. */
  canDecide: boolean;
}) {
  const t = useTranslations("school.import.review");
  const { school } = useSchool();
  const labels = useFieldLabels(job.importType);
  const rows = useInfiniteQuery({
    queryKey: ik.rows(school.id, job.id, filter),
    queryFn: ({ pageParam }) => fetchImportRows(school.id, job.id, statusParam(filter), pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    // Rows hold children's data: kept in memory for the page's life only.
    staleTime: 0,
    gcTime: 30_000,
  });
  const items = mergeRows(rows.data?.pages.map((p) => p.items) ?? []);
  const counts = job.rows;
  const filterCount: Record<RowFilter, number | undefined> = {
    ALL: counts?.total,
    INVALID: counts?.invalid,
    DUPLICATE: counts?.duplicates,
    VALID: counts?.valid,
    SKIPPED: counts?.skipped,
  };

  return (
    <section aria-labelledby="rows-heading" className="flex flex-col gap-3">
      <h3 id="rows-heading" className="text-lg font-bold">
        {t("rows.title")}
      </h3>
      <div role="group" aria-label={t("filters.label")} className="flex flex-wrap gap-2">
        {ROW_FILTERS.map((option) => {
          const count = filterCount[option];
          return (
            <Button
              key={option}
              type="button"
              size="sm"
              variant={filter === option ? "default" : "outline"}
              aria-pressed={filter === option}
              onClick={() => onFilter(option)}
            >
              {t(`filters.${option}`)}
              {count !== undefined ? <span className="font-normal opacity-80">{count}</span> : null}
            </Button>
          );
        })}
      </div>

      {rows.isPending ? (
        <LoadingRows rows={4} />
      ) : rows.isError ? (
        <ErrorNotice message={errorMessage(rows.error, t("rows.loadFailed"))} onRetry={() => void rows.refetch()} />
      ) : items.length === 0 ? (
        <EmptyNotice>{t("rows.empty")}</EmptyNotice>
      ) : (
        <ul className="flex flex-col gap-2">
          {items.map((row) => (
            <li key={row.id}>
              <RowCard job={job} row={row} filter={filter} labels={labels} canDecide={canDecide} />
            </li>
          ))}
        </ul>
      )}
      <LoadMore hasMore={!!rows.hasNextPage} loading={rows.isFetchingNextPage} onClick={() => void rows.fetchNextPage()} />
    </section>
  );
}

function RowCard({
  job,
  row,
  filter,
  labels,
  canDecide,
}: {
  job: ImportJob;
  row: ImportRow;
  filter: RowFilter;
  labels: Record<string, string>;
  canDecide: boolean;
}) {
  const t = useTranslations("school.import.review.rows");
  const label = rowLabel(row, job.importType);
  const candidates = candidatesOf(row);
  const decision = resolutionOf(row);
  const problem = (column: string | undefined | null) => (column ? (labels[column] ?? column) : null);
  return (
    <article
      className={cn(
        "flex flex-col gap-2 rounded-2xl border bg-card p-3",
        row.status === "INVALID" && "border-destructive/40",
        row.status === "DUPLICATE" && decision === null && "border-primary/50",
      )}
    >
      <header className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-sm text-muted-foreground">{t("row", { number: row.rowNumber })}</span>
        {label ? <span className="min-w-0 truncate font-semibold">{label}</span> : null}
        <RowBadges row={row} decision={decision} />
      </header>

      {row.errors.length > 0 ? (
        <ul className="flex flex-col gap-1 text-sm" aria-label={t("errors")}>
          {row.errors.map((e, i) => (
            <li key={i} className="flex items-start gap-2 font-medium text-destructive">
              <CircleAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
              <span>
                {problem(e.column) ? <strong>{problem(e.column)}: </strong> : null}
                {e.message}
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      {row.warnings.length > 0 ? (
        <ul className="flex flex-col gap-1 text-sm" aria-label={t("warnings")}>
          {row.warnings.map((w, i) => (
            <li key={i} className="flex items-start gap-2">
              <TriangleAlertIcon className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden />
              <span>
                {problem(w.column) ? <strong>{problem(w.column)}: </strong> : null}
                {w.message}
              </span>
            </li>
          ))}
        </ul>
      ) : null}

      {candidates.length > 0 ? (
        <Decision job={job} row={row} filter={filter} candidates={candidates} decision={decision} canDecide={canDecide} />
      ) : null}

      {row.cells && row.cells.length > 0 ? (
        <details className="text-sm text-muted-foreground">
          <summary className="min-h-8 cursor-pointer">{t("original")}</summary>
          <p className="break-words">{row.cells.map((c) => (c.trim() === "" ? "–" : c)).join(" · ")}</p>
        </details>
      ) : null}
    </article>
  );
}

const APPLIED = ["CREATED", "UPDATED", "UNCHANGED", "SKIPPED"] as const;

function RowBadges({ row, decision }: { row: ImportRow; decision: Resolution | null }) {
  const t = useTranslations("school.import.review.rows");
  const applied = APPLIED.find((a) => a === row.applyResult);
  return (
    <>
      {applied ? (
        <Badge variant="default">{t(`applied.${applied}`)}</Badge>
      ) : decision ? (
        <Badge variant="secondary">{t(`decided.${decision}`)}</Badge>
      ) : row.status === "DUPLICATE" ? (
        <Badge variant="outline">{t("status.DUPLICATE")}</Badge>
      ) : row.status === "INVALID" ? (
        <Badge variant="destructive">{t("status.INVALID")}</Badge>
      ) : row.status === "SKIPPED" ? (
        <Badge variant="outline">{t("status.SKIPPED")}</Badge>
      ) : row.plannedAction ? (
        <Badge variant="secondary">{t(`action.${row.plannedAction}`)}</Badge>
      ) : null}
    </>
  );
}

/** A possible duplicate: the existing students it could be, and the three ways to decide. */
function Decision({
  job,
  row,
  filter,
  candidates,
  decision,
  canDecide,
}: {
  job: ImportJob;
  row: ImportRow;
  filter: RowFilter;
  candidates: Candidate[];
  decision: Resolution | null;
  canDecide: boolean;
}) {
  const t = useTranslations("school.import.review.duplicates");
  const { school } = useSchool();
  const queryClient = useQueryClient();

  const decide = useSubmit({
    mutationFn: (vars: { resolution: Resolution; studentId?: string }) =>
      resolveRow(school.id, job.id, row.id, vars),
    toastOnError: false,
    errorFallback: t("failed"),
    onSuccess: (updated) => {
      // The row changes in place (it stays in the list until the next visit); the counts come from the job.
      queryClient.setQueryData<RowPages>(ik.rows(school.id, job.id, filter), (data) =>
        data
          ? { ...data, pages: data.pages.map((p) => ({ ...p, items: p.items.map((r) => (r.id === updated.id ? updated : r)) })) }
          : data,
      );
      void queryClient.invalidateQueries({ queryKey: ik.job(school.id, job.id), exact: true });
      void queryClient.invalidateQueries({ queryKey: ik.rowsOf(school.id, job.id), predicate: (q) => q.queryKey[6] !== filter });
    },
  });
  const busy = decide.isPending;
  const matched = row.normalized?.matchedId;
  const linkedTo = decision === "LINK" && typeof matched === "string" ? matched : null;

  return (
    <div className="flex flex-col gap-3 rounded-xl bg-muted/50 p-3">
      <p className="text-sm font-semibold">{t("question")}</p>
      <ul className="flex flex-col gap-2">
        {candidates.map((c) => (
          <li key={c.studentId} className="flex flex-col gap-2 rounded-xl border bg-background p-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="min-w-0 text-sm">
              <p className="truncate font-semibold">{c.displayName}</p>
              <p className="text-muted-foreground">
                {[
                  c.dateOfBirth,
                  c.studentNumber ? t("number", { number: c.studentNumber }) : null,
                  c.classes.length > 0 ? c.classes.join(", ") : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </p>
              <p className="text-muted-foreground">
                {t("similarity", { percent: Math.round(c.similarity * 100) })} · {c.sameBirthDate ? t("sameBirth") : t("nearBirth")}
              </p>
            </div>
            {canDecide ? (
              <Button
                type="button"
                size="sm"
                variant={linkedTo === c.studentId ? "default" : "outline"}
                aria-pressed={linkedTo === c.studentId}
                disabled={busy}
                onClick={() => {
                  decide.mutate({ resolution: "LINK", studentId: c.studentId });
                }}
              >
                {linkedTo === c.studentId ? <CheckIcon aria-hidden /> : null}
                {t("link")}
              </Button>
            ) : null}
          </li>
        ))}
      </ul>
      {canDecide ? (
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            size="sm"
            variant={decision === "CREATE_NEW" ? "default" : "outline"}
            aria-pressed={decision === "CREATE_NEW"}
            disabled={busy}
            onClick={() => {
              decide.mutate({ resolution: "CREATE_NEW" });
            }}
          >
            {decision === "CREATE_NEW" ? <CheckIcon aria-hidden /> : null}
            {t("createNew")}
          </Button>
          <Button
            type="button"
            size="sm"
            variant={decision === "SKIP" ? "default" : "outline"}
            aria-pressed={decision === "SKIP"}
            disabled={busy}
            onClick={() => {
              decide.mutate({ resolution: "SKIP" });
            }}
          >
            {decision === "SKIP" ? <CheckIcon aria-hidden /> : null}
            {t("skip")}
          </Button>
        </div>
      ) : null}
      {decide.isError ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {errorMessage(decide.error, t("failed"))}
        </p>
      ) : null}
    </div>
  );
}

"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeftIcon, LockIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { RelativeTime } from "@/components/feed/relative-time";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { caseControls } from "@/lib/moderation/access";
import { CONTENT_GC_MS, modk } from "@/lib/moderation/keys";
import { outcomeOf } from "@/lib/moderation/outcome";
import { assignCase, fetchCase, setCaseLegalHold, setCasePriority, type ReasonAction } from "@/lib/moderation/queries";
import { PRIORITIES, QUEUE_TARGETS, type CaseAction, type CaseDetail, type CaseReport, type Decision, type Priority } from "@/lib/moderation/types";
import { actionKey, isUrgentPriority, knownOr, reasonKey, roleKey } from "@/lib/moderation/view";
import { Pill } from "./chips";
import { DecisionDialog, ReasonActionDialog } from "./case-dialogs";
import { ContentPanel } from "./content-panel";
import { NothingHere } from "./nothing-here";
import { useModerationScope } from "./scope";

type Dialog = { kind: "decide"; initial?: Decision } | { kind: "reason"; action: ReasonAction };

/**
 * One case. Everything comes from `GET /moderation/cases/{id}` and is dropped when this page is left (`gcTime` 0): the
 * reporters' details exist only for leads and platform moderators (the API sends them or not: `reporterIdentityVisible`),
 * decision reasons are text people wrote. A 404 means nothing is here for this person, and that is all the page says.
 * Buttons come from the case's status and history and the viewer's permissions (`caseControls`); the API decides again.
 */
export function CaseView({ caseId }: { caseId: string }) {
  const t = useTranslations("moderation.case");
  const scope = useModerationScope();
  const query = useQuery({ queryKey: modk.case(caseId), queryFn: () => fetchCase(caseId), gcTime: CONTENT_GC_MS, retry: false });
  const outcome = query.isError ? outcomeOf(query.error) : null;

  if (outcome?.kind === "nothing") return <NothingHere homeHref={scope.queuePath} />;
  return (
    <div className="flex flex-col gap-5" data-testid="case-view">
      <Button asChild variant="ghost" size="sm" className="self-start">
        <Link href={scope.queuePath}>
          <ArrowLeftIcon className="rtl:rotate-180" aria-hidden />
          {t("back")}
        </Link>
      </Button>
      {query.isPending ? <LoadingRows rows={4} /> : null}
      {outcome ? <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} /> : null}
      {query.data ? <CaseBody detail={query.data} caseId={caseId} /> : null}
    </div>
  );
}

function CaseBody({ detail, caseId }: { detail: CaseDetail; caseId: string }) {
  const t = useTranslations("moderation.case");
  const tq = useTranslations("moderation.queue");
  const tRole = useTranslations("moderation.roles");
  const scope = useModerationScope();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const summary = detail.summary;
  const controls = caseControls(detail, scope.viewer);
  const urgent = isUrgentPriority(summary?.priority);
  const decidedKey = summary?.decision ? actionKey(summary.decision) : "OTHER";

  /** Every answer of a case action is the case again: keep it in the (memory-only) cache and refresh the queues. */
  function accept(next: CaseDetail) {
    queryClient.setQueryData(modk.case(caseId), next);
    void queryClient.invalidateQueries({ queryKey: modk.queueAll });
    setDialog(null);
    setActionError(null);
  }
  const assign = useMutation({
    mutationFn: () => assignCase(caseId),
    onSuccess: accept,
    onError: (error) => setActionError(errorMessage(error, t("actionFailed"))),
    gcTime: 0,
  });
  const priority = useMutation({
    mutationFn: (value: Priority) => setCasePriority(caseId, value),
    onSuccess: accept,
    onError: (error) => setActionError(errorMessage(error, t("actionFailed"))),
    gcTime: 0,
  });
  const hold = useMutation({
    mutationFn: (held: boolean) => setCaseLegalHold(caseId, held),
    onSuccess: () => {
      setActionError(null);
      void queryClient.invalidateQueries({ queryKey: modk.case(caseId) });
    },
    onError: (error) => setActionError(errorMessage(error, t("actionFailed"))),
    gcTime: 0,
  });

  const mine = summary?.assignedTo === scope.viewer.userId;
  return (
    <>
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-bold">{t(`title.${knownOr(summary?.targetType, QUEUE_TARGETS, "POST")}`)}</h1>
        <p className="flex flex-wrap items-center gap-2" data-testid="case-facts">
          <Pill tone={urgent ? "urgent" : "muted"}>{tq(`priority.${knownOr(summary?.priority, PRIORITIES, "NORMAL")}`)}</Pill>
          <Pill tone="primary">{tq(`status.${knownOr(summary?.status, ["OPEN", "ASSIGNED", "RESOLVED", "ESCALATED"] as const, "OPEN")}`)}</Pill>
          {summary?.queue === "PLATFORM" ? <Pill>{t("platformQueue")}</Pill> : null}
          {summary?.subjectRole ? <Pill>{tq("about", { role: tRole(roleKey(summary.subjectRole)) })}</Pill> : null}
          {summary?.legalHold ? (
            <Pill tone="urgent" testId="case-hold">
              <LockIcon className="me-1 size-3" aria-hidden />
              {tq("legalHold")}
            </Pill>
          ) : null}
        </p>
        <p className="text-sm text-muted-foreground">
          {summary?.assignedTo ? (mine ? t("assignedToYou") : t("assignedToOther")) : t("unassigned")}
          {decidedKey !== "OTHER" ? ` · ${t("decided", { decision: t(`decision.${decidedKey}`) })}` : null}
        </p>
        {detail.incidentId ? (
          <p>
            <Button asChild variant="outline" size="sm" data-testid="case-incident-link">
              <Link href={`${scope.incidentsPath}/${detail.incidentId}`}>{t("openIncident")}</Link>
            </Button>
          </p>
        ) : null}
      </header>

      {actionError ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="case-action-error">
          {actionError}
        </p>
      ) : null}

      <section aria-label={t("actions")} className="flex flex-wrap items-center gap-2 rounded-2xl border bg-card p-3" data-testid="case-controls">
        {controls.assignToMe ? (
          <Button type="button" size="sm" variant="outline" onClick={() => assign.mutate()} disabled={assign.isPending} data-testid="case-assign">
            {t("assignToMe")}
          </Button>
        ) : null}
        {controls.decisions.length > 0 ? (
          <Button type="button" size="sm" onClick={() => setDialog({ kind: "decide" })} data-testid="case-decide">
            {t("decide")}
          </Button>
        ) : null}
        {controls.restore ? (
          <Button type="button" size="sm" variant="outline" onClick={() => setDialog({ kind: "reason", action: "restore" })} data-testid="case-restore">
            {t("restore")}
          </Button>
        ) : null}
        {controls.liftSuspension ? (
          <Button type="button" size="sm" variant="outline" onClick={() => setDialog({ kind: "reason", action: "lift-suspension" })} data-testid="case-lift">
            {t("liftSuspension")}
          </Button>
        ) : null}
        {controls.reopen ? (
          <Button type="button" size="sm" variant="outline" onClick={() => setDialog({ kind: "reason", action: "reopen" })} data-testid="case-reopen">
            {t("reopen")}
          </Button>
        ) : null}
        {controls.referUp ? (
          <Button type="button" size="sm" variant="outline" onClick={() => setDialog({ kind: "reason", action: "refer-up" })} data-testid="case-refer-up">
            {t("referUp")}
          </Button>
        ) : null}
        {controls.legalHold && summary ? (
          <Button type="button" size="sm" variant="outline" disabled={hold.isPending} onClick={() => hold.mutate(!summary.legalHold)} data-testid="case-hold-toggle">
            {summary.legalHold ? t("liftHold") : t("setHold")}
          </Button>
        ) : null}
        {controls.decisions.length === 0 && !controls.reopen && !controls.restore && !controls.liftSuspension && !controls.assignToMe ? (
          <p className="text-sm text-muted-foreground">{t("noActions")}</p>
        ) : null}
      </section>

      {controls.setPriority && summary ? (
        <div role="group" aria-label={t("priorityLabel")} className="flex flex-wrap items-center gap-2" data-testid="case-priority-set">
          <span className="text-sm font-medium text-muted-foreground">{t("priorityLabel")}</span>
          {PRIORITIES.map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={summary.priority === value}
              disabled={priority.isPending}
              onClick={() => summary.priority !== value && priority.mutate(value)}
              className={summary.priority === value ? "min-h-9 rounded-full border border-primary bg-primary px-3 text-sm font-semibold text-primary-foreground" : "min-h-9 rounded-full border px-3 text-sm font-semibold hover:bg-accent"}
            >
              {tq(`priority.${value}`)}
            </button>
          ))}
        </div>
      ) : null}

      <ReportsList reports={detail.reports ?? []} identityVisible={!!detail.reporterIdentityVisible} />
      <ContentPanel caseId={caseId} />
      <HistoryList actions={detail.actions ?? []} userId={scope.viewer.userId} />

      {dialog?.kind === "decide" ? (
        <DecisionDialog caseId={caseId} decisions={controls.decisions} platformLock={controls.platformLock} initial={dialog.initial} onDone={accept} onClose={() => setDialog(null)} />
      ) : null}
      {dialog?.kind === "reason" ? <ReasonActionDialog caseId={caseId} action={dialog.action} onDone={accept} onClose={() => setDialog(null)} /> : null}
    </>
  );
}

/**
 * The reports of the case. The reporter's role and the reason are for every moderator; the reporter's identity and the
 * details they wrote are shown only when the API sent them (leads and platform moderators): never guessed or filled in.
 */
export function ReportsList({ reports, identityVisible }: { reports: readonly CaseReport[]; identityVisible: boolean }) {
  const t = useTranslations("moderation.case.reports");
  const tr = useTranslations("moderation.report.reasons");
  const tRole = useTranslations("moderation.roles");
  return (
    <section aria-label={t("title")} className="flex flex-col gap-2" data-testid="case-reports">
      <h2 className="text-lg font-bold">{t("title")}</h2>
      {identityVisible ? <p className="text-sm text-muted-foreground">{t("identityVisible")}</p> : <p className="text-sm text-muted-foreground">{t("identityHidden")}</p>}
      {reports.length === 0 ? <EmptyNotice>{t("none")}</EmptyNotice> : null}
      <ul className="flex flex-col gap-2">
        {reports.map((r, index) => (
          <li key={`${r.createdAt}-${index}`} className="flex flex-col gap-1 rounded-xl border bg-card p-3" data-testid="case-report">
            <p className="flex flex-wrap items-center gap-x-2 text-sm">
              <span className="font-semibold">{tr(reasonKey(r.reasonCode))}</span>
              <span className="text-muted-foreground">{t("by", { role: tRole(roleKey(r.reporterRole)) })}</span>
              {r.createdAt ? <RelativeTime instant={r.createdAt} className="text-xs text-muted-foreground" /> : null}
            </p>
            {r.reporterId ? (
              <p className="text-xs text-muted-foreground" data-testid="case-reporter-id">
                {t("reporterId", { id: r.reporterId })}
              </p>
            ) : null}
            {r.details ? (
              <p dir="auto" className="whitespace-pre-wrap [overflow-wrap:anywhere]" data-testid="case-report-details">
                {r.details}
              </p>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The append-only history, oldest first, with the reason of each decision when the API sent one. */
export function HistoryList({ actions, userId }: { actions: readonly CaseAction[]; userId: string }) {
  const t = useTranslations("moderation.case.history");
  const tDecision = useTranslations("moderation.case.decision");
  const sorted = [...actions].sort((a, b) => (a.createdAt ?? "").localeCompare(b.createdAt ?? ""));
  return (
    <section aria-label={t("title")} className="flex flex-col gap-2" data-testid="case-history">
      <h2 className="text-lg font-bold">{t("title")}</h2>
      {sorted.length === 0 ? <EmptyNotice>{t("none")}</EmptyNotice> : null}
      <ol className="flex flex-col gap-2">
        {sorted.map((a, index) => {
          const key = actionKey(a.action);
          return (
            <li key={a.id ?? index} className="flex flex-col gap-1 rounded-xl border bg-card p-3" data-testid="case-action">
              <p className="flex flex-wrap items-center gap-x-2 text-sm">
                <span className="font-semibold">{key === "OTHER" ? t("other") : tDecision(key)}</span>
                {a.level ? <span className="text-muted-foreground">{t(`level.${a.level === "PLATFORM" ? "PLATFORM" : "SCHOOL"}`)}</span> : null}
                <span className="text-muted-foreground">{a.actorId === userId ? t("you") : t("someone")}</span>
                {a.createdAt ? <RelativeTime instant={a.createdAt} className="text-xs text-muted-foreground" /> : null}
              </p>
              {a.notes ? (
                <p dir="auto" className="whitespace-pre-wrap [overflow-wrap:anywhere]" data-testid="case-action-notes">
                  {a.notes}
                </p>
              ) : null}
            </li>
          );
        })}
      </ol>
    </section>
  );
}

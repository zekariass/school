"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon, LockIcon, ShieldCheckIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { RelativeTime } from "@/components/feed/relative-time";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { Pill } from "@/components/moderation/chips";
import { NothingHere } from "@/components/moderation/nothing-here";
import { useModerationScope } from "@/components/moderation/scope";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { canAssignIncidentToMe, canOpenSafeguarding } from "@/lib/moderation/access";
import { CONTENT_GC_MS, modk } from "@/lib/moderation/keys";
import { outcomeOf } from "@/lib/moderation/outcome";
import { assignIncident, fetchIncident, openIncident, setIncidentLegalHold } from "@/lib/moderation/queries";
import {
  INCIDENT_CATEGORIES,
  INCIDENT_OPEN_REASONS,
  INCIDENT_SEVERITIES,
  INCIDENT_STATUSES,
  type IncidentDetail,
  type IncidentOpenReason,
  type IncidentSummary,
} from "@/lib/moderation/types";
import { knownOr } from "@/lib/moderation/view";
import { IncidentActions } from "./incident-actions";
import { IncidentTimeline } from "./incident-timeline";

/**
 * One incident. The metadata comes first (leads only; the API audits that read); the narrative and the notes are read
 * only after a reason from a fixed list is picked (`POST …/open`, audited, 60 an hour). What was read lives in this
 * component's state: not in the query cache, not in the address, the page title or a toast, and it is gone when the
 * page is left. A 404 is the page of an unknown address, whoever the viewer is. Nobody is told anything automatically:
 * telling the child's guardians and referring to an authority are recorded here as human decisions.
 */
export function IncidentView({ incidentId }: { incidentId: string }) {
  const scope = useModerationScope();
  // Without the permission nothing is asked of the API at all: the page is the page of an unknown address.
  if (!canOpenSafeguarding(scope.viewer.permissions)) return <NothingHere homeHref={scope.incidentsPath} />;
  return <IncidentLoader incidentId={incidentId} />;
}

function IncidentLoader({ incidentId }: { incidentId: string }) {
  const t = useTranslations("safeguarding.incident");
  const scope = useModerationScope();
  const query = useQuery({ queryKey: modk.incident(incidentId), queryFn: () => fetchIncident(incidentId), gcTime: CONTENT_GC_MS, retry: false });
  const outcome = query.isError ? outcomeOf(query.error) : null;

  if (outcome?.kind === "nothing") return <NothingHere homeHref={scope.incidentsPath} />;
  return (
    <div className="flex flex-col gap-5" data-testid="incident-view">
      <Button asChild variant="ghost" size="sm" className="self-start">
        <Link href={scope.incidentsPath}>
          <ArrowLeftIcon className="rtl:rotate-180" aria-hidden />
          {t("back")}
        </Link>
      </Button>
      {query.isPending ? <LoadingRows rows={3} /> : null}
      {outcome ? <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} /> : null}
      {query.data ? <IncidentBody incidentId={incidentId} initial={query.data} /> : null}
    </div>
  );
}

function IncidentBody({ incidentId, initial }: { incidentId: string; initial: IncidentSummary }) {
  const t = useTranslations("safeguarding.incident");
  const ti = useTranslations("safeguarding.incidents");
  const scope = useModerationScope();
  const [summary, setSummary] = useState<IncidentSummary>(initial);
  const [detail, setDetail] = useState<IncidentDetail | null>(null);
  const [reason, setReason] = useState<IncidentOpenReason | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [nothing, setNothing] = useState(false);

  const open = useMutation({
    mutationFn: (value: IncidentOpenReason) => openIncident(incidentId, value),
    onSuccess: (data, value) => {
      setDetail(data);
      setReason(value);
      if (data.summary) setSummary(data.summary);
      setError(null);
    },
    onError: (e) => {
      if (outcomeOf(e).kind === "nothing") setNothing(true);
      else setError(errorMessage(e, t("openFailed")));
    },
    gcTime: 0,
  });
  /** Reads the incident again with the reason already chosen (every read is audited): after a change the timeline is the API's, not ours. */
  const refresh = () => {
    if (reason) open.mutate(reason);
  };
  const assign = useMutation({
    mutationFn: () => assignIncident(incidentId, scope.viewer.userId),
    onSuccess: (next) => {
      setSummary(next);
      setError(null);
    },
    onError: (e) => setError(errorMessage(e, t("actionFailed"))),
    gcTime: 0,
  });
  const hold = useMutation({
    mutationFn: (held: boolean) => setIncidentLegalHold(incidentId, held),
    onSuccess: (view) => {
      setSummary((s) => ({ ...s, legalHold: view.legalHold }));
      setError(null);
    },
    onError: (e) => setError(errorMessage(e, t("actionFailed"))),
    gcTime: 0,
  });

  if (nothing) return <NothingHere homeHref={scope.incidentsPath} />;
  const urgent = summary.severity === "HIGH" || summary.severity === "CRITICAL";
  return (
    <>
      <header className="flex flex-col gap-2">
        <h1 className="flex items-center gap-2 text-2xl font-bold">
          <ShieldCheckIcon className="size-6 text-primary" aria-hidden />
          {ti(`category.${knownOr(summary.category, INCIDENT_CATEGORIES, "OTHER")}`)}
        </h1>
        <p className="flex flex-wrap items-center gap-2" data-testid="incident-facts">
          <Pill tone={urgent ? "urgent" : "muted"}>{ti(`severity.${knownOr(summary.severity, INCIDENT_SEVERITIES, "MEDIUM")}`)}</Pill>
          <Pill tone="primary">{ti(`status.${knownOr(summary.status, INCIDENT_STATUSES, "OPEN")}`)}</Pill>
          {summary.legalHold ? (
            <Pill tone="urgent" testId="incident-hold">
              <LockIcon className="me-1 size-3" aria-hidden />
              {ti("legalHold")}
            </Pill>
          ) : null}
        </p>
        <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2" data-testid="incident-dates">
          <Fact label={t("created")} value={summary.createdAt} />
          <Fact label={t("updated")} value={summary.updatedAt} />
          {summary.closedAt ? <Fact label={t("closed")} value={summary.closedAt} /> : null}
          <Fact label={t("guardianInformed")} value={summary.guardianInformedAt} none={t("notRecorded")} />
          <Fact label={t("referred")} value={summary.referredAt} none={t("notRecorded")} />
        </dl>
        <p className="text-sm text-muted-foreground">{summary.assignedTo ? (summary.assignedTo === scope.viewer.userId ? ti("assignedToYou") : ti("assignedToOther")) : ti("unassigned")}</p>
        {summary.sourceCaseId ? (
          <p>
            <Button asChild variant="outline" size="sm">
              <Link href={`${scope.queuePath}/${summary.sourceCaseId}`}>{t("sourceCase")}</Link>
            </Button>
          </p>
        ) : null}
        <p className="rounded-xl bg-muted p-3 text-sm text-muted-foreground" data-testid="incident-nobody-told">
          {t("nobodyTold")}
        </p>
      </header>

      {error ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="incident-error">
          {error}
        </p>
      ) : null}

      <section aria-label={t("controls")} className="flex flex-wrap items-center gap-2 rounded-2xl border bg-card p-3" data-testid="incident-controls">
        {canAssignIncidentToMe(summary, scope.viewer) ? (
          <Button type="button" size="sm" variant="outline" disabled={assign.isPending} onClick={() => assign.mutate()} data-testid="incident-assign">
            {t("assignToMe")}
          </Button>
        ) : null}
        <Button type="button" size="sm" variant="outline" disabled={hold.isPending} onClick={() => hold.mutate(!summary.legalHold)} data-testid="incident-hold-toggle">
          {summary.legalHold ? t("liftHold") : t("setHold")}
        </Button>
      </section>

      {detail ? (
        <>
          <section aria-label={t("narrative")} className="flex flex-col gap-2 rounded-2xl border bg-card p-4" data-testid="incident-narrative">
            <h2 className="text-lg font-bold">{t("narrative")}</h2>
            <p dir="auto" className="whitespace-pre-wrap [overflow-wrap:anywhere]">
              {detail.narrative}
            </p>
            {detail.referredTo ? (
              <p className="text-sm" data-testid="incident-referred-to">
                <span className="font-semibold">{t("referredTo")}</span> <span dir="auto" className="[overflow-wrap:anywhere]">{detail.referredTo}</span>
              </p>
            ) : null}
            {(detail.involvedUserIds ?? []).length > 0 ? <p className="text-sm text-muted-foreground">{t("involved", { count: detail.involvedUserIds?.length ?? 0 })}</p> : null}
          </section>
          <IncidentActions incidentId={incidentId} summary={summary} onChanged={refresh} onSummary={setSummary} />
          <IncidentTimeline notes={detail.notes ?? []} userId={scope.viewer.userId} />
        </>
      ) : (
        <section aria-label={t("open.title")} className="flex flex-col gap-3 rounded-2xl border bg-card p-4" data-testid="incident-open">
          <h2 className="text-lg font-bold">{t("open.title")}</h2>
          <p className="text-sm text-muted-foreground">{t("open.help")}</p>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-medium">{t("open.reasonLabel")}</legend>
            {INCIDENT_OPEN_REASONS.map((value) => (
              <label key={value} className="flex min-h-10 cursor-pointer items-center gap-3 rounded-xl border px-3">
                <input type="radio" name="open-reason" className="size-4 accent-primary" checked={reason === value} onChange={() => setReason(value)} data-testid={`open-reason-${value}`} />
                <span>{t(`open.reasons.${value}`)}</span>
              </label>
            ))}
          </fieldset>
          <div>
            <Button type="button" disabled={!reason || open.isPending} onClick={() => reason && open.mutate(reason)} data-testid="incident-open-button">
              {t("open.submit")}
            </Button>
          </div>
        </section>
      )}
    </>
  );
}

function Fact({ label, value, none }: { label: string; value?: string | null; none?: string }) {
  return (
    <div className="flex gap-2">
      <dt className="font-medium text-muted-foreground">{label}</dt>
      <dd>{value ? <RelativeTime instant={value} /> : (none ?? "")}</dd>
    </div>
  );
}

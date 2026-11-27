"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { ShieldCheckIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { RelativeTime } from "@/components/feed/relative-time";
import { EmptyNotice, ErrorNotice, LoadMore, LoadingRows } from "@/components/console/states";
import { ChipGroup, Pill } from "@/components/moderation/chips";
import { NothingHere } from "@/components/moderation/nothing-here";
import { useModerationScope } from "@/components/moderation/scope";
import { PageHeader } from "@/components/school/page-header";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { canOpenSafeguarding } from "@/lib/moderation/access";
import { QUEUE_GC_MS, modk } from "@/lib/moderation/keys";
import { outcomeOf } from "@/lib/moderation/outcome";
import { fetchPlatformIncidents, fetchSchoolIncidents } from "@/lib/moderation/queries";
import { INCIDENT_CATEGORIES, INCIDENT_SEVERITIES, INCIDENT_STATUSES, type IncidentSummary } from "@/lib/moderation/types";
import { knownOr } from "@/lib/moderation/view";

const STATUS_FILTERS = ["", ...INCIDENT_STATUSES] as const;
const SEVERITY_FILTERS = ["", ...INCIDENT_SEVERITIES] as const;
const CATEGORY_FILTERS = ["", ...INCIDENT_CATEGORIES] as const;

/**
 * The safeguarding incidents of a school (or, for platform leads, of every school). Leads only: a person without
 * `school.safeguarding.read` gets the same page as for an unknown address, and so does a 404 from the API (which answers
 * 404 to everybody else, administrators included, "as if nothing existed"). A row is metadata: category, severity,
 * status, dates. The API never sends text in a list and there is no search over narratives.
 */
export function IncidentsView() {
  const scope = useModerationScope();
  if (!canOpenSafeguarding(scope.viewer.permissions)) return <NothingHere />;
  return <IncidentList />;
}

function IncidentList() {
  const t = useTranslations("safeguarding.incidents");
  const scope = useModerationScope();
  const [filters, setFilters] = useState({ status: "", severity: "", category: "" });
  const scopeKey = scope.schoolId ?? "platform";
  const query = useInfiniteQuery({
    queryKey: modk.incidents(scopeKey, filters.status, filters.severity, filters.category),
    queryFn: ({ pageParam }) => (scope.schoolId ? fetchSchoolIncidents(scope.schoolId, filters, pageParam) : fetchPlatformIncidents(filters, pageParam)),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    gcTime: QUEUE_GC_MS,
    retry: false,
  });
  const items: IncidentSummary[] = query.data?.pages.flatMap((p) => p.items) ?? [];
  const outcome = query.isError ? outcomeOf(query.error) : null;
  // A 404 replaces the whole page, header included: nothing here says what the page is for.
  if (outcome?.kind === "nothing") return <NothingHere />;

  return (
    <div className="flex flex-col gap-5" data-testid="incidents-view">
      <PageHeader
        title={t("title")}
        description={scope.kind === "school" ? t("subtitleSchool", { school: scope.schoolName ?? "" }) : t("subtitlePlatform")}
      />
      <div className="flex flex-col gap-2 rounded-2xl border bg-card p-3">
        <ChipGroup
          label={t("filters.status")}
          testId="incident-filter-status"
          value={filters.status}
          options={STATUS_FILTERS.map((s) => ({ value: s, label: s ? t(`status.${s}`) : t("filters.any") }))}
          onChange={(status) => setFilters({ ...filters, status })}
        />
        <ChipGroup
          label={t("filters.severity")}
          testId="incident-filter-severity"
          value={filters.severity}
          options={SEVERITY_FILTERS.map((s) => ({ value: s, label: s ? t(`severity.${s}`) : t("filters.any") }))}
          onChange={(severity) => setFilters({ ...filters, severity })}
        />
        <ChipGroup
          label={t("filters.category")}
          testId="incident-filter-category"
          value={filters.category}
          options={CATEGORY_FILTERS.map((c) => ({ value: c, label: c ? t(`category.${c}`) : t("filters.any") }))}
          onChange={(category) => setFilters({ ...filters, category })}
        />
      </div>
      {query.isPending ? <LoadingRows rows={4} /> : null}
      {outcome ? <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} /> : null}
      {query.isSuccess && items.length === 0 ? <EmptyNotice>{t("empty")}</EmptyNotice> : null}
      <ul className="flex flex-col gap-2">
        {items.map((item) => (
          <IncidentRow key={item.id} item={item} basePath={scope.incidentsPath} userId={scope.viewer.userId} />
        ))}
      </ul>
      <LoadMore hasMore={!!query.hasNextPage} loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
    </div>
  );
}

export function IncidentRow({ item, basePath, userId }: { item: IncidentSummary; basePath: string; userId: string }) {
  const t = useTranslations("safeguarding.incidents");
  const urgent = item.severity === "HIGH" || item.severity === "CRITICAL";
  return (
    <li data-testid="incident-row" data-severity={item.severity}>
      <Link href={`${basePath}/${item.id}`} className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card p-3 hover:bg-accent/40">
        <ShieldCheckIcon className={urgent ? "size-5 shrink-0 text-destructive" : "size-5 shrink-0 text-muted-foreground"} aria-hidden />
        <div className="min-w-0 flex-1">
          <p className="font-semibold">{t(`category.${knownOr(item.category, INCIDENT_CATEGORIES, "OTHER")}`)}</p>
          <p className="mt-1 flex flex-wrap items-center gap-2">
            <Pill tone={urgent ? "urgent" : "muted"}>{t(`severity.${knownOr(item.severity, INCIDENT_SEVERITIES, "MEDIUM")}`)}</Pill>
            <Pill tone="primary">{t(`status.${knownOr(item.status, INCIDENT_STATUSES, "OPEN")}`)}</Pill>
            {item.legalHold ? <Pill tone="urgent">{t("legalHold")}</Pill> : null}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">{item.assignedTo ? (item.assignedTo === userId ? t("assignedToYou") : t("assignedToOther")) : t("unassigned")}</p>
        </div>
        <div className="text-end text-sm text-muted-foreground">{item.createdAt ? <RelativeTime instant={item.createdAt} /> : null}</div>
      </Link>
    </li>
  );
}

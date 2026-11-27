"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { ShieldAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { RelativeTime } from "@/components/feed/relative-time";
import { EmptyNotice, ErrorNotice, LoadMore, LoadingRows } from "@/components/console/states";
import { PageHeader } from "@/components/school/page-header";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { MOD_PERM } from "@/lib/moderation/access";
import { QUEUE_GC_MS, modk } from "@/lib/moderation/keys";
import { outcomeOf } from "@/lib/moderation/outcome";
import { fetchPlatformQueue, fetchSchoolQueue, type QueueFilters } from "@/lib/moderation/queries";
import { CASE_STATUSES, PRIORITIES, QUEUE_TARGETS, type CaseSummary } from "@/lib/moderation/types";
import { isUrgentPriority, knownOr, reasonCounts, roleKey } from "@/lib/moderation/view";
import { ChipGroup, Pill } from "./chips";
import { NothingHere } from "./nothing-here";
import { useModerationScope } from "./scope";

const PRIORITY_FILTERS = ["", ...PRIORITIES] as const;
const TARGET_FILTERS = ["", ...QUEUE_TARGETS] as const;

/**
 * The moderation queue of a school (`school.moderation.manage`) or of the platform (`platform.moderation.manage`).
 * A row holds references and enums only (what, how urgent, which reasons, how many people reported): the API never
 * puts content, reporter identities or details in a list, and neither does this screen. The page, the menu and the API all check
 * the permission; the page shows what the API answered and nothing it did not.
 */
export function QueueView() {
  const t = useTranslations("moderation.queue");
  const scope = useModerationScope();
  const needed = scope.kind === "school" ? MOD_PERM.schoolModeration : MOD_PERM.platformModeration;
  if (!scope.viewer.permissions.has(needed)) return <NothingHere />;
  return (
    <div className="flex flex-col gap-5" data-testid="moderation-queue">
      <PageHeader
        title={t("title")}
        description={scope.kind === "school" ? t("subtitleSchool", { school: scope.schoolName ?? "" }) : t("subtitlePlatform")}
      />
      <QueueList />
    </div>
  );
}

function QueueList() {
  const t = useTranslations("moderation.queue");
  const scope = useModerationScope();
  const [filters, setFilters] = useState<QueueFilters>({ status: "OPEN", priority: "", targetType: "" });
  const key =
    scope.kind === "school"
      ? modk.schoolQueue(scope.schoolId ?? "", filters.status, filters.priority, filters.targetType)
      : modk.platformQueue(filters.status, filters.priority, filters.targetType);
  const query = useInfiniteQuery({
    queryKey: key,
    queryFn: ({ pageParam }) => (scope.kind === "school" ? fetchSchoolQueue(scope.schoolId ?? "", filters, pageParam) : fetchPlatformQueue(filters, pageParam)),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    gcTime: QUEUE_GC_MS,
    retry: false,
  });
  const items: CaseSummary[] = query.data?.pages.flatMap((p) => p.items) ?? [];
  const outcome = query.isError ? outcomeOf(query.error) : null;

  return (
    <>
      <div className="flex flex-col gap-2 rounded-2xl border bg-card p-3">
        <ChipGroup
          label={t("filters.status")}
          testId="filter-status"
          value={filters.status}
          options={CASE_STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }))}
          onChange={(status) => setFilters({ ...filters, status })}
        />
        <ChipGroup
          label={t("filters.priority")}
          testId="filter-priority"
          value={filters.priority}
          options={PRIORITY_FILTERS.map((p) => ({ value: p, label: p ? t(`priority.${p}`) : t("filters.any") }))}
          onChange={(priority) => setFilters({ ...filters, priority })}
        />
        <ChipGroup
          label={t("filters.target")}
          testId="filter-target"
          value={filters.targetType}
          options={TARGET_FILTERS.map((x) => ({ value: x, label: x ? t(`target.${x}`) : t("filters.any") }))}
          onChange={(targetType) => setFilters({ ...filters, targetType })}
        />
      </div>

      {query.isPending ? <LoadingRows rows={4} /> : null}
      {outcome?.kind === "nothing" ? <NothingHere /> : null}
      {outcome && outcome.kind !== "nothing" ? (
        <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} />
      ) : null}
      {query.isSuccess && items.length === 0 ? <EmptyNotice>{t("empty")}</EmptyNotice> : null}
      <ul className="flex flex-col gap-2">
        {items.map((item) => (
          <CaseRow key={item.id} item={item} basePath={scope.queuePath} showQueue={scope.kind === "platform"} />
        ))}
      </ul>
      <LoadMore hasMore={!!query.hasNextPage} loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
    </>
  );
}

/** One case: references and enums, with a link to the case page. */
export function CaseRow({ item, basePath, showQueue }: { item: CaseSummary; basePath: string; showQueue: boolean }) {
  const t = useTranslations("moderation.queue");
  const tRole = useTranslations("moderation.roles");
  const urgent = isUrgentPriority(item.priority);
  const reasons = reasonCounts(item.reasons);
  return (
    <li data-testid="case-row" data-priority={item.priority} data-status={item.status}>
      <Link href={`${basePath}/${item.id}`} className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card p-3 hover:bg-accent/40">
        <ShieldAlertIcon className={urgent ? "size-5 shrink-0 text-destructive" : "size-5 shrink-0 text-muted-foreground"} aria-hidden />
        <div className="min-w-0 flex-1">
          <p className="font-semibold">{t(`target.${knownOr(item.targetType, QUEUE_TARGETS, "POST")}`)}</p>
          <p className="mt-1 flex flex-wrap items-center gap-2">
            <Pill tone={urgent ? "urgent" : "muted"} testId="case-priority">
              {t(`priority.${knownOr(item.priority, PRIORITIES, "NORMAL")}`)}
            </Pill>
            <Pill tone="primary">{t(`status.${knownOr(item.status, CASE_STATUSES, "OPEN")}`)}</Pill>
            {item.subjectRole ? <Pill>{t("about", { role: tRole(roleKey(item.subjectRole)) })}</Pill> : null}
            {item.legalHold ? <Pill tone="urgent">{t("legalHold")}</Pill> : null}
            {showQueue && item.queue === "SCHOOL" ? <Pill>{t("fromSchool")}</Pill> : null}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            {reasons.map((r) => (r.count > 1 ? `${t(`reason.${r.code}`)} (${r.count})` : t(`reason.${r.code}`))).join(" · ")}
          </p>
        </div>
        <div className="text-end text-sm text-muted-foreground">
          <p>{t("reporters", { count: item.reporterCount ?? 0 })}</p>
          {item.createdAt ? <RelativeTime instant={item.createdAt} /> : null}
        </div>
      </Link>
    </li>
  );
}

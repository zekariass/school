"use client";

import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeftIcon, BellRingIcon, DownloadIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Link } from "@/i18n/navigation";
import {
  ank,
  downloadReportCsv,
  fetchAnnouncement,
  fetchRecipients,
  fetchReport,
  remindUnacknowledged,
  type ChannelCounts,
  type ReportSummary,
} from "@/lib/announcements/queries";
import { canRemind, channelState, csvFileName, percent, recipientFilters, waitParts, type RecipientFilter } from "@/lib/announcements/view";
import { ApiRequestError } from "@/lib/api-error";
import { errorMessage } from "@/lib/console/errors";
import { nextCursorOf } from "@/lib/feed/pages";
import { saveBlob } from "@/lib/points/queries";
import { cn } from "@/lib/utils";
import { TypeBadge } from "./type-badge";
import { useAnnouncementCapabilities, useRefreshAnnouncements } from "./use-announcements";

/**
 * `/s/{slug}/announcements/{id}/report`: for the author and school managers. Delivered, read and acknowledged are
 * counted separately; push, email and text messages are counted per channel. The recipient table is server-filtered
 * and keyset paged (contact details are masked by the API), the CSV goes through the BFF as a Blob (`no-store`), and
 * "remind" asks the API, which holds the limits and answers with its own messages.
 */
export function ReportView({ announcementId }: { announcementId: string }) {
  const t = useTranslations("school.announcements.report");
  const { school } = useSchool();
  const base = `/s/${encodeURIComponent(school.slug)}/announcements/${announcementId}`;
  const announcement = useQuery({
    queryKey: ank.one(school.id, announcementId),
    queryFn: () => fetchAnnouncement(school.id, announcementId),
    retry: false,
  });
  const summary = useQuery({
    queryKey: ank.report(school.id, announcementId),
    queryFn: () => fetchReport(school.id, announcementId),
    retry: false,
    // While delivery is running the numbers move: look again until the API says it is complete.
    refetchInterval: (query) => (query.state.data && !query.state.data.deliveryComplete ? 5_000 : false),
  });

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <Link href={base} className="inline-flex min-h-11 items-center gap-2 self-start text-sm font-semibold text-primary hover:underline">
        <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
        {t("back")}
      </Link>
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-bold">{t("title")}</h1>
        {announcement.data ? (
          <p className="flex flex-wrap items-center gap-2 text-muted-foreground">
            <TypeBadge type={announcement.data.type} />
            <span className="font-medium [overflow-wrap:anywhere]">{announcement.data.title}</span>
          </p>
        ) : null}
      </header>

      {summary.isPending ? (
        <LoadingRows rows={4} />
      ) : summary.isError ? (
        <ErrorNotice message={errorMessage(summary.error, t("loadFailed"))} onRetry={() => void summary.refetch()} />
      ) : (
        <>
          <Counts report={summary.data} />
          <ChannelTable report={summary.data} />
          <Actions schoolId={school.id} announcementId={announcementId} report={summary.data} announcement={announcement.data} />
          <RecipientTable schoolId={school.id} announcementId={announcementId} requiresAcknowledgement={summary.data.requiresAcknowledgement} />
        </>
      )}
    </div>
  );
}

function Stat({ label, value, of, hint, testId }: { label: string; value: number; of?: number; hint?: string; testId: string }) {
  const t = useTranslations("school.announcements.report");
  const pct = of !== undefined ? percent(value, of) : null;
  return (
    <div className="flex flex-col gap-1 rounded-2xl border bg-card p-4" data-testid={testId}>
      <dt className="text-sm font-semibold text-muted-foreground">{label}</dt>
      <dd className="flex flex-wrap items-baseline gap-x-2">
        <span className="text-3xl font-bold tabular-nums" data-testid={`${testId}-value`}>
          {value}
        </span>
        {of !== undefined ? (
          <span className="text-sm text-muted-foreground">
            {t("ofRecipients", { total: of })} · {pct}%
          </span>
        ) : null}
      </dd>
      {pct !== null ? (
        <div className="h-2 overflow-hidden rounded-full bg-muted" aria-hidden>
          <div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
        </div>
      ) : null}
      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

function Counts({ report }: { report: ReportSummary }) {
  const t = useTranslations("school.announcements.report");
  const format = useFormatInstant();
  return (
    <section className="flex flex-col gap-3" aria-label={t("title")}>
      <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <Stat testId="stat-recipients" label={t("recipients")} value={report.recipients} />
        <Stat testId="stat-delivered" label={t("delivered")} value={report.delivered} of={report.recipients} />
        <Stat testId="stat-failed" label={t("failed")} value={report.failed} />
        <Stat testId="stat-read" label={t("read")} value={report.read} of={report.recipients} />
        {report.requiresAcknowledgement ? <Stat testId="stat-acknowledged" label={t("acknowledged")} value={report.acknowledged} of={report.recipients} /> : null}
        <Stat testId="stat-late" label={t("lateReaders")} value={report.lateReaders} hint={t("lateHelp")} />
        {report.requiresAcknowledgement ? (
          <Stat testId="stat-reminders" label={t("reminders")} value={report.reminders} hint={report.lastReminderAt ? t("lastReminder", { when: format(report.lastReminderAt) }) : undefined} />
        ) : null}
      </dl>
      <p className="text-sm text-muted-foreground" role="status" data-testid="delivery-state">
        {report.deliveryComplete ? t("deliveryComplete") : t("deliveryRunning")}
      </p>
    </section>
  );
}

function ChannelTable({ report }: { report: ReportSummary }) {
  const t = useTranslations("school.announcements.report");
  const rows: { key: "push" | "email" | "sms"; counts: ChannelCounts }[] = [
    { key: "push", counts: report.push },
    { key: "email", counts: report.email },
    { key: "sms", counts: report.sms },
  ];
  return (
    <section className="flex flex-col gap-2" aria-label={t("channelsTitle")}>
      <h2 className="text-lg font-bold">{t("channelsTitle")}</h2>
      <div className="overflow-x-auto rounded-2xl border">
        <Table data-testid="channel-table">
          <TableHeader>
            <TableRow>
              <TableHead scope="col" />
              <TableHead scope="col" className="text-end">{t("sent")}</TableHead>
              <TableHead scope="col" className="text-end">{t("channelFailed")}</TableHead>
              <TableHead scope="col" className="text-end">{t("skipped")}</TableHead>
              <TableHead scope="col" className="text-end">{t("pending")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map(({ key, counts }) => (
              <TableRow key={key} data-testid={`channel-${key}`}>
                <TableHead scope="row" className="font-semibold">{t(`channel.${key}`)}</TableHead>
                <TableCell className="text-end tabular-nums">{counts.sent}</TableCell>
                <TableCell className={cn("text-end tabular-nums", counts.failed > 0 && "font-bold text-destructive")}>{counts.failed}</TableCell>
                <TableCell className="text-end tabular-nums">{counts.skipped}</TableCell>
                <TableCell className="text-end tabular-nums">{counts.pending}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </section>
  );
}

function Actions({
  schoolId,
  announcementId,
  report,
  announcement,
}: {
  schoolId: string;
  announcementId: string;
  report: ReportSummary;
  announcement: { status: ReportSummary["status"]; requiresAcknowledgement: boolean; can: { remind: boolean } } | undefined;
}) {
  const t = useTranslations("school.announcements.report");
  const queryClient = useQueryClient();
  const refresh = useRefreshAnnouncements();
  const caps = useAnnouncementCapabilities(schoolId);
  const [reminding, setReminding] = useState(false);
  const exportCsv = useMutation({
    mutationFn: async () => saveBlob(await downloadReportCsv(schoolId, announcementId), csvFileName(announcementId, new Date())),
    onSuccess: () => toast.success(t("exported")),
  });
  const remind = useMutation({
    mutationFn: () => remindUnacknowledged(schoolId, announcementId),
    onSuccess: async (result) => {
      toast.success(t("remind.done", { count: result.targeted }));
      setReminding(false);
      await queryClient.invalidateQueries({ queryKey: ank.report(schoolId, announcementId) });
      await refresh();
    },
  });
  const showRemind = announcement ? canRemind({ status: report.status, requiresAcknowledgement: report.requiresAcknowledgement, can: announcement.can }) : false;

  const wait = remind.error instanceof ApiRequestError && remind.error.retryAfter !== undefined ? waitParts(remind.error.retryAfter) : null;
  const remindError = remind.isError ? `${errorMessage(remind.error, t("remind.failed"))}${wait ? ` ${t("remind.retryIn", wait)}` : ""}` : null;

  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="outline" onClick={() => exportCsv.mutate()} disabled={exportCsv.isPending} data-testid="export-csv">
          {exportCsv.isPending ? <Loader2Icon className="animate-spin" aria-hidden /> : <DownloadIcon aria-hidden />}
          {t("export")}
        </Button>
        {showRemind ? (
          <Button type="button" onClick={() => { remind.reset(); setReminding(true); }} data-testid="remind-open">
            <BellRingIcon aria-hidden />
            {t("remind.button")}
          </Button>
        ) : null}
      </div>
      <p className="text-xs text-muted-foreground">{t("exportNote")}</p>
      {exportCsv.isError ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="export-error">
          {errorMessage(exportCsv.error, t("exportFailed"))}
        </p>
      ) : null}
      {reminding ? (
        <ConfirmDialog
          title={t("remind.title")}
          description={t("remind.description", { hours: caps.data?.reminderMinHours ?? 24 })}
          confirmLabel={t("remind.confirm")}
          destructive={false}
          pending={remind.isPending}
          error={remindError}
          onConfirm={() => remind.mutate()}
          onClose={() => setReminding(false)}
        />
      ) : null}
    </section>
  );
}

function RecipientTable({ schoolId, announcementId, requiresAcknowledgement }: { schoolId: string; announcementId: string; requiresAcknowledgement: boolean }) {
  const t = useTranslations("school.announcements.report");
  const format = useFormatInstant();
  const [filter, setFilter] = useState<RecipientFilter>("ALL");
  const filters = recipientFilters(requiresAcknowledgement);
  const query = useInfiniteQuery({
    queryKey: ank.recipients(schoolId, announcementId, filter),
    queryFn: ({ pageParam }) => fetchRecipients(schoolId, announcementId, filter, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => nextCursorOf(last),
    retry: false,
  });
  const rows = useMemo(() => (query.data?.pages ?? []).flatMap((p) => p.items), [query.data]);

  return (
    <section className="flex flex-col gap-3" aria-label={t("recipients")}>
      <h2 className="text-lg font-bold">{t("recipients")}</h2>
      <div role="group" aria-label={t("filterLabel")} className="flex flex-wrap gap-2">
        {filters.map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={filter === value}
            onClick={() => setFilter(value)}
            data-testid={`recipient-filter-${value}`}
            className={cn("min-h-10 rounded-full border px-4 text-sm font-medium", filter === value ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
          >
            {t(`filters.${value}`)}
          </button>
        ))}
      </div>
      {query.isPending ? (
        <LoadingRows rows={4} />
      ) : query.isError ? (
        <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("table.empty")}</EmptyNotice>
      ) : (
        <>
          <div className="overflow-x-auto rounded-2xl border">
            <Table data-testid="recipient-table">
              <caption className="sr-only">{t("table.caption")}</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">{t("table.name")}</TableHead>
                  <TableHead scope="col">{t("table.role")}</TableHead>
                  <TableHead scope="col">{t("table.contact")}</TableHead>
                  <TableHead scope="col">{t("table.push")}</TableHead>
                  <TableHead scope="col">{t("table.email")}</TableHead>
                  <TableHead scope="col">{t("table.sms")}</TableHead>
                  <TableHead scope="col">{t("table.read")}</TableHead>
                  {requiresAcknowledgement ? <TableHead scope="col">{t("table.acknowledged")}</TableHead> : null}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r) => (
                  <TableRow key={r.userId} data-testid="recipient-row">
                    <TableCell className="min-w-40 font-medium">
                      {r.displayName}
                      {r.viaStudentName ? <span className="block text-xs font-normal text-muted-foreground">{t("table.child")}: {r.viaStudentName}</span> : null}
                    </TableCell>
                    <TableCell>{t(`roles.${r.role}`)}</TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {r.maskedPhone ? <span className="block">{r.maskedPhone}</span> : null}
                      {r.maskedEmail ? <span className="block">{r.maskedEmail}</span> : null}
                    </TableCell>
                    <StateCell state={r.push} />
                    <StateCell state={r.email} />
                    <StateCell state={r.sms} />
                    <TableCell>{r.readAt ? format(r.readAt) : <span className="text-muted-foreground">{t("table.notYet")}</span>}</TableCell>
                    {requiresAcknowledgement ? (
                      <TableCell>
                        {r.acknowledgedAt ? (
                          <>
                            {format(r.acknowledgedAt)}
                            {typeof r.acknowledgedRevision === "number" ? <span className="block text-xs text-muted-foreground">{t("table.version", { revision: r.acknowledgedRevision })}</span> : null}
                          </>
                        ) : (
                          <span className="text-muted-foreground">{t("table.notYet")}</span>
                        )}
                      </TableCell>
                    ) : null}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <LoadMore hasMore={!!query.hasNextPage} loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
        </>
      )}
    </section>
  );
}

/** A channel's state as a word (never colour alone). */
function StateCell({ state }: { state: string }) {
  const t = useTranslations("school.announcements.report.states");
  const kind = channelState(state);
  return (
    <TableCell className={cn("text-sm", kind === "failed" && "font-bold text-destructive", kind === "none" && "text-muted-foreground")}>
      {kind === "none" ? "—" : t(kind)}
    </TableCell>
  );
}

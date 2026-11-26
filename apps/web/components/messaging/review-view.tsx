"use client";

import { useInfiniteQuery, useMutation, useQuery } from "@tanstack/react-query";
import { EyeIcon, Loader2Icon, ShieldCheckIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadMore, LoadingRows } from "@/components/console/states";
import { MediaImage } from "@/components/media/media-image";
import { RequirePermission } from "@/components/school/require-permission";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { MESSAGING_GC_MS, mk } from "@/lib/messaging/keys";
import { fetchReports, fetchReviewConversations, fetchStudentBlocks, reviewConversation } from "@/lib/messaging/queries";
import { REASON_MAX, REASON_MIN, checkReason, reasonRemaining } from "@/lib/messaging/review";
import { messageSegments } from "@/lib/messaging/text";
import { REPORT_REASONS, type ReportReason, type ReviewConversation, type ReviewedMessage, type ReviewedPage } from "@/lib/messaging/types";
import { SP } from "@/lib/school/access";
import { cn } from "@/lib/utils";
import { RelativeTime } from "@/components/feed/relative-time";
import { usePersonText } from "./person-name";
import { useMessageTime } from "./use-message-time";

type Tab = "conversations" | "reports" | "blocks";
const TABS: readonly Tab[] = ["conversations", "reports", "blocks"];

/**
 * The safeguarding review (holders of `school.messaging.review` only: the page, the menu and the API all check). Reading a
 * conversation needs a reason of 10 to 300 characters that is stored with the reader's name in the audit log; nothing read
 * here is kept: the pages live in this component's state and vanish when the reader is closed.
 */
export function ReviewView() {
  return (
    <RequirePermission anyOf={[SP.messagingReview]}>
      <ReviewContent />
    </RequirePermission>
  );
}

function ReviewContent() {
  const t = useTranslations("school.messaging.review");
  const { school } = useSchool();
  const [tab, setTab] = useState<Tab>("conversations");
  const [target, setTarget] = useState<string | null>(null);

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-5" data-testid="review-mode">
      <div role="note" className="flex items-start gap-3 rounded-2xl border-2 border-primary bg-primary/10 p-4" data-testid="review-banner">
        <ShieldCheckIcon className="mt-0.5 size-6 shrink-0 text-primary" aria-hidden />
        <div>
          <p className="text-lg font-bold">{t("banner.title")}</p>
          <p className="text-sm">{t("banner.body")}</p>
        </div>
      </div>
      <header className="flex flex-col gap-1">
        <h1 className="text-3xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle", { school: school.name })}</p>
      </header>
      <div role="tablist" aria-label={t("tabs.label")} className="flex flex-wrap gap-2">
        {TABS.map((value) => (
          <button
            key={value}
            role="tab"
            type="button"
            aria-selected={tab === value}
            id={`review-tab-${value}`}
            aria-controls={`review-panel-${value}`}
            onClick={() => setTab(value)}
            data-testid={`review-tab-${value}`}
            className={cn("min-h-10 rounded-full border px-4 text-sm font-semibold", tab === value ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
          >
            {t(`tabs.${value}`)}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`review-panel-${tab}`} aria-labelledby={`review-tab-${tab}`} className="flex flex-col gap-3">
        {tab === "conversations" ? <ConversationsTab schoolId={school.id} onRead={setTarget} /> : null}
        {tab === "reports" ? <ReportsTab schoolId={school.id} onRead={setTarget} /> : null}
        {tab === "blocks" ? <BlocksTab schoolId={school.id} onRead={setTarget} /> : null}
      </div>
      {target ? <ReasonThenReader schoolId={school.id} conversationId={target} onClose={() => setTarget(null)} /> : null}
    </div>
  );
}

function ConversationsTab({ schoolId, onRead }: { schoolId: string; onRead: (id: string) => void }) {
  const t = useTranslations("school.messaging.review.conversations");
  const tKind = useTranslations("school.messaging.kind");
  const { participant } = usePersonText();
  const query = useInfiniteQuery({
    queryKey: mk.reviewConversations(schoolId),
    queryFn: ({ pageParam }) => fetchReviewConversations(schoolId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    gcTime: MESSAGING_GC_MS,
    retry: false,
  });
  const items: ReviewConversation[] = query.data?.pages.flatMap((p) => p.items) ?? [];
  return (
    <>
      <p className="text-sm text-muted-foreground">{t("help")}</p>
      {query.isPending ? <LoadingRows rows={3} /> : null}
      {query.isError ? <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} /> : null}
      {query.isSuccess && items.length === 0 ? <EmptyNotice>{t("empty")}</EmptyNotice> : null}
      <ul className="flex flex-col gap-2">
        {items.map((c) => (
          <li key={c.id} className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card p-3" data-testid="review-conversation">
            <div className="min-w-0 flex-1">
              <p className="font-semibold">{c.type === "DIRECT" ? tKind(c.directKind ?? "direct") : tKind(c.groupKind ?? "GROUP")}</p>
              <p className="truncate text-sm text-muted-foreground">{c.participants.map((p) => participant(p)).join(" · ")}</p>
              <p className="mt-1 flex flex-wrap gap-2 text-xs">
                {c.adminVisible ? <span className="rounded-full bg-secondary px-2 py-0.5 font-semibold text-secondary-foreground">{t("adminVisible")}</span> : null}
                {c.openReports > 0 ? <span className="rounded-full bg-destructive/10 px-2 py-0.5 font-semibold text-destructive">{t("openReports", { count: c.openReports })}</span> : null}
                {c.lastMessageAt ? <RelativeTime instant={c.lastMessageAt} className="text-muted-foreground" /> : null}
              </p>
            </div>
            <Button type="button" size="sm" onClick={() => onRead(c.id)} data-testid="review-read">
              <EyeIcon aria-hidden />
              {t("read")}
            </Button>
          </li>
        ))}
      </ul>
      <LoadMore hasMore={!!query.hasNextPage} loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
    </>
  );
}

function ReportsTab({ schoolId, onRead }: { schoolId: string; onRead: (id: string) => void }) {
  const t = useTranslations("school.messaging.review.reports");
  const tReason = useTranslations("school.messaging.report.reasons");
  const [status, setStatus] = useState<"OPEN" | "RESOLVED">("OPEN");
  const query = useQuery({ queryKey: mk.reviewReports(schoolId, status), queryFn: () => fetchReports(schoolId, status), gcTime: MESSAGING_GC_MS, retry: false });
  const time = useMessageTime(undefined);
  return (
    <>
      <div className="flex gap-2" role="group" aria-label={t("filter")}>
        {(["OPEN", "RESOLVED"] as const).map((s) => (
          <button key={s} type="button" aria-pressed={status === s} onClick={() => setStatus(s)} className={cn("min-h-10 rounded-full border px-4 text-sm font-semibold", status === s ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}>
            {t(`status.${s}`)}
          </button>
        ))}
      </div>
      {query.isPending ? <LoadingRows rows={3} /> : null}
      {query.isError ? <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} /> : null}
      {query.isSuccess && query.data.length === 0 ? <EmptyNotice>{t("empty")}</EmptyNotice> : null}
      <ul className="flex flex-col gap-2">
        {(query.data ?? []).map((r) => (
          <li key={r.id} className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card p-3" data-testid="review-report">
            <div className="min-w-0 flex-1">
              <p className="font-semibold">{tReason(reasonKey(r.reason))}</p>
              <p className="text-sm text-muted-foreground">{time.full(r.createdAt)}</p>
            </div>
            <Button type="button" size="sm" variant="outline" onClick={() => onRead(r.conversationId)} data-testid="review-report-read">
              <EyeIcon aria-hidden />
              {t("read")}
            </Button>
          </li>
        ))}
      </ul>
    </>
  );
}

function reasonKey(reason: string): ReportReason {
  return (REPORT_REASONS as readonly string[]).includes(reason) ? (reason as ReportReason) : "OTHER";
}

function BlocksTab({ schoolId, onRead }: { schoolId: string; onRead: (id: string) => void }) {
  const t = useTranslations("school.messaging.review.blocks");
  const query = useQuery({ queryKey: mk.reviewBlocks(schoolId), queryFn: () => fetchStudentBlocks(schoolId), gcTime: MESSAGING_GC_MS, retry: false });
  const time = useMessageTime(undefined);
  return (
    <>
      <p className="text-sm text-muted-foreground">{t("help")}</p>
      {query.isPending ? <LoadingRows rows={3} /> : null}
      {query.isError ? <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} /> : null}
      {query.isSuccess && query.data.length === 0 ? <EmptyNotice>{t("empty")}</EmptyNotice> : null}
      <ul className="flex flex-col gap-2">
        {(query.data ?? []).map((b) => (
          <li key={b.id} className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card p-3" data-testid="review-block">
            <p className="min-w-0 flex-1 font-medium">{t("row", { when: time.full(b.createdAt) })}</p>
            {b.conversationId ? (
              <Button type="button" size="sm" variant="outline" onClick={() => onRead(b.conversationId!)}>
                <EyeIcon aria-hidden />
                {t("read")}
              </Button>
            ) : null}
          </li>
        ))}
      </ul>
    </>
  );
}

/** Step 1: the reason (audited). Step 2: the reader. */
function ReasonThenReader({ schoolId, conversationId, onClose }: { schoolId: string; conversationId: string; onClose: () => void }) {
  const t = useTranslations("school.messaging.review.reason");
  const [reason, setReason] = useState("");
  const [pages, setPages] = useState<ReviewedPage[] | null>(null);
  const [usedReason, setUsedReason] = useState("");
  const check = checkReason(reason);
  const remaining = reasonRemaining(reason);

  const read = useMutation({
    gcTime: 0,
    mutationFn: ({ why, cursor }: { why: string; cursor?: string }) => reviewConversation(schoolId, conversationId, why, cursor),
    onSuccess: (page, { cursor, why }) => {
      setUsedReason(why);
      setPages((current) => (cursor && current ? [...current, page] : [page]));
    },
  });

  if (pages) {
    return (
      <Reader
        pages={pages}
        loadingMore={read.isPending}
        error={read.error}
        onLoadMore={() => {
          const cursor = pages[pages.length - 1]?.nextCursor;
          if (cursor) read.mutate({ why: usedReason, cursor });
        }}
        onClose={() => {
          setPages(null);
          onClose();
        }}
      />
    );
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !read.isPending && onClose()}>
      <DialogContent>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (check.ok) read.mutate({ why: check.reason });
          }}
        >
          <DialogHeader>
            <DialogTitle>{t("title")}</DialogTitle>
            <DialogDescription>{t("description")}</DialogDescription>
          </DialogHeader>
          <p className="rounded-xl bg-primary/10 p-3 text-sm font-medium" data-testid="review-audit-notice">
            {t("audited")}
          </p>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="review-reason" className="text-sm font-medium">
              {t("label", { min: REASON_MIN, max: REASON_MAX })}
            </label>
            <Textarea id="review-reason" autoFocus rows={3} value={reason} onChange={(e) => setReason(e.target.value)} aria-describedby="review-reason-help" data-testid="review-reason" className="[field-sizing:fixed] [overflow-wrap:anywhere]" />
            <p id="review-reason-help" className="text-sm text-muted-foreground">
              {t("help")} {remaining < 0 ? t("tooLong") : t("remaining", { count: remaining })}
            </p>
          </div>
          {read.error ? (
            <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="review-error">
              {errorMessage(read.error, t("failed"))}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={read.isPending}>
              {t("cancel")}
            </Button>
            <Button type="submit" disabled={!check.ok || read.isPending} data-testid="review-confirm">
              {read.isPending ? <Loader2Icon className="animate-spin" aria-hidden /> : <EyeIcon aria-hidden />}
              {t("confirm")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function Reader({ pages, loadingMore, error, onLoadMore, onClose }: { pages: ReviewedPage[]; loadingMore: boolean; error: unknown; onLoadMore: () => void; onClose: () => void }) {
  const t = useTranslations("school.messaging.review.reader");
  const time = useMessageTime(undefined);
  // each page is newest first; show the whole as a transcript, oldest at the top
  const messages: ReviewedMessage[] = [...pages].reverse().flatMap((p) => [...p.messages].reverse());
  const hasMore = !!pages[pages.length - 1]?.nextCursor;
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-2xl" data-testid="review-reader">
        <DialogHeader>
          <DialogTitle>{t("title")}</DialogTitle>
          <DialogDescription>{t("description")}</DialogDescription>
        </DialogHeader>
        {hasMore ? (
          <div className="flex flex-col items-center gap-1">
            <Button type="button" variant="outline" size="sm" onClick={onLoadMore} disabled={loadingMore}>
              {loadingMore ? <Loader2Icon className="animate-spin" aria-hidden /> : null}
              {t("older")}
            </Button>
            <p className="text-xs text-muted-foreground">{t("olderAudited")}</p>
          </div>
        ) : null}
        {error ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {errorMessage(error, t("failed"))}
          </p>
        ) : null}
        <ol className="flex flex-col gap-3">
          {messages.map((m) => (
            <ReviewedMessageRow key={m.id} message={m} time={time} />
          ))}
        </ol>
        <DialogFooter>
          <Button type="button" onClick={onClose} data-testid="review-close">
            {t("close")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ReviewedMessageRow({ message: m, time }: { message: ReviewedMessage; time: ReturnType<typeof useMessageTime> }) {
  const t = useTranslations("school.messaging.review.reader");
  const { participant } = usePersonText();
  const segments = m.body ? messageSegments(m.body) : [];
  return (
    <li className="rounded-2xl border p-3" data-testid="review-message" data-status={m.status}>
      <p className="flex flex-wrap items-baseline gap-x-2 text-sm">
        <span className="font-semibold">{participant(m.sender)}</span>
        {m.sentAt ? <time dateTime={m.sentAt} className="text-muted-foreground">{time.full(m.sentAt)}</time> : null}
        {m.status !== "SENT" ? <span className="rounded-full bg-secondary px-2 py-0.5 text-xs font-semibold">{t(`status.${statusKey(m.status)}`)}</span> : null}
        {m.reportCount > 0 ? <span className="rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-semibold text-destructive">{t("reports", { count: m.reportCount })}</span> : null}
      </p>
      {segments.length > 0 ? (
        <p dir="auto" className="mt-1 whitespace-pre-wrap [overflow-wrap:anywhere]">
          {segments.map((s, i) => (s.kind === "link" ? <span key={i} className="underline">{s.text}</span> : <span key={i}>{s.text}</span>))}
        </p>
      ) : null}
      {m.attachmentMediaIds.length > 0 ? (
        <ul className="mt-2 flex flex-wrap gap-2">
          {m.attachmentMediaIds.map((id) => (
            <li key={id}>
              <MediaImage mediaId={id} variant="MEDIUM" alt={t("photoAlt")} width={200} height={200} className="max-h-48 w-48 rounded-xl object-cover" />
            </li>
          ))}
        </ul>
      ) : null}
      {m.hideReason ? <p className="mt-1 text-sm text-muted-foreground">{t("hideReason", { reason: m.hideReason })}</p> : null}
      {m.history.length > 0 ? (
        <details className="mt-2 text-sm">
          <summary className="cursor-pointer font-medium">{t("history", { count: m.history.length })}</summary>
          <ul className="mt-1 flex flex-col gap-1 ps-4">
            {m.history.map((h, i) => (
              <li key={i}>
                <span className="text-muted-foreground">{time.full(h.at)} </span>
                <span dir="auto" className="whitespace-pre-wrap [overflow-wrap:anywhere]">
                  {h.body}
                </span>
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </li>
  );
}

function statusKey(status: string): "EDITED" | "DELETED" | "HIDDEN" | "SCHEDULED" | "OTHER" {
  return status === "EDITED" || status === "DELETED" || status === "HIDDEN" || status === "SCHEDULED" ? status : "OTHER";
}

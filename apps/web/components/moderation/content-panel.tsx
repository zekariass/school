"use client";

import { useQuery } from "@tanstack/react-query";
import { EyeIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { RelativeTime } from "@/components/feed/relative-time";
import { MediaImage } from "@/components/media/media-image";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { CONTENT_GC_MS, modk } from "@/lib/moderation/keys";
import { outcomeOf } from "@/lib/moderation/outcome";
import { fetchCaseContent } from "@/lib/moderation/queries";
import type { ReviewContent, ReviewItem } from "@/lib/moderation/types";
import { itemTypeKey, knownOr, roleKey } from "@/lib/moderation/view";
import { cn } from "@/lib/utils";
import { useModerationScope } from "./scope";

/**
 * The reported content, read through its owner's audited path (`GET /moderation/cases/{id}/content`). It is read only
 * when the person asks (every read is an audit row and counts against 60 an hour), kept only while this panel is on
 * screen (`gcTime` 0: leaving the page drops it) and never copied into a URL, storage, the page title or a toast. Text
 * is shown as text (no links, no markup). A message is not read here: the API answers a pointer to the messaging review.
 */
export function ContentPanel({ caseId, skip }: { caseId: string; skip?: boolean }) {
  const t = useTranslations("moderation.content");
  const [asked, setAsked] = useState(false);
  const query = useQuery({
    queryKey: modk.content(caseId),
    queryFn: () => fetchCaseContent(caseId),
    enabled: asked && !skip,
    gcTime: CONTENT_GC_MS,
    staleTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const outcome = query.isError ? outcomeOf(query.error) : null;

  return (
    <section aria-label={t("title")} className="flex flex-col gap-3 rounded-2xl border bg-card p-4" data-testid="content-panel">
      <h2 className="text-lg font-bold">{t("title")}</h2>
      {!asked ? (
        <>
          <p className="text-sm text-muted-foreground">{t("audited")}</p>
          <div>
            <Button type="button" onClick={() => setAsked(true)} data-testid="content-read">
              <EyeIcon aria-hidden />
              {t("read")}
            </Button>
          </div>
        </>
      ) : null}
      {asked && query.isPending ? (
        <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
          <Loader2Icon className="size-4 animate-spin" aria-hidden />
          {t("loading")}
        </p>
      ) : null}
      {outcome ? (
        <div role="alert" className="flex flex-col gap-2 rounded-xl bg-destructive/10 p-3 text-sm text-destructive" data-testid="content-error">
          <p className="font-medium">
            {outcome.kind === "nothing"
              ? t("gone")
              : outcome.kind === "rateLimited"
                ? (outcome.detail ?? t("rateLimited"))
                : (outcome.detail ?? t("failed"))}
          </p>
          {outcome.kind === "rateLimited" && outcome.retryAfter ? <p>{t("retryAfter", { seconds: outcome.retryAfter })}</p> : null}
          <div>
            <Button type="button" size="sm" variant="outline" onClick={() => void query.refetch()}>
              {t("retry")}
            </Button>
          </div>
        </div>
      ) : null}
      {query.data ? <ContentBody content={query.data} /> : null}
    </section>
  );
}

/** What the API sent: text items (with the reported one highlighted) or the pointer to the messaging review. */
export function ContentBody({ content }: { content: ReviewContent }) {
  const t = useTranslations("moderation.content");
  const scope = useModerationScope();
  if (content.kind === "MESSAGING_REVIEW") {
    return (
      <div className="flex flex-col gap-2" data-testid="content-message-pointer">
        <p className="text-sm">{t("messagePointer")}</p>
        {scope.messageReviewPath ? (
          <div>
            <Button asChild variant="outline" size="sm">
              <Link href={scope.messageReviewPath}>{t("openReview")}</Link>
            </Button>
          </div>
        ) : null}
      </div>
    );
  }
  const items = content.items ?? [];
  if (items.length === 0) return <p className="text-sm text-muted-foreground">{t("none")}</p>;
  return (
    <ul className="flex flex-col gap-2" data-testid="content-items">
      {items.map((item) => (
        <ItemCard key={`${item.type}-${item.id}`} item={item} />
      ))}
    </ul>
  );
}

const ITEM_STATUSES = ["PUBLISHED", "VISIBLE", "HIDDEN", "DELETED", "EXPIRED"] as const;

function ItemCard({ item }: { item: ReviewItem }) {
  const t = useTranslations("moderation.content");
  const tRole = useTranslations("moderation.roles");
  return (
    <li className={cn("flex flex-col gap-2 rounded-xl border p-3", item.highlighted ? "border-primary bg-primary/5" : "bg-background")} data-testid="content-item" data-highlighted={item.highlighted ? "true" : "false"}>
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <span className="font-semibold">{t(`type.${itemTypeKey(item.type)}`)}</span>
        <span className="text-muted-foreground">{tRole(roleKey(item.authorRole))}</span>
        {item.status ? <span className="rounded-full bg-secondary px-2 py-0.5 text-xs font-semibold">{t(`status.${knownOr(item.status, ITEM_STATUSES, "VISIBLE")}`)}</span> : null}
        {item.highlighted ? <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-semibold text-primary">{t("reported")}</span> : null}
        {item.createdAt ? <RelativeTime instant={item.createdAt} className="text-xs text-muted-foreground" /> : null}
      </p>
      {item.text ? (
        <p dir="auto" className="whitespace-pre-wrap [overflow-wrap:anywhere]" data-testid="content-text">
          {item.text}
        </p>
      ) : null}
      {(item.mediaIds ?? []).length > 0 ? (
        <ul className="flex flex-wrap gap-2" aria-label={t("photos")}>
          {(item.mediaIds ?? []).map((id) => (
            <li key={id}>
              <MediaImage mediaId={id} variant="SMALL" alt={t("photoAlt")} width={160} height={160} className="size-40 rounded-lg object-cover" />
            </li>
          ))}
        </ul>
      ) : null}
    </li>
  );
}

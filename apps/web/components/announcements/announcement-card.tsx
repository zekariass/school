"use client";

import { CheckCircle2Icon, ChevronRightIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useFormatInstant } from "@/components/calendar-preference";
import { RelativeTime } from "@/components/feed/relative-time";
import { Link } from "@/i18n/navigation";
import type { AnnouncementCard } from "@/lib/announcements/queries";
import { oneLine } from "@/lib/announcements/text";
import { cardIsNew, cardNeedsAcknowledgement, isUrgentType } from "@/lib/announcements/view";
import { cn } from "@/lib/utils";
import { TypeBadge } from "./type-badge";

/**
 * One announcement in a list: the title and a first line as text, who wrote it and when, and what is waiting for the
 * reader (new, please acknowledge). A withdrawn one shows its tombstone. `mode` says which list it is in: the inbox
 * (reader's own state), or authored lists (status instead of reading state).
 */
export function AnnouncementListItem({
  card,
  href,
  mode,
  showSchool = false,
}: {
  card: AnnouncementCard;
  href: string;
  mode: "reader" | "author";
  showSchool?: boolean;
}) {
  const t = useTranslations("school.announcements");
  const format = useFormatInstant();
  const withdrawn = card.status === "WITHDRAWN";
  const isNew = mode === "reader" && cardIsNew(card);
  const needsAck = mode === "reader" && cardNeedsAcknowledgement(card);
  const urgent = isUrgentType(card.type) && !withdrawn;
  const when = card.publishedAt ?? card.scheduledFor ?? card.createdAt;

  return (
    <li>
      <Link
        href={href}
        data-testid="announcement-item"
        data-announcement-id={card.id}
        data-new={isNew ? "true" : undefined}
        className={cn(
          "flex min-h-16 items-center gap-3 rounded-2xl border bg-card p-4 transition-colors hover:bg-accent",
          urgent && "border-destructive border-s-4",
          isNew && !urgent && "border-primary/50 bg-primary/5",
          withdrawn && "bg-muted/50 text-muted-foreground",
        )}
      >
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <div className="flex flex-wrap items-center gap-2">
            <TypeBadge type={card.type} />
            {isNew ? <span className="rounded-full bg-primary px-2.5 py-0.5 text-xs font-bold text-primary-foreground">{t("card.new")}</span> : null}
            {mode === "author" ? <span className="rounded-full border px-2.5 py-0.5 text-xs font-semibold">{t(`status.${card.status}`)}</span> : null}
            {withdrawn && mode === "reader" ? <span className="rounded-full border px-2.5 py-0.5 text-xs font-semibold">{t("status.WITHDRAWN")}</span> : null}
            {needsAck ? <span className="rounded-full bg-warning/25 px-2.5 py-0.5 text-xs font-bold text-foreground">{t("card.needsAck")}</span> : null}
            {mode === "reader" && card.requiresAcknowledgement && !needsAck && card.acknowledgedAt ? (
              <span className="inline-flex items-center gap-1 text-xs font-semibold text-muted-foreground">
                <CheckCircle2Icon className="size-3.5" aria-hidden />
                {t("card.acknowledged")}
              </span>
            ) : null}
            {mode === "author" && card.requiresAcknowledgement ? <span className="text-xs text-muted-foreground">{t("card.asksAck")}</span> : null}
          </div>
          <p className={cn("text-base leading-snug break-words [overflow-wrap:anywhere]", isNew ? "font-bold" : "font-semibold", withdrawn && "line-through")}>{card.title}</p>
          {card.excerpt && !withdrawn ? <p className="line-clamp-2 text-sm text-muted-foreground [overflow-wrap:anywhere]">{oneLine(card.excerpt, 200)}</p> : null}
          <p className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
            {showSchool ? <span className="font-medium">{card.schoolName}</span> : null}
            {card.authorName ? <span>{t("card.by", { name: card.authorName })}</span> : null}
            {card.status === "SCHEDULED" && card.scheduledFor ? (
              <span>{t("card.scheduledFor", { when: format(card.scheduledFor) })}</span>
            ) : card.status === "DRAFT" ? (
              <span>{t("card.saved", { when: format(card.createdAt) })}</span>
            ) : (
              <RelativeTime instant={when} />
            )}
            {mode === "author" && typeof card.recipientCount === "number" && card.status !== "DRAFT" ? <span>{t("card.recipients", { count: card.recipientCount })}</span> : null}
          </p>
        </div>
        <ChevronRightIcon className="size-5 shrink-0 text-muted-foreground rtl:rotate-180" aria-hidden />
      </Link>
    </li>
  );
}

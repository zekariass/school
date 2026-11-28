"use client";

import { useQuery } from "@tanstack/react-query";
import { CalendarIcon, MegaphoneIcon, PinIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { PlainText } from "@/components/announcements/announcement-text";
import { TypeBadge } from "@/components/announcements/type-badge";
import { useFormatInstant } from "@/components/calendar-preference";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { ank, fetchLatest, type AnnouncementCard } from "@/lib/announcements/queries";
import { cardIsNew, isUrgentType } from "@/lib/announcements/view";
import { pinnedAnnouncement } from "@/lib/feed/rail";
import { cn } from "@/lib/utils";

/**
 * The newest published announcement the API lists for the person, pinned above the feed with a link to it. Plain text
 * only (`PlainText`), never in a URL or storage. No announcement (or the module not reachable): no card.
 */
export function PinnedAnnouncement({ schoolId, hrefFor }: { schoolId: string | null; hrefFor: (card: AnnouncementCard) => string }) {
  const t = useTranslations("school.feed.redesign.announcement");
  const format = useFormatInstant();
  const latest = useQuery({ queryKey: ank.latest(schoolId), queryFn: () => fetchLatest(schoolId), staleTime: 60_000, retry: false });
  const card = pinnedAnnouncement(latest.data?.items);
  if (!card) return null;
  const urgent = isUrgentType(card.type);
  const isNew = cardIsNew(card);
  const when = card.publishedAt ?? card.createdAt;

  return (
    <section
      aria-label={t("label")}
      className={cn("flex flex-wrap items-center gap-4 rounded-2xl border p-4 shadow-sm sm:p-5", urgent ? "border-destructive bg-destructive/5" : "border-primary/20 bg-primary/5")}
      data-testid="pinned-announcement"
    >
      <span aria-hidden className="hidden size-14 shrink-0 place-items-center rounded-2xl bg-primary/10 text-primary sm:grid">
        <MegaphoneIcon className="size-7" />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <p className="flex flex-wrap items-center gap-2 text-sm font-semibold text-primary">
          <PinIcon className="size-4" aria-hidden />
          {t("label")}
          <TypeBadge type={card.type} />
          {isNew ? <span className="rounded-full bg-primary px-2.5 py-0.5 text-xs font-bold text-primary-foreground">{t("new")}</span> : null}
        </p>
        <PlainText text={card.title} className="text-lg leading-snug font-bold" />
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <CalendarIcon className="size-4 shrink-0" aria-hidden />
          {t("published", { when: format(when) })}
        </p>
      </div>
      <Button asChild variant="outline" className="min-h-11 w-full rounded-full bg-card sm:w-auto">
        <Link href={hrefFor(card)} aria-label={t("viewLabel")} data-testid="pinned-announcement-link">
          {t("view")}
        </Link>
      </Button>
    </section>
  );
}

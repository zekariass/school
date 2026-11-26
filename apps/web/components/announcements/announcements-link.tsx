"use client";

import { MegaphoneIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { acknowledgementCount, badgeCount, badgeText } from "@/lib/announcements/view";
import { useAnnouncementSummary } from "./use-announcements";

/**
 * The header's door to the announcement inbox, with the number of unread announcements. The badge is a number, not a
 * colour: it also says in words what waits (unread, waiting for an acknowledgement).
 */
export function AnnouncementsLink() {
  const t = useTranslations("family.announcements");
  const summary = useAnnouncementSummary();
  const unread = badgeCount(summary.data);
  const ack = acknowledgementCount(summary.data);
  const label = ack > 0 ? t("linkNeedsAck", { unread, ack }) : unread > 0 ? t("linkUnread", { unread }) : t("link");
  return (
    <Link
      href="/announcements"
      aria-label={label}
      title={t("link")}
      data-testid="announcements-link"
      className="relative inline-flex size-10 shrink-0 sm:size-11 items-center justify-center rounded-xl text-foreground transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
    >
      <MegaphoneIcon className="size-5" aria-hidden />
      {unread > 0 || ack > 0 ? (
        <span
          data-testid="announcements-badge"
          className="absolute end-1 top-1 grid min-w-5 place-items-center rounded-full bg-destructive px-1 text-[0.7rem] leading-5 font-bold text-white"
        >
          {unread > 0 ? badgeText(unread) : "!"}
        </span>
      ) : null}
    </Link>
  );
}

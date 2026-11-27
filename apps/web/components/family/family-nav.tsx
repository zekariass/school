"use client";

import { useTranslations } from "next-intl";
import { useAnnouncementSummary } from "@/components/announcements/use-announcements";
import { useUnreadCount } from "@/components/messaging/use-messaging";
import { Link, usePathname } from "@/i18n/navigation";
import { badgeCount, badgeText } from "@/lib/announcements/view";
import { unreadBadge } from "@/lib/messaging/inbox";
import { cn } from "@/lib/utils";

const TABS = [
  { href: "/family", key: "family" },
  { href: "/family/portfolio", key: "portfolio" },
  { href: "/announcements", key: "announcements" },
  { href: "/messages", key: "messages" },
  { href: "/calendar", key: "calendar" },
  { href: "/family/consent", key: "consent" },
] as const;

/** Six tabs: my children, the portfolio, announcements and messages (each with the number still unread), the calendar and consent. */
export function FamilyNav() {
  const t = useTranslations();
  const pathname = usePathname();
  const summary = useAnnouncementSummary();
  const unread = badgeCount(summary.data);
  const messages = useUnreadCount();
  const unreadMessages = messages.data?.unread ?? 0;
  const messagesBadge = unreadBadge(unreadMessages);
  return (
    <nav aria-label={t("nav.primary")}>
      <ul className="flex flex-wrap gap-2">
        {TABS.map((tab) => {
          const active = tab.href === "/family" ? pathname === tab.href : pathname.startsWith(tab.href);
          return (
            <li key={tab.href}>
              <Link
                href={tab.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "flex min-h-11 items-center gap-2 rounded-xl px-4 text-base font-semibold transition-colors",
                  active ? "bg-primary text-primary-foreground shadow-sm" : "text-muted-foreground hover:bg-accent hover:text-accent-foreground",
                )}
              >
                {tab.key === "family" ? t("nav.family") : tab.key === "portfolio" ? t("nav.portfolio") : tab.key === "announcements" ? t("nav.announcements") : tab.key === "messages" ? t("nav.messaging") : tab.key === "calendar" ? t("nav.calendar") : t("consent.title")}
                {tab.key === "announcements" && unread > 0 ? (
                  <span className="grid min-w-5 place-items-center rounded-full bg-destructive px-1.5 text-xs leading-5 font-bold text-white" data-testid="family-announcements-badge">
                    {badgeText(unread)}
                  </span>
                ) : null}
                {tab.key === "messages" && messagesBadge ? (
                  <span
                    className="grid min-w-5 place-items-center rounded-full bg-destructive px-1.5 text-xs leading-5 font-bold text-white"
                    data-testid="family-messages-badge"
                    aria-label={t("family.messaging.unreadTab", { count: unreadMessages })}
                  >
                    {messagesBadge}
                  </span>
                ) : null}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

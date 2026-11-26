"use client";

import { useTranslations } from "next-intl";
import { useAnnouncementSummary } from "@/components/announcements/use-announcements";
import { Link, usePathname } from "@/i18n/navigation";
import { badgeCount, badgeText } from "@/lib/announcements/view";
import { cn } from "@/lib/utils";

const TABS = [
  { href: "/family", key: "family" },
  { href: "/announcements", key: "announcements" },
  { href: "/family/consent", key: "consent" },
] as const;

/** Three tabs: my children, announcements (with the number still unread) and consent. */
export function FamilyNav() {
  const t = useTranslations();
  const pathname = usePathname();
  const summary = useAnnouncementSummary();
  const unread = badgeCount(summary.data);
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
                {tab.key === "family" ? t("nav.family") : tab.key === "announcements" ? t("nav.announcements") : t("consent.title")}
                {tab.key === "announcements" && unread > 0 ? (
                  <span className="grid min-w-5 place-items-center rounded-full bg-destructive px-1.5 text-xs leading-5 font-bold text-white" data-testid="family-announcements-badge">
                    {badgeText(unread)}
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

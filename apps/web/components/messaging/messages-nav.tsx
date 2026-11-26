"use client";

import { useTranslations } from "next-intl";
import { Link, usePathname } from "@/i18n/navigation";
import { cn } from "@/lib/utils";
import { useMessagesPlace } from "./messages-context";

/** Inbox, scheduled, groups and blocked: the sections of messaging. Groups are for people who can moderate or create them. */
export function MessagesNav({ showGroups, showScheduled = true }: { showGroups: boolean; showScheduled?: boolean }) {
  const t = useTranslations("school.messaging.nav");
  const pathname = usePathname();
  const { base } = useMessagesPlace();
  const tabs: { href: string; key: "inbox" | "scheduled" | "groups" | "blocked"; exact: boolean }[] = [
    { href: base, key: "inbox", exact: true },
    ...(showScheduled ? [{ href: `${base}/scheduled`, key: "scheduled" as const, exact: false }] : []),
    ...(showGroups ? [{ href: `${base}/groups`, key: "groups" as const, exact: false }] : []),
    { href: `${base}/blocked`, key: "blocked", exact: false },
  ];
  const inConversation = /\/messages\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(pathname);
  // the conversation view and "new" are part of the inbox
  const known = tabs.filter((tab) => !tab.exact).some((tab) => pathname.startsWith(tab.href));
  return (
    // on a phone the tabs would take the room of the conversation itself
    <nav aria-label={t("label")} className={inConversation ? "hidden md:block" : undefined}>
      <ul className="flex flex-wrap gap-2">
        {tabs.map((tab) => {
          const active = tab.exact ? !known && pathname.startsWith(tab.href) : pathname.startsWith(tab.href);
          return (
            <li key={tab.key}>
              <Link
                href={tab.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "flex min-h-10 items-center rounded-xl px-4 text-sm font-semibold transition-colors",
                  active ? "bg-secondary text-secondary-foreground" : "text-muted-foreground hover:bg-accent hover:text-accent-foreground",
                )}
              >
                {t(tab.key)}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

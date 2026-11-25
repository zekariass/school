"use client";

import { useTranslations } from "next-intl";
import { Link, usePathname } from "@/i18n/navigation";
import { cn } from "@/lib/utils";

const TABS = [
  { href: "/family", key: "family" },
  { href: "/family/consent", key: "consent" },
] as const;

/** Two tabs: my children and consent. */
export function FamilyNav() {
  const t = useTranslations();
  const pathname = usePathname();
  return (
    <nav aria-label={t("nav.primary")}>
      <ul className="flex gap-2">
        {TABS.map((tab) => {
          const active = tab.href === "/family" ? pathname === tab.href : pathname.startsWith(tab.href);
          return (
            <li key={tab.href}>
              <Link
                href={tab.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "flex min-h-11 items-center rounded-xl px-4 text-base font-semibold transition-colors",
                  active ? "bg-primary text-primary-foreground shadow-sm" : "text-muted-foreground hover:bg-accent hover:text-accent-foreground",
                )}
              >
                {tab.key === "family" ? t("nav.family") : t("consent.title")}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

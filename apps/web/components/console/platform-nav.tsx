"use client";

import {
  BuildingIcon,
  FlagIcon,
  LayoutDashboardIcon,
  ScrollTextIcon,
  SchoolIcon,
  SlidersHorizontalIcon,
  type LucideIcon,
} from "lucide-react";
import { useTranslations } from "next-intl";
import { Link, usePathname } from "@/i18n/navigation";
import { visibleSections, type SectionId } from "@/lib/console/permissions";
import { cn } from "@/lib/utils";
import { useConsole } from "./console-context";

const ICONS: Record<SectionId, LucideIcon> = {
  schools: SchoolIcon,
  organizations: BuildingIcon,
  featureFlags: FlagIcon,
  settings: SlidersHorizontalIcon,
  audit: ScrollTextIcon,
};

/** Left navigation of the platform console; sections depend on the user's permissions. */
export function PlatformNav({ orientation }: { orientation: "vertical" | "horizontal" }) {
  const t = useTranslations("console.nav");
  const pathname = usePathname();
  const { permissions } = useConsole();
  const items = [
    { id: "overview" as const, href: "/platform", icon: LayoutDashboardIcon },
    ...visibleSections(permissions).map((s) => ({ id: s.id, href: s.href, icon: ICONS[s.id] })),
  ];

  return (
    <nav aria-label={t("label")}>
      <ul className={cn("flex gap-1", orientation === "vertical" ? "flex-col" : "overflow-x-auto px-3 py-2")}>
        {items.map(({ id, href, icon: Icon }) => {
          const active = href === "/platform" ? pathname === href : pathname.startsWith(href);
          return (
            <li key={id} className="shrink-0">
              <Link
                href={href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "flex min-h-11 items-center gap-3 rounded-xl px-4 text-base font-semibold transition-colors",
                  active
                    ? "bg-primary text-primary-foreground shadow-sm"
                    : "text-muted-foreground hover:bg-accent hover:text-accent-foreground",
                )}
              >
                <Icon className="size-5" aria-hidden />
                {t(id)}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

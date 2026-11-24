"use client";

import {
  BookOpenIcon,
  CalendarDaysIcon,
  LayoutDashboardIcon,
  MessageCircleIcon,
  SettingsIcon,
  SmileIcon,
  UsersIcon,
  type LucideIcon,
} from "lucide-react";
import { useTranslations } from "next-intl";
import { Link, usePathname } from "@/i18n/navigation";
import { cn } from "@/lib/utils";

const ITEMS: ReadonlyArray<{
  slug: string;
  labelKey: "dashboard" | "classes" | "students" | "teachers" | "messages" | "calendar" | "settings";
  icon: LucideIcon;
}> = [
  { slug: "", labelKey: "dashboard", icon: LayoutDashboardIcon },
  { slug: "classes", labelKey: "classes", icon: BookOpenIcon },
  { slug: "students", labelKey: "students", icon: SmileIcon },
  { slug: "teachers", labelKey: "teachers", icon: UsersIcon },
  { slug: "messages", labelKey: "messages", icon: MessageCircleIcon },
  { slug: "calendar", labelKey: "calendar", icon: CalendarDaysIcon },
  { slug: "settings", labelKey: "settings", icon: SettingsIcon },
];

/** Vertical list in the desktop sidebar, horizontal scroller on small screens. */
export function SidebarNav({
  school,
  orientation,
}: {
  school: string;
  orientation: "vertical" | "horizontal";
}) {
  const t = useTranslations("nav");
  const pathname = usePathname();
  const base = `/s/${encodeURIComponent(school)}`;

  return (
    <nav aria-label={t("primary")}>
      <ul
        className={cn(
          "flex gap-1",
          orientation === "vertical" ? "flex-col" : "overflow-x-auto px-3 py-2",
        )}
      >
        {ITEMS.map(({ slug, labelKey, icon: Icon }) => {
          const href = slug ? `${base}/${slug}` : base;
          const active = slug ? pathname.startsWith(href) : pathname === base;
          return (
            <li key={labelKey} className="shrink-0">
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
                {t(labelKey)}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

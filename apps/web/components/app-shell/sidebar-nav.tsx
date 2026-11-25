"use client";

import {
  BookOpenIcon,
  CalendarRangeIcon,
  ClipboardCheckIcon,
  FileUpIcon,
  FileTextIcon,
  HeartHandshakeIcon,
  LayoutDashboardIcon,
  TrophyIcon,
  SettingsIcon,
  SlidersHorizontalIcon,
  SmileIcon,
  UsersIcon,
  type LucideIcon,
} from "lucide-react";
import { useTranslations } from "next-intl";
import { Link, usePathname } from "@/i18n/navigation";
import { NAV_PATH, type NavId } from "@/lib/school/access";
import { cn } from "@/lib/utils";

const ICONS: Record<NavId, LucideIcon> = {
  dashboard: LayoutDashboardIcon,
  classes: BookOpenIcon,
  myClasses: BookOpenIcon,
  students: SmileIcon,
  teachers: UsersIcon,
  import: FileUpIcon,
  academic: CalendarRangeIcon,
  settings: SettingsIcon,
  features: SlidersHorizontalIcon,
  families: HeartHandshakeIcon,
  attendance: ClipboardCheckIcon,
  points: TrophyIcon,
  policies: FileTextIcon,
};

/** Vertical list in the desktop sidebar, horizontal scroller on small screens. Only the items the caller may use. */
export function SidebarNav({
  school,
  items,
  orientation,
}: {
  school: string;
  items: readonly NavId[];
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
        {items.map((id) => {
          const Icon = ICONS[id];
          const slug = NAV_PATH[id];
          const href = slug ? `${base}/${slug}` : base;
          const active = slug ? pathname.startsWith(href) : pathname === base;
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

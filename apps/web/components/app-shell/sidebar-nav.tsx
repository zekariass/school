"use client";

import {
  BookOpenIcon,
  CalendarDaysIcon,
  CalendarRangeIcon,
  ClipboardCheckIcon,
  FileUpIcon,
  FileTextIcon,
  HeartHandshakeIcon,
  ImagesIcon,
  LayoutDashboardIcon,
  MegaphoneIcon,
  MessageCircleIcon,
  ShieldCheckIcon,
  TrophyIcon,
  SettingsIcon,
  SlidersHorizontalIcon,
  SmileIcon,
  UsersIcon,
  type LucideIcon,
} from "lucide-react";
import { useTranslations } from "next-intl";
import { useUnreadCount } from "@/components/messaging/use-messaging";
import { Link, usePathname } from "@/i18n/navigation";
import { unreadBadge } from "@/lib/messaging/inbox";
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
  feed: ImagesIcon,
  announcements: MegaphoneIcon,
  messages: MessageCircleIcon,
  messageReview: ShieldCheckIcon,
  calendar: CalendarDaysIcon,
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
  const tMessages = useTranslations("school.messaging.link");
  const pathname = usePathname();
  const base = `/s/${encodeURIComponent(school)}`;
  const hasMessages = items.includes("messages");
  const unreadMessages = useUnreadCount(hasMessages).data?.unread ?? 0;
  const messagesBadge = hasMessages ? unreadBadge(unreadMessages) : null;

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
          // "messages" is also the parent of "messages/review", which has its own item
          const active = slug ? pathname.startsWith(href) && !(id === "messages" && pathname.startsWith(`${href}/review`)) : pathname === base;
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
                {id === "messages" && messagesBadge ? (
                  <span
                    data-testid="nav-messages-badge"
                    aria-label={tMessages("unread", { count: unreadMessages })}
                    className="ms-auto grid min-w-5 place-items-center rounded-full bg-destructive px-1.5 text-xs leading-5 font-bold text-white"
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

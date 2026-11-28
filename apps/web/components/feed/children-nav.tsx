"use client";

import { CalendarDaysIcon, ChevronRightIcon, MegaphoneIcon, UsersIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useAnnouncementSummary } from "@/components/announcements/use-announcements";
import { StudentAvatar } from "@/components/school/student-avatar";
import { Link } from "@/i18n/navigation";
import { badgeCount, badgeText } from "@/lib/announcements/view";
import type { MyChild } from "@/lib/family/queries";
import { cn } from "@/lib/utils";

/** What a child's row says under the name: the first class the API listed for them (none when the link hides classes). */
function classLine(child: MyChild): string | null {
  for (const school of child.schools) {
    const first = school.classes[0];
    if (first) return first.name;
  }
  return null;
}

/**
 * "My children": the family's children with their picture, name and class; choosing one narrows the feed to that child
 * (`studentId` of `GET /me/feed`). On small screens it is a horizontal switcher. Below it, quick links to places that
 * already exist. The list is what the API sent for this guardian; nothing is added.
 */
export function ChildrenNav({
  kids,
  selected,
  onSelect,
}: {
  kids: readonly MyChild[];
  /** The studentId of the chosen child, or null for all. */
  selected: string | null;
  onSelect: (studentId: string | null) => void;
}) {
  const t = useTranslations("school.feed.redesign.children");
  const summary = useAnnouncementSummary();
  const unread = badgeCount(summary.data);
  const several = kids.length > 1;

  return (
    <div className="flex flex-col gap-4" data-testid="children-nav">
      <nav aria-labelledby="feed-children-title" className="flex flex-col gap-2">
        <h2 id="feed-children-title" className="px-1 text-lg font-bold">
          {t("title")}
        </h2>
        <ul className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-1 lg:flex-col lg:overflow-visible lg:pb-0">
          {several ? (
            <li className="shrink-0 lg:shrink">
              <ChildRow
                active={selected === null}
                onClick={() => onSelect(null)}
                label={t("showAll")}
                testId="filter-all"
                avatar={
                  <span aria-hidden className="grid size-10 shrink-0 place-items-center rounded-full bg-primary/10 text-primary">
                    <UsersIcon className="size-5" />
                  </span>
                }
                name={t("all")}
                sub={t("allHint")}
              />
            </li>
          ) : null}
          {kids.map((child) => {
            const line = classLine(child);
            return (
              <li key={child.studentId} className="shrink-0 lg:shrink">
                <ChildRow
                  active={several ? selected === child.studentId : true}
                  onClick={() => onSelect(several ? child.studentId : null)}
                  label={t("show", { name: child.displayName })}
                  testId={`filter-${child.studentId}`}
                  avatar={<StudentAvatar id={child.studentId} name={child.displayName} mediaId={child.avatar?.id} config={child.avatarConfig} size={40} />}
                  name={child.displayName}
                  sub={line ?? t("noClass")}
                />
              </li>
            );
          })}
        </ul>
      </nav>

      <nav aria-label={t("quickLinks")} className="hidden flex-col gap-1 border-t pt-3 lg:flex">
        <Link
          href="/announcements"
          className="flex min-h-11 items-center gap-3 rounded-xl px-3 text-base font-medium hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
          data-testid="quick-announcements"
        >
          <MegaphoneIcon className="size-5 shrink-0 text-muted-foreground" aria-hidden />
          <span className="flex-1">{t("announcements")}</span>
          {unread > 0 ? (
            <span className="grid min-w-5 place-items-center rounded-full bg-destructive px-1.5 text-xs leading-5 font-bold text-white" aria-label={t("unread", { count: unread })}>
              {badgeText(unread)}
            </span>
          ) : null}
        </Link>
        <Link
          href="/calendar"
          className="flex min-h-11 items-center gap-3 rounded-xl px-3 text-base font-medium hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
          data-testid="quick-calendar"
        >
          <CalendarDaysIcon className="size-5 shrink-0 text-muted-foreground" aria-hidden />
          <span className="flex-1">{t("calendar")}</span>
        </Link>
      </nav>
    </div>
  );
}

function ChildRow({
  active,
  onClick,
  label,
  testId,
  avatar,
  name,
  sub,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
  testId: string;
  avatar: React.ReactNode;
  name: string;
  sub: string;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      aria-label={label}
      onClick={onClick}
      data-testid={testId}
      className={cn(
        "flex min-h-14 w-full items-center gap-3 rounded-2xl border p-2 pe-3 text-start transition-colors focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none",
        active ? "border-primary/40 bg-primary/10" : "border-transparent hover:bg-accent",
      )}
    >
      {avatar}
      <span className="min-w-0 flex-1">
        <span className="block truncate text-base font-semibold">{name}</span>
        <span className="block truncate text-sm text-muted-foreground">{sub}</span>
      </span>
      <ChevronRightIcon className="hidden size-5 shrink-0 text-muted-foreground lg:block rtl:rotate-180" aria-hidden />
    </button>
  );
}

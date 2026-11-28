"use client";

import { useQuery } from "@tanstack/react-query";
import { CalendarDaysIcon, ChevronRightIcon, MailIcon, TrophyIcon, UsersIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useCalendarPreference } from "@/components/calendar-preference";
import { useToday } from "@/components/calendar/use-calendar";
import { useCapabilities as useMessagingCapabilities } from "@/components/messaging/use-messaging";
import { StudentAvatar } from "@/components/school/student-avatar";
import { Skeleton } from "@/components/ui/skeleton";
import { Link } from "@/i18n/navigation";
import { weekdayOf } from "@/lib/calendar/dates";
import { dayBadge, timeOfDay, weekdayName } from "@/lib/calendar/format";
import { daySpan, type CalendarItem } from "@/lib/calendar/items";
import { CALENDAR_GC_MS, ck, fetchCalendarWindow } from "@/lib/calendar/queries";
import { firstWords } from "@/lib/feed/post-style";
import type { Post } from "@/lib/feed/queries";
import { RECENT_ACHIEVEMENTS } from "@/lib/feed/tabs";
import { UPCOMING_SOURCES, upcomingItems, upcomingWindow } from "@/lib/feed/rail";
import { cn } from "@/lib/utils";
import { AuthorAvatar, AuthorName } from "./author";

/** A card of the right rail: the same rounded, softly bordered surface as the feed's cards. */
export function RailCard({ title, icon, action, children, testId }: { title: string; icon: React.ReactNode; action?: React.ReactNode; children: React.ReactNode; testId: string }) {
  const id = `${testId}-title`;
  return (
    <section aria-labelledby={id} className="flex flex-col gap-3 rounded-2xl border bg-card p-4 text-card-foreground shadow-sm" data-testid={testId}>
      <header className="flex items-center gap-3">
        <span aria-hidden className="grid size-10 shrink-0 place-items-center rounded-full bg-primary/10 text-primary">
          {icon}
        </span>
        <h2 id={id} className="min-w-0 flex-1 truncate text-base font-bold">
          {title}
        </h2>
        {action}
      </header>
      {children}
    </section>
  );
}

function RailSkeleton() {
  return <Skeleton className="h-40 rounded-2xl" aria-hidden />;
}

// --- class info ----------------------------------------------------------------------------------------

export interface RailTeacher {
  id: string;
  name: string;
  role: "LEAD" | "CO_TEACHER" | "SUBJECT_TEACHER" | "ASSISTANT";
  avatarId?: string | null;
}

/**
 * The class's name and teachers as the API gave them to this person, and the counts only when the API sent them (a
 * guardian's view has none). The message button is a link to the messaging "new conversation" page, shown only when the
 * school's messaging capabilities let this person start a conversation with a teacher.
 */
export function ClassInfoCard({
  name,
  schoolId,
  teachers,
  studentCount,
  teacherCount,
  allowMessage = false,
}: {
  name: string;
  schoolId: string;
  teachers: readonly RailTeacher[];
  studentCount?: number;
  teacherCount?: number;
  /** True for a guardian: the message link also needs the school's messaging capabilities. */
  allowMessage?: boolean;
}) {
  const t = useTranslations("school.feed.redesign.rail");
  const tr = useTranslations("school.teacherRole");
  const caps = useMessagingCapabilities(allowMessage ? schoolId : undefined);
  const canMessage = allowMessage && !!caps.data?.featureEnabled && !!caps.data.canStartParentTeacher;
  const showCounts = typeof studentCount === "number" || typeof teacherCount === "number";

  return (
    <RailCard title={t("classInfo")} icon={<UsersIcon className="size-5" />} testId="rail-class-info">
      <p className="-mt-2 truncate ps-[3.25rem] text-sm text-muted-foreground" data-testid="rail-class-name">
        {name}
      </p>
      {teachers.length > 0 ? (
        <ul aria-label={t("teachersList")} className="flex flex-col gap-2 border-t pt-3">
          {teachers.map((teacher, index) => (
            <li key={teacher.id} className="flex items-center gap-3" data-testid="rail-teacher">
              <StudentAvatar id={teacher.id} name={teacher.name} mediaId={teacher.avatarId} size={44} />
              <div className="min-w-0 flex-1">
                <p className="truncate font-semibold">{teacher.name}</p>
                <p className="truncate text-sm text-muted-foreground">{tr(teacher.role)}</p>
              </div>
              {canMessage && index === 0 ? (
                <Link
                  href="/messages/new"
                  aria-label={t("messageLabel", { name: teacher.name })}
                  title={t("message")}
                  className="grid size-11 shrink-0 place-items-center rounded-xl bg-primary/10 text-primary hover:bg-primary/20 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
                  data-testid="rail-message"
                >
                  <MailIcon className="size-5" aria-hidden />
                </Link>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {showCounts ? (
        <dl className="grid grid-cols-2 divide-x border-t pt-3 rtl:divide-x-reverse" data-testid="rail-counts">
          {typeof studentCount === "number" ? (
            <div className="flex flex-col-reverse px-2">
              <dt className="text-sm text-muted-foreground">{t("students")}</dt>
              <dd className="text-xl font-bold">{studentCount}</dd>
            </div>
          ) : (
            <span />
          )}
          {typeof teacherCount === "number" ? (
            <div className="flex flex-col-reverse px-3">
              <dt className="text-sm text-muted-foreground">{t("teachers")}</dt>
              <dd className="text-xl font-bold">{teacherCount}</dd>
            </div>
          ) : null}
        </dl>
      ) : null}
    </RailCard>
  );
}

// --- upcoming ------------------------------------------------------------------------------------------

/**
 * The next three events of the calendar (the API's `EVENT` source for this person's schools or the child), each in
 * its own time zone, with a link to the event and to the calendar. Event titles may name a child: they stay in the
 * memory-only calendar cache and are never put in a URL (the link carries the event id). Nothing to show: no card.
 */
export function UpcomingCard({
  schoolId,
  studentId,
  calendarHref,
}: {
  schoolId: string | null;
  studentId: string | null;
  calendarHref: string;
}) {
  const t = useTranslations("school.feed.redesign.rail");
  const locale = useLocale();
  const { calendar: system } = useCalendarPreference();
  const today = useToday();
  const win = today ? upcomingWindow(today) : null;
  const query = useQuery({
    queryKey: win ? ck.window(schoolId, studentId, UPCOMING_SOURCES, win.from, win.to) : ["calendar", "window", "pending"],
    queryFn: () => fetchCalendarWindow({ schoolId, studentId, sources: UPCOMING_SOURCES }, win!),
    enabled: !!win,
    staleTime: 60_000,
    gcTime: CALENDAR_GC_MS,
    retry: false,
  });

  if (!win || query.isPending) return <RailSkeleton />;
  if (query.isError) return null;
  const items = upcomingItems(query.data.items, new Date());
  if (items.length === 0) return null;

  return (
    <RailCard
      title={t("upcoming")}
      icon={<CalendarDaysIcon className="size-5" />}
      testId="rail-upcoming"
      action={
        <Link href={calendarHref} className="inline-flex min-h-11 items-center px-1 text-sm font-semibold text-primary hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none" data-testid="rail-upcoming-all">
          {t("viewAll")}
        </Link>
      }
    >
      <ul className="flex flex-col divide-y">
        {items.map((item) => (
          <li key={item.id} className="py-2 first:pt-0 last:pb-0">
            <UpcomingRow item={item} system={system} locale={locale} />
          </li>
        ))}
      </ul>
    </RailCard>
  );
}

function UpcomingRow({ item, system, locale }: { item: CalendarItem; system: "gregorian" | "ethiopian"; locale: string }) {
  const t = useTranslations("school.feed.redesign.rail");
  const iso = daySpan(item).first;
  const badge = dayBadge(iso, system, locale);
  const weekday = weekdayName(weekdayOf(iso), locale);
  const line = `${weekday} · ${item.allDay ? t("allDay") : timeOfDay(item.startAt, item.timeZone, locale)}`;
  const body = (
    <>
      <span aria-hidden className="flex size-12 shrink-0 flex-col items-center justify-center rounded-xl bg-primary/10 text-primary">
        <span className="text-[0.65rem] leading-none font-bold uppercase">{badge.month}</span>
        <span className="text-lg leading-tight font-bold">{badge.day}</span>
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate font-semibold">{item.title}</span>
        <span className="block truncate text-sm text-muted-foreground">{line}</span>
      </span>
    </>
  );
  const cls = "flex min-h-12 items-center gap-3 rounded-xl focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none";
  return item.eventId ? (
    <Link href={`/calendar/events/${item.eventId}`} className={cn(cls, "hover:bg-accent")} data-testid="rail-event">
      {body}
    </Link>
  ) : (
    <div className={cls} data-testid="rail-event">
      {body}
    </div>
  );
}

// --- recent achievements -------------------------------------------------------------------------------

/**
 * The latest three achievements of the same feed scope (the API's `type=ACHIEVEMENT`, newest first): the tagged
 * student's picture and name as the API sent them (the author when nobody is tagged) and the first words of the post,
 * linking to the post. None: no card.
 */
export function RecentAchievementsCard({
  queryKey,
  fetchRecent,
  onViewAll,
  viewAllLabel,
}: {
  queryKey: readonly unknown[];
  fetchRecent: () => Promise<Post[]>;
  /** Switches the screen's tab to Achievements. */
  onViewAll?: () => void;
  viewAllLabel?: string;
}) {
  const t = useTranslations("school.feed.redesign.rail");
  const query = useQuery({ queryKey, queryFn: fetchRecent, staleTime: 30_000, retry: false });
  if (query.isPending) return <RailSkeleton />;
  if (query.isError || query.data.length === 0) return null;
  const posts = query.data.slice(0, RECENT_ACHIEVEMENTS);

  return (
    <RailCard
      title={t("recent")}
      icon={<TrophyIcon className="size-5" />}
      testId="rail-recent"
      action={
        onViewAll ? (
          <button
            type="button"
            onClick={onViewAll}
            className="inline-flex min-h-11 items-center px-1 text-sm font-semibold text-primary hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
            data-testid="rail-recent-all"
          >
            {viewAllLabel ?? t("viewAll")}
          </button>
        ) : null
      }
    >
      <ul className="flex flex-col gap-2">
        {posts.map((post) => (
          <li key={post.id}>
            <RecentRow post={post} />
          </li>
        ))}
      </ul>
    </RailCard>
  );
}

function RecentRow({ post }: { post: Post }) {
  const t = useTranslations("school.feed.redesign.rail");
  const tagged = post.tags[0];
  const label = firstWords(post.body, 4) || t("plainAchievement");
  return (
    <Link
      href={`/feed/posts/${post.schoolId}/${post.id}`}
      className="flex min-h-14 items-center gap-3 rounded-xl border bg-background/50 p-2 pe-3 hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
      data-testid="rail-achievement"
    >
      {tagged ? <StudentAvatar id={tagged.studentId} name={tagged.displayName} mediaId={tagged.avatar?.id} size={40} /> : <AuthorAvatar author={post.author} size={40} />}
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2 font-semibold">
          {tagged ? <span className="truncate">{tagged.displayName}</span> : <AuthorName author={post.author} />}
          {post.tags.length > 1 ? <span className="shrink-0 text-xs font-medium text-muted-foreground">{t("more", { count: post.tags.length - 1 })}</span> : null}
        </span>
        <span className="block truncate text-sm text-muted-foreground">{label}</span>
      </span>
      <ChevronRightIcon className="size-5 shrink-0 text-muted-foreground rtl:rotate-180" aria-hidden />
    </Link>
  );
}

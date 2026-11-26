"use client";

import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarCheckIcon, CalendarPlusIcon, ChevronLeftIcon, ChevronRightIcon, FlagIcon, GraduationCapIcon, TriangleAlertIcon, UserIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useEffect, useMemo, useState } from "react";
import { useCalendarPreference } from "@/components/calendar-preference";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { SelectField } from "@/components/school/fields";
import { Button } from "@/components/ui/button";
import { errorMessage } from "@/lib/console/errors";
import { fetchMyChildren, familyKeys } from "@/lib/family/queries";
import { monthBounds, eachDay } from "@/lib/calendar/dates";
import { monthTitle, rangeLabel } from "@/lib/calendar/format";
import { CALENDAR_VIEWS, fetchWindow, monthGrid, shiftAnchor, visibleRange, weekGrid, type CalendarView } from "@/lib/calendar/grid";
import { FILTER_IDS, bucketItems, sourcesParam, type CalendarItem, type FilterId } from "@/lib/calendar/items";
import { CALENDAR_GC_MS, ck, fetchCalendarWindow, type WindowScope } from "@/lib/calendar/queries";
import { cn } from "@/lib/utils";
import { AgendaList } from "./agenda-list";
import { DayGrid } from "./day-grid";
import { EventDialog } from "./event-dialog";
import { EventFormDialog } from "./event-form";
import { InfoDialog } from "./info-dialog";
import { useIsNarrow, useToday, useViewerTimeZone } from "./use-calendar";

export interface SchoolRef {
  id: string;
  name: string;
  slug: string;
}

const FILTER_ICON = { events: CalendarCheckIcon, holidays: FlagIcon, academic: GraduationCapIcon, personal: UserIcon } as const;

/**
 * The calendar: month, week and agenda views over the API's union (events, holidays, academic dates, personal events), in
 * the viewer's choice of Gregorian or Ethiopian display. Data comes per visible window through TanStack Query (the adjacent
 * windows are prefetched) and lives in memory only. The union is already filtered for the caller by the API; the filters here
 * only narrow what is asked for (sources, a school, a child).
 */
export function CalendarView({
  schools,
  workSchools,
  fixedSchool,
  isGuardian,
  userTimeZone,
}: {
  /** The schools the viewer belongs to (the school filter shows when there are several). */
  schools: readonly SchoolRef[];
  /** The schools where the viewer works (teacher, staff or administrator): where school events may be created. */
  workSchools: readonly SchoolRef[];
  /** The workspace calendar of one school. */
  fixedSchool?: SchoolRef;
  isGuardian: boolean;
  /** The person's own time zone (profile), the default of their personal events. */
  userTimeZone?: string | null;
}) {
  const today = useToday();
  const viewerZone = useViewerTimeZone();
  if (!today || !viewerZone) return <LoadingRows rows={6} />;
  return <Loaded today={today} viewerZone={viewerZone} schools={schools} workSchools={workSchools} fixedSchool={fixedSchool} isGuardian={isGuardian} userTimeZone={userTimeZone ?? null} />;
}

function Loaded({
  today,
  viewerZone,
  schools,
  workSchools,
  fixedSchool,
  isGuardian,
  userTimeZone,
}: {
  today: string;
  viewerZone: string;
  schools: readonly SchoolRef[];
  workSchools: readonly SchoolRef[];
  fixedSchool?: SchoolRef;
  isGuardian: boolean;
  userTimeZone: string | null;
}) {
  const t = useTranslations("family.calendar");
  const locale = useLocale();
  const queryClient = useQueryClient();
  const { calendar: system, setCalendar } = useCalendarPreference();
  const narrow = useIsNarrow() ?? false;

  const [view, setView] = useState<CalendarView>("month");
  const [anchor, setAnchor] = useState(today);
  const [focusIso, setFocusIso] = useState(today);
  const [selectedDay, setSelectedDay] = useState<string | null>(null);
  const [filters, setFilters] = useState<ReadonlySet<FilterId>>(() => new Set(FILTER_IDS));
  const [schoolFilter, setSchoolFilter] = useState("");
  const [studentId, setStudentId] = useState("");
  const [opened, setOpened] = useState<CalendarItem | null>(null);
  const [creating, setCreating] = useState<{ day: string | null } | null>(null);

  const schoolId = fixedSchool?.id ?? (schoolFilter || null);
  const children = useQuery({ queryKey: familyKeys.children, queryFn: fetchMyChildren, enabled: isGuardian, staleTime: 60_000, gcTime: CALENDAR_GC_MS });
  const kids = children.data?.children ?? [];

  const range = useMemo(() => visibleRange(view, anchor, system), [view, anchor, system]);
  const sources = sourcesParam(filters);
  const scope: WindowScope = { schoolId, studentId: studentId || null, sources };

  const windowKey = (r: { from: string; to: string }) => {
    const w = fetchWindow(r);
    return { w, key: ck.window(scope.schoolId, scope.studentId, scope.sources, w.from, w.to) };
  };
  const { w: win, key } = windowKey(range);
  const query = useQuery({
    queryKey: key,
    queryFn: () => fetchCalendarWindow(scope, win),
    enabled: sources !== "",
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    gcTime: CALENDAR_GC_MS,
    retry: false,
  });

  // The windows before and after are fetched ahead, so paging feels instant.
  useEffect(() => {
    if (sources === "") return;
    for (const delta of [-1, 1] as const) {
      const next = visibleRange(view, shiftAnchor(view, anchor, system, delta), system);
      const w = fetchWindow(next);
      void queryClient.prefetchQuery({
        queryKey: ck.window(schoolId, studentId || null, sources, w.from, w.to),
        queryFn: () => fetchCalendarWindow({ schoolId, studentId: studentId || null, sources }, w),
        staleTime: 30_000,
        gcTime: CALENDAR_GC_MS,
      });
    }
  }, [queryClient, view, anchor, system, schoolId, studentId, sources]);

  const buckets = useMemo(() => bucketItems(query.data?.items ?? [], range), [query.data, range]);
  const bounds = monthBounds(anchor, system);
  const title = view === "week" ? rangeLabel(range.from, range.to, system, locale) : monthTitle(bounds.year, bounds.month, system, locale);

  function go(next: string) {
    setAnchor(next);
    setFocusIso(next);
    setSelectedDay(null);
  }
  function focusDay(iso: string) {
    setFocusIso(iso);
    if (iso < range.from || iso > range.to) setAnchor(iso);
  }
  function openItem(item: CalendarItem) {
    setOpened(item);
  }
  function toggleFilter(id: FilterId) {
    setFilters((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  const showSchoolFilter = !fixedSchool && schools.length > 1;
  const showChildFilter = isGuardian && kids.length > 0;
  const weeks = view === "month" ? monthGrid(anchor, system, today).weeks : view === "week" ? [weekGrid(anchor, system, today)] : [];
  const listDays = eachDay(range.from, range.to);
  const useGrid = !narrow && view !== "agenda";

  return (
    <div className="flex flex-col gap-4" data-testid="calendar-view" data-view={view} data-system={system}>
      <div role="toolbar" aria-label={t("toolbar")} className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" onClick={() => go(today)} data-testid="calendar-today">
          {t("today")}
        </Button>
        <div className="flex items-center gap-1">
          <Button type="button" variant="outline" size="icon" aria-label={t(view === "week" ? "previousWeek" : "previousMonth")} onClick={() => go(shiftAnchor(view, anchor, system, -1))} data-testid="calendar-prev">
            <ChevronLeftIcon className="size-5 rtl:rotate-180" aria-hidden />
          </Button>
          <Button type="button" variant="outline" size="icon" aria-label={t(view === "week" ? "nextWeek" : "nextMonth")} onClick={() => go(shiftAnchor(view, anchor, system, 1))} data-testid="calendar-next">
            <ChevronRightIcon className="size-5 rtl:rotate-180" aria-hidden />
          </Button>
        </div>
        <h2 aria-live="polite" className="min-w-0 flex-1 text-xl font-bold" data-testid="calendar-title">
          {title}
        </h2>
        <Segmented
          label={t("viewLabel")}
          value={view}
          onChange={(v) => {
            setView(v);
            setSelectedDay(null);
          }}
          options={CALENDAR_VIEWS.map((v) => ({ value: v, label: t(`views.${v}`) }))}
          testId="calendar-views"
        />
        <Segmented
          label={t("systemLabel")}
          value={system}
          onChange={(v) => setCalendar(v)}
          options={(["gregorian", "ethiopian"] as const).map((v) => ({ value: v, label: t(`systems.${v}`) }))}
          testId="calendar-system"
        />
        <Button type="button" onClick={() => setCreating({ day: selectedDay })} data-testid="calendar-new">
          <CalendarPlusIcon className="size-4" aria-hidden />
          {t("newEvent")}
        </Button>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div role="group" aria-label={t("showLabel")} className="flex flex-wrap gap-2">
          {FILTER_IDS.map((id) => {
            const Icon = FILTER_ICON[id];
            const on = filters.has(id);
            return (
              <button
                key={id}
                type="button"
                aria-pressed={on}
                onClick={() => toggleFilter(id)}
                data-testid={`calendar-filter-${id}`}
                className={cn(
                  "inline-flex min-h-11 items-center gap-2 rounded-full border px-4 text-sm font-semibold outline-none focus-visible:ring-[3px] focus-visible:ring-ring/60",
                  on ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent",
                )}
              >
                <Icon className="size-4" aria-hidden />
                {t(`filters.${id}`)}
              </button>
            );
          })}
        </div>
        {showSchoolFilter ? (
          <SelectField
            label={t("schoolFilter")}
            value={schoolFilter}
            onChange={setSchoolFilter}
            noneLabel={t("allSchools")}
            options={schools.map((s) => ({ value: s.id, label: s.name }))}
            className="w-52"
          />
        ) : null}
        {showChildFilter ? (
          <SelectField
            label={t("childFilter")}
            value={studentId}
            onChange={setStudentId}
            noneLabel={t("allChildren")}
            options={kids.map((k) => ({ value: k.studentId, label: k.displayName }))}
            className="w-52"
          />
        ) : null}
      </div>

      {query.isError ? <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} /> : null}
      {query.data?.truncated ? (
        <p role="status" className="flex items-start gap-2 rounded-xl bg-warning/15 p-3 text-sm font-medium" data-testid="calendar-truncated">
          <TriangleAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
          {t("truncated")}
        </p>
      ) : null}
      {sources === "" ? <p className="rounded-xl border border-dashed p-6 text-center text-muted-foreground">{t("noSources")}</p> : null}

      {sources !== "" && query.isPending ? <LoadingRows rows={6} /> : null}

      {sources !== "" && !query.isPending ? (
        <div className={cn(query.isPlaceholderData && "opacity-60 transition-opacity")} aria-busy={query.isFetching}>
          {useGrid ? (
            <>
              <DayGrid
                label={t("gridLabel", { title })}
                weeks={weeks}
                buckets={buckets}
                system={system}
                focusIso={focusIso}
                selectedIso={selectedDay}
                onFocusDay={focusDay}
                onSelectDay={(iso) => {
                  setFocusIso(iso);
                  setSelectedDay(iso);
                }}
                onOpenItem={openItem}
                viewerZone={viewerZone}
                limit={view === "week" ? Number.POSITIVE_INFINITY : 3}
              />
              {selectedDay ? (
                <section aria-labelledby={`agenda-${selectedDay}`} className="mt-4 rounded-2xl border p-4" data-testid="calendar-day-panel">
                  <AgendaList
                    days={[selectedDay]}
                    buckets={buckets}
                    system={system}
                    today={today}
                    viewerZone={viewerZone}
                    onOpenItem={openItem}
                    showEmpty
                    onAdd={(iso) => setCreating({ day: iso })}
                    headingLevel={3}
                  />
                </section>
              ) : null}
            </>
          ) : (
            <AgendaList
              days={listDays}
              buckets={buckets}
              system={system}
              today={today}
              viewerZone={viewerZone}
              onOpenItem={openItem}
              showEmpty={view === "week"}
              onAdd={(iso) => setCreating({ day: iso })}
              headingLevel={3}
            />
          )}
        </div>
      ) : null}

      <Legend />

      {opened && (opened.source === "EVENT" || opened.source === "PERSONAL_EVENT") ? (
        <EventDialog item={opened} viewerZone={viewerZone} onClose={() => setOpened(null)} userTimeZone={userTimeZone} />
      ) : null}
      {opened && opened.source !== "EVENT" && opened.source !== "PERSONAL_EVENT" ? <InfoDialog item={opened} system={system} onClose={() => setOpened(null)} /> : null}
      {creating ? (
        <EventFormDialog
          mode="create"
          schools={workSchools}
          fixedSchool={fixedSchool}
          defaultDay={creating.day}
          userTimeZone={userTimeZone}
          viewerZone={viewerZone}
          onClose={() => setCreating(null)}
        />
      ) : null}
    </div>
  );
}

/** One of several choices as a row of toggle buttons (a segmented control that is also fine with a keyboard and a screen reader). */
function Segmented<T extends string>({
  label,
  value,
  onChange,
  options,
  testId,
}: {
  label: string;
  value: T;
  onChange: (value: T) => void;
  options: readonly { value: T; label: string }[];
  testId: string;
}) {
  return (
    <div role="group" aria-label={label} className="inline-flex overflow-hidden rounded-xl border" data-testid={testId}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={value === option.value}
          onClick={() => onChange(option.value)}
          data-testid={`${testId}-${option.value}`}
          className={cn(
            "min-h-11 px-3 text-sm font-semibold outline-none focus-visible:ring-[3px] focus-visible:ring-inset focus-visible:ring-ring/60",
            value === option.value ? "bg-primary text-primary-foreground" : "hover:bg-accent",
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

/** What the looks mean: an icon and a word per kind (colour is only a hint). */
function Legend() {
  const t = useTranslations("family.calendar");
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground" aria-label={t("legend")} data-testid="calendar-legend">
      {(["EVENT", "PERSONAL_EVENT", "HOLIDAY", "ACADEMIC_PERIOD"] as const).map((source) => (
        <li key={source} className="flex items-center gap-1.5">
          <span className={cn("inline-block h-3 w-5 rounded-sm border-s-4", source === "EVENT" && "border-primary bg-primary/10", source === "PERSONAL_EVENT" && "border-secondary bg-secondary/25", source === "HOLIDAY" && "border-success bg-success/15", source === "ACADEMIC_PERIOD" && "border-info bg-info/15")} aria-hidden />
          {t(`sources.${source}`)}
        </li>
      ))}
      <li className="flex items-center gap-1.5">
        <span className="line-through">abc</span>
        {t("cancelled")}
      </li>
    </ul>
  );
}

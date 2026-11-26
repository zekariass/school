import { useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useNetworkState } from 'expo-network';
import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { TabHeader } from '@/components/tab-header';
import { Button, Screen, SegmentedControl, Text } from '@/components/ui';
import { Chips } from '@/components/feed/chips';
import { bucketItems, daysWithEntries } from '@/lib/calendar/bucket';
import { eachDay } from '@/lib/calendar/civil';
import { CALENDAR_PREFERENCES } from '@/lib/calendar/format-instant';
import {
  AGENDA_STEP_DAYS,
  DEFAULT_WEEK_START,
  monthGrid,
  monthOf,
  neighbouringRanges,
  rangeFor,
  stepAnchor,
  weekdayOrder,
  type CalendarView,
} from '@/lib/calendar/grid';
import { SOURCE_CHIPS } from '@/lib/calendar/item-style';
import { dateHeading, dateText } from '@/lib/calendar/labels';
import { DEFAULT_SOURCES, type CalendarFilter } from '@/lib/calendar/keys';
import { monthTitle } from '@/lib/calendar/names';
import { refreshCalendar, useCalendarRange, useCalendarSchools, usePrefetchRanges } from '@/lib/calendar/queries';
import { problemKind } from '@/lib/calendar/problems';
import type { CalendarSource } from '@/lib/calendar/types';
import { useLabelContext, useLooseT, useToday } from '@/lib/calendar/use-labels';
import { useMyChildren } from '@/lib/family/queries';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { AgendaList, DaySection, WeekList } from './day-sections';
import { MonthGrid } from './month-grid';
import { ToggleChips } from './toggle-chips';
import { useSwipe } from './use-swipe';

/**
 * The calendar of a guardian, teacher or student: a month grid with the selected day's list, a week, and an agenda; Gregorian or
 * Ethiopian; filters for sources, school and (guardians) child. Windows come from the API for the visible days only (the next
 * and the previous page are fetched ahead) and live in memory; with no connection the screen says so and shows nothing old.
 */
export function CalendarScreen({ standalone = false }: { standalone?: boolean }) {
  const t = useTranslations();
  const tx = useLooseT();
  const { colors, spacing } = useAppTheme();
  const { calendar, setCalendar, locale } = usePreferences();
  const { activeRole } = useSession();
  const context = useLabelContext();
  const today = useToday();
  const queryClient = useQueryClient();
  const network = useNetworkState();
  const prefetch = usePrefetchRanges();

  const [view, setView] = useState<CalendarView>('month');
  const [anchor, setAnchor] = useState(today);
  const [sources, setSources] = useState<readonly CalendarSource[]>(DEFAULT_SOURCES);
  const [schoolId, setSchoolId] = useState<string | null>(null);
  const [studentId, setStudentId] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const schools = useCalendarSchools();
  const children = useMyChildren(activeRole === 'guardian');
  const kids = activeRole === 'guardian' ? (children.data?.children ?? []) : [];

  const filter = useMemo<CalendarFilter>(() => ({ schoolId, studentId, sources }), [schoolId, studentId, sources]);
  const range = useMemo(() => rangeFor(view, anchor, calendar, DEFAULT_WEEK_START, AGENDA_STEP_DAYS), [view, anchor, calendar]);
  const query = useCalendarRange(filter, range);
  const items = useMemo(() => query.data?.items ?? [], [query.data]);
  const entries = useMemo(() => bucketItems(items, range), [items, range]);

  // The previous and next page are fetched ahead, so a swipe finds its data.
  useEffect(() => {
    if (query.isSuccess) prefetch(filter, neighbouringRanges(view, anchor, calendar, DEFAULT_WEEK_START, AGENDA_STEP_DAYS));
  }, [query.isSuccess, filter, view, anchor, calendar, prefetch]);

  const rows = useMemo(() => (view === 'month' ? monthGrid(monthOf(anchor, calendar), calendar, DEFAULT_WEEK_START) : []), [view, anchor, calendar]);
  const weekDays = useMemo(() => (view === 'week' ? eachDay(range.first, range.last) : []), [view, range]);
  const agendaDays = useMemo(() => (view === 'agenda' ? daysWithEntries(entries) : []), [view, entries]);

  const go = (delta: number) => setAnchor((current) => stepAnchor(view, current, delta, calendar, AGENDA_STEP_DAYS));
  // A swipe sideways turns the page (the arrows do the same for anybody who cannot swipe).
  const swipe = useSwipe((direction) => go(direction));

  async function refresh() {
    setRefreshing(true);
    try {
      await refreshCalendar(queryClient);
    } finally {
      setRefreshing(false);
    }
  }

  const title = view === 'month' ? monthTitle(calendar, monthOf(anchor, calendar), locale) : view === 'week' ? `${dateText(range.first, calendar, locale)} – ${dateText(range.last, calendar, locale)}` : t('mobile.calendar.agendaFrom', { date: dateText(anchor, calendar, locale) });
  const offline = network.isConnected === false || (query.isError && problemKind(query.error) === 'offline');
  const error = query.isError && !offline ? query.error : null;
  const selectedEntries = entries.get(anchor) ?? [];

  const newEventHref = { pathname: '/events/compose', params: { date: anchor, ...(schoolId ? { schoolId } : {}) } } as const;

  return (
    <Screen
      scroll
      edges={standalone ? ['bottom'] : ['top']}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} tintColor={colors.primary} />}
      contentStyle={{ gap: spacing[3] }}>
      {standalone ? null : <TabHeader />}
      <View {...swipe} style={{ gap: spacing[3] }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[2] }}>
          <ArrowButton direction="previous" label={view === 'month' ? t('mobile.calendar.previousMonth') : view === 'week' ? t('mobile.calendar.previousWeek') : t('mobile.calendar.previousPage')} onPress={() => go(-1)} />
          <Text variant="heading" style={{ flex: 1, textAlign: 'center' }} numberOfLines={2} accessibilityLiveRegion="polite" accessibilityLabel={title}>
            {title}
          </Text>
          <ArrowButton direction="next" label={view === 'month' ? t('mobile.calendar.nextMonth') : view === 'week' ? t('mobile.calendar.nextWeek') : t('mobile.calendar.nextPage')} onPress={() => go(1)} />
        </View>

        <View style={{ flexDirection: 'row', gap: spacing[2], alignItems: 'center' }}>
          <View style={{ flex: 1 }}>
            <SegmentedControl
              accessibilityLabel={t('mobile.calendar.view.label')}
              value={view}
              onChange={(next) => {
                setView(next);
                if (next === 'agenda') setAnchor(today);
              }}
              options={[
                { value: 'month', label: t('mobile.calendar.view.month') },
                { value: 'week', label: t('mobile.calendar.view.week') },
                { value: 'agenda', label: t('mobile.calendar.view.agenda') },
              ]}
            />
          </View>
          <Button variant="ghost" fullWidth={false} label={t('mobile.calendar.today')} accessibilityHint={dateHeading(today, calendar, locale)} onPress={() => setAnchor(today)} />
        </View>

        <SegmentedControl
          accessibilityLabel={t('calendar.label')}
          value={calendar}
          onChange={setCalendar}
          options={CALENDAR_PREFERENCES.map((value) => ({ value, label: t(`calendar.${value}`) }))}
        />

        <ToggleChips
          accessibilityLabel={t('mobile.calendar.source.label')}
          selected={sources}
          onChange={setSources}
          chips={SOURCE_CHIPS.map((chip) => ({ value: chip.source, label: tx(chip.labelKey), icon: chip.icon }))}
        />
        {kids.length > 1 ? (
          <Chips
            accessibilityLabel={t('mobile.calendar.child.label')}
            value={studentId ?? 'all'}
            onChange={(next) => setStudentId(next === 'all' ? null : next)}
            chips={[{ value: 'all', label: t('mobile.calendar.child.all') }, ...kids.map((kid) => ({ value: kid.studentId, label: kid.givenName || kid.displayName }))]}
          />
        ) : null}
        {schools.length > 1 ? (
          <Chips
            accessibilityLabel={t('mobile.calendar.school.label')}
            value={schoolId ?? 'all'}
            onChange={(next) => setSchoolId(next === 'all' ? null : next)}
            chips={[{ value: 'all', label: t('mobile.calendar.school.all') }, ...schools.map((school) => ({ value: school.id, label: school.name }))]}
          />
        ) : null}

        {offline ? (
          <View accessibilityRole="alert" style={{ flexDirection: 'row', gap: spacing[2], alignItems: 'center' }}>
            <SymbolView name={{ ios: 'wifi.slash', android: 'wifi_off', web: 'wifi_off' } as SymbolViewProps['name']} size={16} tintColor={colors.warning} />
            <Text variant="caption" style={{ flex: 1 }}>
              {t('mobile.calendar.offline')}
            </Text>
            <Button variant="ghost" fullWidth={false} label={t('common.retry')} onPress={() => void query.refetch()} />
          </View>
        ) : null}
        {query.data?.truncated || query.data?.partial ? (
          <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
            {t('mobile.calendar.truncated')}
          </Text>
        ) : null}
        {error ? (
          <ProblemBanner error={error} fallbackTitle={t('mobile.calendar.loadFailedTitle')} fallbackBody={t('mobile.calendar.loadFailedBody')} onRetry={() => void query.refetch()} />
        ) : null}
        {query.isFetching && !refreshing ? <ActivityIndicator color={colors.primary} accessibilityLabel={t('common.loading')} /> : null}

        {view === 'month' ? (
          <>
            <MonthGrid rows={rows} weekdays={weekdayOrder(DEFAULT_WEEK_START)} entries={entries} selected={anchor} today={today} context={context} onSelect={setAnchor} />
            <DaySection date={anchor} entries={selectedEntries} context={context} today={today} />
          </>
        ) : null}
        {view === 'week' ? <WeekList days={weekDays} entries={entries} context={context} today={today} /> : null}
        {view === 'agenda' ? (
          <>
            {agendaDays.length === 0 && query.isSuccess ? (
              <Text tone="muted" style={{ textAlign: 'center', paddingVertical: spacing[6] }}>
                {t('mobile.calendar.agendaEmpty')}
              </Text>
            ) : (
              <AgendaList days={agendaDays} entries={entries} context={context} today={today} />
            )}
            <Button variant="ghost" label={t('mobile.calendar.agendaLater')} onPress={() => go(1)} />
          </>
        ) : null}
      </View>

      <Button label={t('mobile.calendar.newEvent')} onPress={() => router.push(newEventHref)} />
    </Screen>
  );
}

function ArrowButton({ direction, label, onPress }: { direction: 'previous' | 'next'; label: string; onPress: () => void }) {
  const { colors, minTouchTarget, radii } = useAppTheme();
  const previous = direction === 'previous';
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      hitSlop={6}
      style={{ width: minTouchTarget, height: minTouchTarget, borderRadius: radii.full, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.muted }}>
      <SymbolView name={{ ios: previous ? 'chevron.left' : 'chevron.right', android: previous ? 'chevron_left' : 'chevron_right', web: previous ? 'chevron_left' : 'chevron_right' } as SymbolViewProps['name']} size={22} tintColor={colors.primary} />
    </Pressable>
  );
}

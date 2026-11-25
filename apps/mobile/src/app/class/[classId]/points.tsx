import { Stack, router, useLocalSearchParams } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, RefreshControl, ScrollView, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { Avatar } from '@/components/avatar';
import { AwardSheet } from '@/components/points/award-sheet';
import { PointsGrid } from '@/components/points/points-grid';
import { FailureBanners } from '@/components/points/queue-status';
import { QueueIndicator } from '@/components/sync/queue-indicator';
import { UndoSnackbar } from '@/components/points/undo-snackbar';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Text } from '@/components/ui';
import { useMe } from '@/lib/auth/use-me';
import { useMyClass, useRoster } from '@/lib/people/queries';
import type { RosterEntry } from '@/lib/people/roster';
import { setMuted, useMuted } from '@/lib/points/feedback';
import { useCategories, useClassPoints, type PointCategory } from '@/lib/points/queries';
import { useClassAwards, type AwardTarget } from '@/lib/points/use-class-awards';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { useReduceMotion } from '@/lib/use-reduce-motion';

/**
 * The signature screen: the class as a wall of avatars with point bubbles. Tap a student for the skill sheet,
 * long-press (or "Select") to pick several, "Whole class" to award everybody. Awards are queued first, so they
 * work with no connection and never count twice.
 */
export default function ClassPoints() {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const insets = useSafeAreaInsets();
  const { classId } = useLocalSearchParams<{ classId: string }>();
  const { cls } = useMyClass(classId);
  const schoolId = cls?.schoolId;
  const me = useMe();
  const roster = useRoster(schoolId, classId);
  const categories = useCategories(schoolId, classId);
  const overview = useClassPoints(schoolId, classId);
  const reduceMotion = useReduceMotion();
  const muted = useMuted();
  const [refreshing, setRefreshing] = useState(false);
  const [now, setNow] = useState(() => Date.now());

  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [sheetTarget, setSheetTarget] = useState<AwardTarget | null>(null);

  const students = useMemo(() => roster.data ?? [], [roster.data]);
  const skills = useMemo<PointCategory[]>(() => categories.data ?? [], [categories.data]);
  const { refetch: refetchTotals } = overview;
  const refetchOverview = useCallback(() => void refetchTotals(), [refetchTotals]);

  const awards = useClassAwards({
    schoolId,
    classId: classId!,
    ownerId: me.data?.id,
    roster: students,
    categories: skills,
    overview: overview.data,
    refetchOverview,
    reduceMotion,
  });

  // The undo countdown ticks while the snackbar is up.
  const lastOpId = awards.last?.opId;
  useEffect(() => {
    if (!lastOpId) return;
    const timer = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(timer);
  }, [lastOpId]);

  // The snackbar goes away by itself once nothing can be undone any more.
  const { last, dismissLast } = awards;
  const stillUndoable = awards.canUndo(now);
  useEffect(() => {
    if (!last || last.state !== 'confirmed' || stillUndoable) return;
    const timer = setTimeout(dismissLast, 2500);
    return () => clearTimeout(timer);
  }, [last, stillUndoable, dismissLast]);

  async function refresh() {
    setRefreshing(true);
    try {
      await Promise.all([overview.refetch(), roster.refetch(), categories.refetch()]);
    } finally {
      setRefreshing(false);
    }
  }

  function toggle(student: RosterEntry) {
    setSelected((current) => {
      const next = new Set(current);
      if (!next.delete(student.id)) next.add(student.id);
      return next;
    });
  }

  function onPressStudent(student: RosterEntry) {
    if (selecting) toggle(student);
    else setSheetTarget({ kind: 'students', ids: [student.id] });
  }

  function onLongPressStudent(student: RosterEntry) {
    if (!selecting) {
      setSelecting(true);
      setSelected(new Set([student.id]));
    }
  }

  function stopSelecting() {
    setSelecting(false);
    setSelected(new Set());
  }

  function onAward(category: PointCategory, pointsOverride: number | null) {
    const target = sheetTarget;
    setSheetTarget(null);
    if (!target) return;
    void awards.award(target, category, pointsOverride);
    if (target.kind === 'students' && target.ids.length > 1) stopSelecting();
  }

  const header = (
    <Stack.Screen
      options={{
        title: cls?.name ?? t('mobile.classes.classTitle'),
        headerRight: () => (
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            <HeaderIcon
              label={muted ? t('mobile.points.header.unmute') : t('mobile.points.header.mute')}
              symbol={muted ? { ios: 'speaker.slash.fill', android: 'volume_off' } : { ios: 'speaker.wave.2.fill', android: 'volume_up' }}
              onPress={() => setMuted(!muted)}
            />
            <HeaderIcon
              label={t('mobile.points.header.toolkit')}
              symbol={{ ios: 'timer', android: 'timer' }}
              onPress={() => router.push({ pathname: '/class/[classId]/toolkit', params: { classId: classId! } })}
            />
          </View>
        ),
      }}
    />
  );

  if (!cls) {
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5] }}>
        {header}
        {roster.isPending && !cls ? null : (
          <EmptyState
            icon={{ ios: 'questionmark.circle', android: 'help' }}
            title={t('mobile.classes.notFoundTitle')}
            body={t('mobile.classes.notFoundBody')}>
            <Button label={t('common.back')} onPress={() => router.back()} />
          </EmptyState>
        )}
      </View>
    );
  }

  const sheetStudent = sheetTarget?.kind === 'students' && sheetTarget.ids.length === 1 ? students.find((student) => student.id === sheetTarget.ids[0]) : undefined;
  const sheetTitle =
    sheetTarget?.kind === 'class'
      ? t('mobile.points.sheet.titleClass')
      : sheetStudent
        ? t('mobile.points.sheet.titleStudent', { name: sheetStudent.firstName })
        : t('mobile.points.sheet.titleMany', { count: sheetTarget?.kind === 'students' ? sheetTarget.ids.length : 0 });

  const archived = cls.status === 'ARCHIVED';
  const bottomPad = awards.last ? 120 : selecting ? 96 : spacing[6];

  return (
    <View style={{ flex: 1, backgroundColor: colors.background }}>
      {header}
      <ScrollView
        contentInsetAdjustmentBehavior="automatic"
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} tintColor={colors.primary} />}
        contentContainerStyle={{ paddingHorizontal: spacing[5], paddingTop: spacing[2], paddingBottom: bottomPad + insets.bottom, gap: spacing[4] }}>
        <View style={{ flexDirection: 'row', gap: spacing[3], alignItems: 'stretch' }}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('mobile.points.wholeClass.label', { total: awards.classTotal })}
            accessibilityState={{ disabled: archived || students.length === 0 }}
            disabled={archived || students.length === 0}
            onPress={() => setSheetTarget({ kind: 'class' })}
            style={({ pressed }) => ({
              flex: 1,
              minHeight: minTouchTarget + 12,
              flexDirection: 'row',
              alignItems: 'center',
              gap: spacing[3],
              paddingHorizontal: spacing[4],
              borderRadius: radii.xl,
              backgroundColor: pressed ? colors.ring : colors.primary,
              opacity: archived || students.length === 0 ? 0.5 : 1,
            })}>
            <SymbolView name={{ ios: 'person.3.fill', android: 'groups', web: 'groups' }} size={24} tintColor={colors.primaryForeground} />
            <View style={{ flex: 1 }}>
              <Text variant="label" style={{ color: colors.primaryForeground }}>
                {t('mobile.points.wholeClass.title')}
              </Text>
              <Text variant="caption" style={{ color: colors.primaryForeground, opacity: 0.9 }}>
                {t('mobile.points.wholeClass.total', { total: awards.classTotal })}
              </Text>
            </View>
          </Pressable>
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ selected: selecting }}
            accessibilityLabel={selecting ? t('mobile.points.select.done') : t('mobile.points.select.start')}
            onPress={() => (selecting ? stopSelecting() : setSelecting(true))}
            style={{
              minHeight: minTouchTarget + 12,
              paddingHorizontal: spacing[4],
              borderRadius: radii.xl,
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: selecting ? colors.secondary : colors.muted,
            }}>
            <SymbolView
              name={{ ios: 'checkmark.circle', android: 'check_circle', web: 'check_circle' }}
              size={22}
              tintColor={selecting ? colors.secondaryForeground : colors.foreground}
            />
            <Text variant="caption" style={{ color: selecting ? colors.secondaryForeground : colors.foreground }}>
              {selecting ? t('mobile.points.select.done') : t('mobile.points.select.start')}
            </Text>
          </Pressable>
        </View>

        <QueueIndicator classId={classId!} />
        <FailureBanners classId={classId!} students={students} categories={skills} />
        {awards.problem ? (
          <View accessibilityRole="alert" style={{ padding: spacing[3], borderRadius: radii.lg, borderWidth: 1, borderColor: colors.destructive, backgroundColor: colors.muted }}>
            <Text tone="destructive">{awards.problem}</Text>
          </View>
        ) : null}
        {categories.isError && !categories.data ? (
          <ProblemBanner
            error={categories.error}
            fallbackTitle={t('mobile.points.errors.skillsTitle')}
            fallbackBody={t('mobile.points.errors.skillsBody')}
            onRetry={() => void categories.refetch()}
          />
        ) : null}
        {archived ? <Text tone="muted">{t('mobile.classes.archivedNote')}</Text> : null}

        {roster.isPending ? (
          <ActivityIndicator color={colors.primary} />
        ) : roster.isError && !roster.data ? (
          <ProblemBanner
            error={roster.error}
            fallbackTitle={t('mobile.classes.rosterErrorTitle')}
            fallbackBody={t('mobile.classes.rosterErrorBody')}
            onRetry={() => void roster.refetch()}
          />
        ) : students.length === 0 ? (
          <EmptyState
            icon={{ ios: 'person.3', android: 'groups' }}
            title={t('mobile.classes.rosterEmptyTitle')}
            body={t('mobile.classes.rosterEmptyBody')}
          />
        ) : (
          <PointsGrid
            students={students}
            totals={awards.totals}
            totalsKnown={awards.totalsKnown}
            selecting={selecting}
            selectedIds={selected}
            bursts={awards.bursts}
            animate={!reduceMotion}
            onPress={archived ? () => {} : onPressStudent}
            onLongPress={archived ? () => {} : onLongPressStudent}
          />
        )}
        {overview.isError && !overview.data && !roster.isPending ? (
          <Text variant="caption" tone="muted">
            {t('mobile.points.errors.totalsOffline')}
          </Text>
        ) : null}
      </ScrollView>

      {selecting && !awards.last ? (
        <View
          style={{
            position: 'absolute',
            left: spacing[4],
            right: spacing[4],
            bottom: Math.max(insets.bottom, spacing[3]) + spacing[2],
            flexDirection: 'row',
            gap: spacing[3],
          }}>
          <View style={{ flex: 1 }}>
            <Button
              label={t('mobile.points.select.award', { count: selected.size })}
              disabled={selected.size === 0}
              onPress={() => setSheetTarget({ kind: 'students', ids: [...selected] })}
            />
          </View>
          <Button variant="ghost" fullWidth={false} label={t('common.cancel')} onPress={stopSelecting} />
        </View>
      ) : null}

      {awards.last ? (
        <UndoSnackbar
          message={awards.last.message}
          status={
            awards.last.undoing
              ? t('mobile.points.snackbar.undoing')
              : awards.last.state === 'waiting'
                ? t('mobile.points.snackbar.waiting')
                : stillUndoable
                  ? t('mobile.points.snackbar.sent', { seconds: secondsLeft(awards.last.undoableUntil, now) })
                  : t('mobile.points.snackbar.sentFinal')
          }
          undoBusy={awards.last.undoing}
          onUndo={stillUndoable ? () => void awards.undo() : null}
          onDismiss={awards.dismissLast}
        />
      ) : null}

      <AwardSheet
        visible={sheetTarget !== null}
        onClose={() => setSheetTarget(null)}
        title={sheetTitle}
        categories={skills}
        loading={categories.isPending}
        onAward={onAward}
        header={
          sheetStudent ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3] }}>
              <Avatar
                mediaId={sheetStudent.avatarMediaId}
                name={sheetStudent.name}
                seed={sheetStudent.id}
                config={sheetStudent.avatarConfig}
                size={56}
                maxInitials={1}
              />
              <Text tone="muted" numberOfLines={1} style={{ flex: 1 }}>
                {sheetStudent.name}
              </Text>
            </View>
          ) : null
        }
      />
    </View>
  );
}

function secondsLeft(undoableUntil: string | null, now: number): number {
  const until = undoableUntil ? Date.parse(undoableUntil) : NaN;
  return Number.isFinite(until) ? Math.max(0, Math.ceil((until - now) / 1000)) : 0;
}

function HeaderIcon({
  label,
  symbol,
  onPress,
}: {
  label: string;
  symbol: { ios: string; android: string };
  onPress: () => void;
}) {
  const { colors, minTouchTarget } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      hitSlop={4}
      onPress={onPress}
      style={{ width: minTouchTarget, height: minTouchTarget, alignItems: 'center', justifyContent: 'center' }}>
      <SymbolView name={{ ios: symbol.ios, android: symbol.android, web: symbol.android } as never} size={22} tintColor={colors.primary} />
    </Pressable>
  );
}

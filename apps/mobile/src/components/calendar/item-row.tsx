import { router } from 'expo-router';
import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { memo } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import type { DayEntry } from '@/lib/calendar/bucket';
import { CANCELLED_ICON, isCancelled, itemStyle, opensDetail } from '@/lib/calendar/item-style';
import { entryTimeText, type LabelContext } from '@/lib/calendar/labels';
import { splitItemId } from '@/lib/calendar/occurrence';
import { useLooseT } from '@/lib/calendar/use-labels';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

/** The route of an item's detail screen (events and personal events only), with the occurrence of a repeating one. */
export function detailHref(entry: DayEntry) {
  const ids = splitItemId(entry.item.id);
  const eventId = entry.item.eventId ?? ids?.eventId;
  if (!eventId) return null;
  const occurrenceId = entry.item.occurrenceId ?? ids?.occurrenceId ?? null;
  return {
    pathname: '/events/[eventId]',
    params: { eventId, ...(occurrenceId ? { occurrenceId } : {}), ...(entry.item.schoolId ? { schoolId: entry.item.schoolId } : {}) },
  } as const;
}

/**
 * One item of a day: an icon in a tinted circle AND the kind's name (never colour alone), the title (struck through and marked
 * "Cancelled" when it is), the time in the event's own zone and the place. Events open their detail; holidays and academic dates
 * are plain rows.
 */
export const ItemRow = memo(function ItemRow({ entry, context, compact = false }: { entry: DayEntry; context: LabelContext; compact?: boolean }) {
  const t = useTranslations();
  const tx = useLooseT();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const { item } = entry;
  const style = itemStyle(item);
  const cancelled = isCancelled(item);
  const tone = colors[style.tone];
  const kind = tx(style.labelKey);
  const time = entryTimeText(entry, context);
  const label = [item.title, kind, time, cancelled ? t('mobile.calendar.cancelled') : null, item.location].filter(Boolean).join('. ');
  const href = opensDetail(item) ? detailHref(entry) : null;

  const body = (
    <>
      <View
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
        style={{ width: compact ? 32 : 40, height: compact ? 32 : 40, borderRadius: radii.full, backgroundColor: colors.muted, alignItems: 'center', justifyContent: 'center' }}>
        <SymbolView name={{ ios: style.icon.ios, android: style.icon.android, web: style.icon.android } as SymbolViewProps['name']} size={compact ? 18 : 22} tintColor={tone} />
      </View>
      <View style={{ flex: 1, gap: 2 }}>
        <Text variant="label" numberOfLines={compact ? 1 : 2} style={cancelled ? { textDecorationLine: 'line-through', color: colors.mutedForeground } : undefined}>
          {item.title}
        </Text>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: 8 }}>
          <Text variant="caption">{kind}</Text>
          {cancelled ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
              <SymbolView name={{ ...CANCELLED_ICON, web: CANCELLED_ICON.android } as SymbolViewProps['name']} size={13} tintColor={colors.destructive} />
              <Text variant="caption" tone="destructive">
                {t('mobile.calendar.cancelled')}
              </Text>
            </View>
          ) : null}
          {item.modified && !cancelled ? <Text variant="caption">{t('mobile.calendar.changed')}</Text> : null}
        </View>
        <Text variant="caption">{time}</Text>
        {!compact && item.location ? (
          <Text variant="caption" numberOfLines={1}>
            {item.location}
          </Text>
        ) : null}
      </View>
      {href ? <SymbolView name={{ ios: 'chevron.right', android: 'chevron_right', web: 'chevron_right' } as SymbolViewProps['name']} size={16} tintColor={colors.mutedForeground} /> : null}
    </>
  );

  const container = {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing[3],
    minHeight: minTouchTarget + 8,
    paddingVertical: spacing[2],
    paddingHorizontal: spacing[3],
    borderRadius: radii.xl,
    backgroundColor: colors.card,
    borderWidth: 1,
    borderColor: colors.border,
    borderLeftWidth: 4,
    borderLeftColor: tone,
  } as const;

  if (!href) {
    return (
      <View accessible accessibilityLabel={label} style={container}>
        {body}
      </View>
    );
  }
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityHint={t('mobile.calendar.openEvent')} onPress={() => router.push(href)} style={container}>
      {body}
    </Pressable>
  );
});

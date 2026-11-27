import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { useFormatCivilDate } from '@/lib/calendar/use-format-instant';
import { ITEM_TYPE_LABEL } from '@/lib/portfolio/labels';
import { withheldNotice } from '@/lib/portfolio/model';
import type { PortfolioItem } from '@/lib/portfolio/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { FeedImage } from '../feed/feed-image';
import { Text } from '../ui';

import { StatusBadge } from './status-badge';

const TYPE_ICON = {
  TEXT: { ios: 'text.alignleft', android: 'notes' },
  PHOTO: { ios: 'photo', android: 'image' },
  DRAWING: { ios: 'paintbrush.pointed', android: 'brush' },
  SCAN: { ios: 'doc.viewfinder', android: 'document_scanner' },
  DOCUMENT: { ios: 'doc.richtext', android: 'description' },
  AUDIO: { ios: 'waveform', android: 'graphic_eq' },
  VIDEO: { ios: 'video', android: 'videocam' },
} as const;

/**
 * One item in a list: a small picture (or an icon for text and documents), the title, what kind of work it is, its date and its
 * status. Everything shown is what the API sent; a withheld picture is explained, never left as an unexplained gap.
 */
export function ItemCard({ item, showStudent = false, onPress }: { item: PortfolioItem; showStudent?: boolean; onPress: (item: PortfolioItem) => void }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const formatDate = useFormatCivilDate();
  const first = item.media.find((file) => file.kind === 'IMAGE');
  const notice = withheldNotice(item);
  const icon = TYPE_ICON[item.itemType];
  const date = item.workDate ? formatDate(item.workDate) : '';
  const title = item.title?.trim() || t('mobile.portfolio.untitled');
  const studentName = showStudent ? item.student.displayName?.trim() : '';
  const label = [title, studentName, t(ITEM_TYPE_LABEL[item.itemType]), date].filter(Boolean).join(', ');

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={() => onPress(item)}
      style={({ pressed }) => ({
        flexDirection: 'row',
        gap: spacing[3],
        padding: spacing[3],
        borderRadius: radii.xl,
        borderWidth: 1,
        borderColor: colors.border,
        backgroundColor: pressed ? colors.muted : colors.card,
      })}>
      <View style={{ width: 84, height: 84, borderRadius: radii.lg, overflow: 'hidden', backgroundColor: colors.muted, alignItems: 'center', justifyContent: 'center' }}>
        {first ? (
          <FeedImage mediaId={first.mediaId} available={first.variants} variant="THUMB" label={title} style={{ width: 84, height: 84 }} />
        ) : (
          <SymbolView name={{ ios: icon.ios, android: icon.android, web: icon.android } as never} size={32} tintColor={colors.mutedForeground} />
        )}
      </View>
      <View style={{ flex: 1, gap: 4 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
          <Text variant="heading" numberOfLines={2} style={{ flex: 1 }}>
            {title}
          </Text>
          {item.featured ? <SymbolView name={{ ios: 'star.fill', android: 'star', web: 'star' }} size={18} tintColor={colors.warning} accessibilityLabel={t('mobile.portfolio.featured')} /> : null}
        </View>
        {studentName ? <Text numberOfLines={1}>{studentName}</Text> : null}
        <Text variant="caption" numberOfLines={1}>
          {[t(ITEM_TYPE_LABEL[item.itemType]), date].filter(Boolean).join(' · ')}
        </Text>
        <StatusBadge status={item.status} />
        {notice ? (
          <Text variant="caption" tone="destructive">
            {notice === 'hidden' ? t('mobile.portfolio.withheld.short') : t('mobile.portfolio.withheld.warningShort')}
          </Text>
        ) : null}
      </View>
    </Pressable>
  );
}

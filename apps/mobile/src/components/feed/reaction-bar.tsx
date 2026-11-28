import type { ReactNode } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { nextReaction } from '@/lib/feed/reactions';
import { REACTIONS, type ReactionType } from '@/lib/feed/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

import { Tint } from './tint';

const GLYPH: Record<ReactionType, string> = { HEART: '❤️', LIKE: '👍', CLAP: '👏', STAR: '⭐' };

/**
 * The four reactions as pill chips with their counts, one per person; yours is outlined and tinted. The tap answers at once
 * (the counts change before the server replies) and a second tap on your own takes it back. Each pill names itself, its count
 * and whether it is yours for screen readers. `children` (the comment pill) sit in the same row.
 */
export function ReactionBar({
  counts,
  mine,
  disabled,
  onReact,
  children,
}: {
  counts: Record<string, number>;
  mine: ReactionType | null | undefined;
  disabled?: boolean;
  onReact: (next: ReactionType | null) => void;
  children?: ReactNode;
}) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();

  return (
    <View style={{ flexDirection: 'row', gap: spacing[2], flexWrap: 'wrap', alignItems: 'center' }}>
      {REACTIONS.map((reaction) => {
        const selected = mine === reaction;
        const count = counts[reaction] ?? 0;
        const name = t(`mobile.feed.reaction.${reaction}`);
        return (
          <Pressable
            key={reaction}
            accessibilityRole="button"
            accessibilityLabel={count > 0 ? t('mobile.feed.reactionWithCount', { name, count }) : name}
            accessibilityState={{ selected, disabled: !!disabled }}
            disabled={disabled}
            onPress={() => onReact(nextReaction(mine, reaction))}
            hitSlop={4}
            style={({ pressed }) => ({
              minHeight: minTouchTarget - 4,
              minWidth: minTouchTarget + spacing[2],
              paddingHorizontal: spacing[3],
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 6,
              borderRadius: radii.full,
              overflow: 'hidden',
              borderWidth: selected ? 2 : 1,
              borderColor: selected ? colors.primary : colors.border,
              backgroundColor: pressed ? colors.muted : colors.card,
              opacity: disabled ? 0.5 : 1,
            })}>
            {selected ? <Tint color={colors.primary} opacity={0.14} /> : null}
            <Text accessibilityElementsHidden importantForAccessibility="no" style={{ fontSize: 18, lineHeight: 24 }}>
              {GLYPH[reaction]}
            </Text>
            {count > 0 ? (
              <Text variant="label" style={{ color: colors.foreground }}>
                {count}
              </Text>
            ) : null}
          </Pressable>
        );
      })}
      {children}
    </View>
  );
}

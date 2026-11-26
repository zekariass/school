import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { nextReaction } from '@/lib/feed/reactions';
import { REACTIONS, type ReactionType } from '@/lib/feed/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

const GLYPH: Record<ReactionType, string> = { HEART: '❤️', LIKE: '👍', CLAP: '👏', STAR: '⭐' };

/**
 * The four reactions, one per person. The tap answers at once (the counts change before the server replies) and a second tap
 * on your own takes it back. Each button names itself, its count and whether it is yours for screen readers.
 */
export function ReactionBar({
  counts,
  mine,
  disabled,
  onReact,
}: {
  counts: Record<string, number>;
  mine: ReactionType | null | undefined;
  disabled?: boolean;
  onReact: (next: ReactionType | null) => void;
}) {
  const t = useTranslations();
  const { colors, radii, minTouchTarget } = useAppTheme();

  return (
    <View style={{ flexDirection: 'row', gap: 6, flexWrap: 'wrap' }}>
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
              minWidth: minTouchTarget,
              paddingHorizontal: 10,
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 4,
              borderRadius: radii.full,
              borderWidth: 1,
              borderColor: selected ? colors.primary : colors.border,
              backgroundColor: selected ? colors.accent : pressed ? colors.muted : 'transparent',
              opacity: disabled ? 0.5 : 1,
            })}>
            <Text accessibilityElementsHidden importantForAccessibility="no" style={{ fontSize: 18, lineHeight: 24 }}>
              {GLYPH[reaction]}
            </Text>
            {count > 0 ? (
              <Text variant="label" style={{ color: selected ? colors.accentForeground : colors.foreground }}>
                {count}
              </Text>
            ) : null}
          </Pressable>
        );
      })}
    </View>
  );
}

import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Sheet, Text, TextField } from '../ui';

export interface Choice {
  id: string;
  label: string;
}

/** Lowercase, no accents: "Ábebe" finds "abebe". */
function fold(text: string): string {
  return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/**
 * Picks one of many (a class, a child, a time zone): the current choice on the screen and a sheet with a searchable list. Names
 * of children are held in memory only.
 */
export function ChoiceField({
  label,
  choices,
  value,
  onChange,
  emptyText,
  loading = false,
  error,
  disabled = false,
}: {
  label: string;
  choices: readonly Choice[];
  value: string | null;
  onChange: (id: string) => void;
  /** Shown when there is nothing to choose from. */
  emptyText: string;
  loading?: boolean;
  error?: string | undefined;
  disabled?: boolean;
}) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const current = choices.find((choice) => choice.id === value);
  const shown = useMemo(() => choices.filter((choice) => fold(choice.label).includes(fold(query.trim()))), [choices, query]);
  return (
    <View style={{ gap: spacing[1] }}>
      <Text variant="label">{label}</Text>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${label}: ${current?.label ?? t('mobile.calendar.form.choose')}`}
        accessibilityState={{ disabled }}
        disabled={disabled}
        onPress={() => setOpen(true)}
        style={{
          minHeight: minTouchTarget + 4,
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          paddingHorizontal: spacing[4],
          borderRadius: radii.lg,
          borderWidth: error ? 2 : 1,
          borderColor: error ? colors.destructive : colors.input,
          backgroundColor: colors.card,
          opacity: disabled ? 0.5 : 1,
        }}>
        <Text style={{ flex: 1 }} tone={current ? 'default' : 'muted'}>
          {current?.label ?? t('mobile.calendar.form.choose')}
        </Text>
        <SymbolView name={{ ios: 'chevron.up.chevron.down', android: 'unfold_more', web: 'unfold_more' } as SymbolViewProps['name']} size={16} tintColor={colors.mutedForeground} />
      </Pressable>
      {error ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {error}
        </Text>
      ) : null}
      <Sheet visible={open} onClose={() => setOpen(false)} title={label}>
        {choices.length > 8 ? <TextField label={t('mobile.calendar.form.search')} value={query} onChangeText={setQuery} autoCapitalize="none" autoCorrect={false} /> : null}
        {loading ? <ActivityIndicator color={colors.primary} /> : null}
        <View style={{ gap: 2 }}>
          {shown.map((choice) => {
            const on = choice.id === value;
            return (
              <Pressable
                key={choice.id}
                accessibilityRole="radio"
                accessibilityState={{ selected: on }}
                accessibilityLabel={choice.label}
                onPress={() => {
                  onChange(choice.id);
                  setOpen(false);
                  setQuery('');
                }}
                style={({ pressed }) => ({ minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: spacing[3], paddingHorizontal: spacing[2], backgroundColor: pressed ? colors.muted : 'transparent', borderRadius: 12 })}>
                <SymbolView
                  name={(on ? { ios: 'checkmark.circle.fill', android: 'check_circle', web: 'check_circle' } : { ios: 'circle', android: 'radio_button_unchecked', web: 'radio_button_unchecked' }) as SymbolViewProps['name']}
                  size={24}
                  tintColor={on ? colors.primary : colors.mutedForeground}
                />
                <Text style={{ flex: 1 }}>{choice.label}</Text>
              </Pressable>
            );
          })}
          {shown.length === 0 && !loading ? <Text tone="muted">{choices.length === 0 ? emptyText : t('mobile.calendar.form.noMatch')}</Text> : null}
        </View>
        <Button variant="ghost" label={t('common.cancel')} onPress={() => setOpen(false)} />
      </Sheet>
    </View>
  );
}

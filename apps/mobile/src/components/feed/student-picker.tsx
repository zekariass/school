import { SymbolView } from 'expo-symbols';
import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Sheet, Text, TextField } from '../ui';

export interface StudentOption {
  id: string;
  name: string;
}

/** Lowercase, no accents: "Ábebe" finds "abebe". */
function fold(text: string): string {
  return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/**
 * Tagging students in a post: a summary line with the chosen names (kept in memory only) and a sheet with a searchable list of
 * the class (or, for a contribution, the caller's own children). The API checks every tag; this only helps choose.
 */
export function StudentTagger({
  options,
  selected,
  onToggle,
  loading,
  onSearch,
  title,
  hint,
}: {
  options: readonly StudentOption[];
  selected: readonly string[];
  onToggle: (studentId: string) => void;
  loading?: boolean;
  /** For lists the app only fetches by search (a whole school): called with the text typed. */
  onSearch?: (query: string) => void;
  title: string;
  hint?: string;
}) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const names = useMemo(() => new Map(options.map((option) => [option.id, option.name])), [options]);
  const shown = useMemo(() => (onSearch ? options : options.filter((option) => fold(option.name).includes(fold(query.trim())))), [onSearch, options, query]);
  const chosen = selected.map((id) => names.get(id)).filter((name): name is string => !!name);
  const hidden = selected.length - chosen.length;

  return (
    <View style={{ gap: spacing[2] }}>
      <Text variant="label">{title}</Text>
      {hint ? <Text variant="caption">{hint}</Text> : null}
      <Text accessibilityLiveRegion="polite" tone={selected.length ? 'default' : 'muted'}>
        {selected.length === 0
          ? t('mobile.feed.tags.none')
          : [...chosen, ...(hidden > 0 ? [t('mobile.feed.tags.others', { count: hidden })] : [])].join(', ')}
      </Text>
      <Button variant="ghost" fullWidth={false} label={selected.length ? t('mobile.feed.tags.change') : t('mobile.feed.tags.choose')} onPress={() => setOpen(true)} />
      <Sheet visible={open} onClose={() => setOpen(false)} title={title}>
        {options.length > 8 || onSearch ? (
          <TextField
            label={t('mobile.feed.tags.search')}
            value={query}
            onChangeText={(text) => {
              setQuery(text);
              onSearch?.(text);
            }}
            autoCapitalize="none"
            autoCorrect={false}
          />
        ) : null}
        {loading ? <ActivityIndicator color={colors.primary} /> : null}
        <View style={{ gap: 2 }}>
          {shown.map((option) => {
            const on = selected.includes(option.id);
            return (
              <Pressable
                key={option.id}
                accessibilityRole="checkbox"
                accessibilityState={{ checked: on }}
                accessibilityLabel={option.name}
                onPress={() => onToggle(option.id)}
                style={({ pressed }) => ({ minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: spacing[3], paddingHorizontal: spacing[2], backgroundColor: pressed ? colors.muted : 'transparent', borderRadius: 12 })}>
                <SymbolView
                  name={on ? { ios: 'checkmark.circle.fill', android: 'check_circle', web: 'check_circle' } : { ios: 'circle', android: 'radio_button_unchecked', web: 'radio_button_unchecked' }}
                  size={24}
                  tintColor={on ? colors.primary : colors.mutedForeground}
                />
                <Text style={{ flex: 1 }}>{option.name}</Text>
              </Pressable>
            );
          })}
          {shown.length === 0 && !loading ? <Text tone="muted">{t('mobile.feed.tags.noMatch')}</Text> : null}
        </View>
        <Button label={t('common.done')} onPress={() => setOpen(false)} />
      </Sheet>
    </View>
  );
}

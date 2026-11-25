import { SymbolView } from 'expo-symbols';
import { useState } from 'react';
import { ActivityIndicator, Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, EmptyState, Text } from '@/components/ui';
import { useRoutineTemplates, type ActivityTemplate } from '@/lib/points/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** Routines and checklists of the school as a tick list for the class. Ticks live on the screen only. */
export function RoutinesPanel({ schoolId, classId }: { schoolId: string | undefined; classId: string }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const templates = useRoutineTemplates(schoolId, classId);
  const [openId, setOpenId] = useState<string | null>(null);
  const [ticks, setTicks] = useState<Record<string, ReadonlySet<number>>>({});

  const list = templates.data ?? [];
  const open = list.find((template) => template.id === openId);

  if (templates.isPending) return <ActivityIndicator color={colors.primary} />;
  if (templates.isError && !templates.data) {
    return (
      <ProblemBanner
        error={templates.error}
        fallbackTitle={t('mobile.toolkit.routines.errorTitle')}
        fallbackBody={t('mobile.toolkit.routines.errorBody')}
        onRetry={() => void templates.refetch()}
      />
    );
  }
  if (list.length === 0) {
    return (
      <EmptyState
        icon={{ ios: 'checklist', android: 'checklist' }}
        title={t('mobile.toolkit.routines.emptyTitle')}
        body={t('mobile.toolkit.routines.emptyBody')}
      />
    );
  }

  if (open) {
    const done = ticks[open.id] ?? new Set<number>();
    const items = open.items;
    return (
      <View style={{ gap: spacing[4] }}>
        <Button variant="ghost" fullWidth={false} label={t('mobile.toolkit.routines.back')} onPress={() => setOpenId(null)} />
        <View style={{ gap: spacing[1] }}>
          <Text variant="title">{open.title}</Text>
          {open.instructions ? <Text tone="muted">{open.instructions}</Text> : null}
          <Text variant="label" accessibilityLiveRegion="polite">
            {t('mobile.toolkit.routines.progress', { done: done.size, total: items.length })}
          </Text>
        </View>
        <Card style={{ gap: 0, padding: spacing[2] }}>
          {items.map((item, index) => (
            <TickRow
              key={`${index}-${item.text ?? ''}`}
              text={item.text ?? ''}
              minutes={item.minutes}
              checked={done.has(index)}
              onToggle={() =>
                setTicks((current) => {
                  const next = new Set(current[open.id] ?? []);
                  if (!next.delete(index)) next.add(index);
                  return { ...current, [open.id]: next };
                })
              }
            />
          ))}
        </Card>
        <Button
          variant="ghost"
          label={t('mobile.toolkit.routines.clear')}
          disabled={done.size === 0}
          onPress={() => setTicks((current) => ({ ...current, [open.id]: new Set() }))}
        />
      </View>
    );
  }

  return (
    <View style={{ gap: spacing[3] }}>
      {list.map((template) => (
        <TemplateRow key={template.id} template={template} onPress={() => setOpenId(template.id)} />
      ))}
    </View>
  );
}

function TemplateRow({ template, onPress }: { template: ActivityTemplate; onPress: () => void }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${template.title}, ${t('mobile.toolkit.routines.itemCount', { count: template.items.length })}`}
      onPress={onPress}
      style={({ pressed }) => ({ opacity: pressed ? 0.85 : 1 })}>
      <Card style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3] }}>
        <View style={{ flex: 1, gap: 2 }}>
          <Text variant="heading" numberOfLines={2}>
            {template.title}
          </Text>
          <Text variant="caption">{t('mobile.toolkit.routines.itemCount', { count: template.items.length })}</Text>
        </View>
        <SymbolView name={{ ios: 'chevron.right', android: 'chevron_right', web: 'chevron_right' }} size={18} tintColor={colors.mutedForeground} />
      </Card>
    </Pressable>
  );
}

function TickRow({ text, minutes, checked, onToggle }: { text: string; minutes: number | undefined; checked: boolean; onToggle: () => void }) {
  const t = useTranslations();
  const { colors, spacing, minTouchTarget } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="checkbox"
      accessibilityState={{ checked }}
      accessibilityLabel={minutes ? `${text}, ${t('mobile.toolkit.timer.minutes', { minutes })}` : text}
      onPress={onToggle}
      style={{ minHeight: minTouchTarget + 8, flexDirection: 'row', alignItems: 'center', gap: spacing[3], padding: spacing[3] }}>
      <View
        style={{
          width: 30,
          height: 30,
          borderRadius: 15,
          borderWidth: 2,
          borderColor: checked ? colors.primary : colors.input,
          backgroundColor: checked ? colors.primary : 'transparent',
          alignItems: 'center',
          justifyContent: 'center',
        }}>
        {checked ? <SymbolView name={{ ios: 'checkmark', android: 'check', web: 'check' }} size={16} tintColor={colors.primaryForeground} /> : null}
      </View>
      <Text style={{ flex: 1, textDecorationLine: checked ? 'line-through' : 'none', opacity: checked ? 0.6 : 1 }}>{text}</Text>
      {minutes ? (
        <Text variant="caption" tone="muted">
          {t('mobile.toolkit.timer.minutes', { minutes })}
        </Text>
      ) : null}
    </Pressable>
  );
}

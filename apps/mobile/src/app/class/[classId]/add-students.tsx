import { useMutation, useQueryClient } from '@tanstack/react-query';
import { SymbolView } from 'expo-symbols';
import { router, useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Avatar } from '@/components/avatar';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Screen, Text, TextField } from '@/components/ui';
import { api } from '@/lib/api';
import { ApiRequestError, request, unwrap } from '@/lib/api-error';
import { invalidateRoster, useMyClass, useRoster, useStudentSearch } from '@/lib/people/queries';
import { addCandidates, selectAll, toggleSelection, withoutMembers } from '@/lib/people/selection';
import { useAppTheme } from '@/lib/theme/theme-provider';

const DEBOUNCE_MS = 300;

/** Pick students of the school who are not in this class yet and add them in one go. */
export default function AddStudents() {
  const t = useTranslations();
  const { spacing, colors, radii } = useAppTheme();
  const { classId } = useLocalSearchParams<{ classId: string }>();
  const { cls } = useMyClass(classId);
  const roster = useRoster(cls?.schoolId, classId);
  const queryClient = useQueryClient();

  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());

  useEffect(() => {
    const timer = setTimeout(() => setQ(search.trim()), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [search]);

  const found = useStudentSearch(cls?.schoolId, q);
  const rosterIds = useMemo(() => new Set((roster.data ?? []).map((student) => student.id)), [roster.data]);
  const candidates = useMemo(
    () => addCandidates((found.data?.pages ?? []).flatMap((page) => page.items), rosterIds),
    [found.data, rosterIds],
  );

  const ids = withoutMembers(selected, rosterIds);
  const add = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: () =>
      request(async () =>
        unwrap(
          await api.POST('/api/v1/schools/{schoolId}/classes/{classId}/students', {
            params: { path: { schoolId: cls!.schoolId, classId: classId! } },
            body: { studentIds: ids },
          }),
        ),
      ),
    onSuccess: async () => {
      await invalidateRoster(queryClient, classId!);
      router.back();
    },
  });

  const forbidden = add.error instanceof ApiRequestError && add.error.status === 403;

  return (
    <Screen edges={['bottom']} contentStyle={{ gap: spacing[3] }}>
      <TextField
        label={t('mobile.classes.add.searchLabel')}
        value={search}
        onChangeText={setSearch}
        autoCorrect={false}
        autoCapitalize="none"
        returnKeyType="search"
        clearButtonMode="while-editing"
      />

      {add.isError ? (
        <ProblemBanner
          error={add.error}
          fallbackTitle={t(forbidden ? 'mobile.classes.forbiddenTitle' : 'status.errorTitle')}
          fallbackBody={t(forbidden ? 'mobile.classes.forbiddenBody' : 'mobile.classes.actionFailed')}
        />
      ) : null}

      {found.isPending ? (
        <ActivityIndicator color={colors.primary} style={{ marginTop: spacing[6] }} />
      ) : found.isError ? (
        <ProblemBanner
          error={found.error}
          fallbackTitle={t('mobile.classes.add.errorTitle')}
          fallbackBody={t('mobile.classes.add.errorBody')}
          onRetry={() => void found.refetch()}
        />
      ) : (
        <FlatList
          style={{ flex: 1 }}
          data={candidates}
          keyExtractor={(student) => student.id}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          contentContainerStyle={{ gap: spacing[2], paddingBottom: spacing[4] }}
          onEndReached={() => {
            if (found.hasNextPage && !found.isFetchingNextPage) void found.fetchNextPage();
          }}
          onEndReachedThreshold={0.5}
          ListHeaderComponent={
            candidates.length > 0 ? (
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text tone="muted" variant="caption">
                  {t('mobile.classes.add.selected', { count: selected.size })}
                </Text>
                <Button
                  variant="ghost"
                  fullWidth={false}
                  label={t('mobile.classes.add.selectAll')}
                  onPress={() => setSelected((current) => selectAll(current, candidates.map((student) => student.id)))}
                />
              </View>
            ) : undefined
          }
          ListEmptyComponent={
            <View style={{ gap: spacing[3], paddingVertical: spacing[6] }}>
              <Text variant="heading" style={{ textAlign: 'center' }}>
                {t(q ? 'mobile.classes.add.noMatchTitle' : 'mobile.classes.add.emptyTitle')}
              </Text>
              <Text tone="muted" style={{ textAlign: 'center' }}>
                {t(q ? 'mobile.classes.add.noMatchBody' : 'mobile.classes.add.emptyBody')}
              </Text>
              <Button
                variant="secondary"
                label={t('mobile.classes.newStudent.action')}
                onPress={() => router.replace({ pathname: '/class/[classId]/new-student', params: { classId: classId! } })}
              />
            </View>
          }
          ListFooterComponent={found.isFetchingNextPage ? <ActivityIndicator color={colors.primary} /> : undefined}
          renderItem={({ item }) => {
            const checked = selected.has(item.id);
            return (
              <Pressable
                accessibilityRole="checkbox"
                accessibilityState={{ checked }}
                accessibilityLabel={item.displayName}
                onPress={() => setSelected((current) => toggleSelection(current, item.id))}
                style={({ pressed }) => ({
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: spacing[3],
                  padding: spacing[3],
                  borderRadius: radii.xl,
                  backgroundColor: checked ? colors.accent : colors.card,
                  borderWidth: 1,
                  borderColor: checked ? colors.primary : colors.border,
                  opacity: pressed ? 0.85 : 1,
                })}>
                <Avatar mediaId={item.avatar?.id} name={item.displayName} seed={item.id} config={item.avatarConfig} size={48} maxInitials={1} />
                <Text variant="label" style={{ flex: 1 }} numberOfLines={2}>
                  {item.displayName}
                </Text>
                <SymbolView
                  name={{
                    ios: checked ? 'checkmark.circle.fill' : 'circle',
                    android: checked ? 'check_circle' : 'circle',
                    web: checked ? 'check_circle' : 'circle',
                  }}
                  size={26}
                  tintColor={checked ? colors.primary : colors.mutedForeground}
                />
              </Pressable>
            );
          }}
        />
      )}

      <Button
        label={ids.length === 0 ? t('mobile.classes.add.submitNone') : t('mobile.classes.add.submit', { count: ids.length })}
        onPress={() => add.mutate()}
        loading={add.isPending}
        disabled={ids.length === 0}
      />
    </Screen>
  );
}

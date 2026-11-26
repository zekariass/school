import { useQueryClient } from '@tanstack/react-query';
import { randomUUID } from 'expo-crypto';
import { router } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { Banner } from '@/components/messaging/banner';
import { Chips } from '@/components/feed/chips';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Text, TextField } from '@/components/ui';
import { problemOf } from '@/lib/api-error';
import { GROUP_KIND_HINT, GROUP_KIND_LABEL } from '@/lib/messaging/labels';
import { INBOX_PREFIX, groupsKey } from '@/lib/messaging/keys';
import { useMessagingCapabilities } from '@/lib/messaging/queries';
import { createGroup } from '@/lib/messaging/requests';
import type { GroupKind } from '@/lib/messaging/types';
import { useMyClasses, useRoster } from '@/lib/people/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** The kinds a phone can create: the class-bound ones. Staff groups need a list of colleagues and are made on the web. */
const PHONE_KINDS = ['CLASS_STUDENTS', 'CLASS_PARENTS', 'STUDENT_GROUP'] as const satisfies readonly GroupKind[];
type PhoneKind = (typeof PHONE_KINDS)[number];
/** A conversation with students needs at least two of them: with one it would be a private adult-student conversation. */
const MIN_STUDENTS = 2;

/**
 * Creating a group (teachers). Which kinds are offered comes from the school's capabilities (`creatableGroups`); the API checks
 * the class, the roles and the rule that a conversation with students needs at least two of them and a teacher moderator.
 * A class conversation and a class parent group follow the class list by themselves.
 */
export default function NewGroup() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const client = useQueryClient();
  const classes = useMyClasses();
  const mine = useMemo(() => (classes.data ?? []).filter((cls) => cls.role === 'TEACHER' && cls.status === 'ACTIVE'), [classes.data]);
  const [classId, setClassId] = useState<string | null>(null);
  const cls = mine.find((item) => item.id === classId) ?? mine[0];
  const caps = useMessagingCapabilities(cls?.schoolId);
  const kinds = PHONE_KINDS.filter((kind) => caps.data?.creatableGroups.includes(kind));
  const [kindPick, setKindPick] = useState<PhoneKind | null>(null);
  const kind = kinds.find((item) => item === kindPick) ?? kinds[0];
  const roster = useRoster(cls?.schoolId, cls?.id);
  const [title, setTitle] = useState('');
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const key = useRef<string | null>(null);

  if (classes.isPending) return <View style={{ padding: spacing[8] }}><ActivityIndicator color={colors.primary} /></View>;
  if (classes.isError) return <View style={{ padding: spacing[5] }}><ProblemBanner error={classes.error} fallbackTitle={t('mobile.messaging.start.loadFailedTitle')} fallbackBody={t('mobile.messaging.start.loadFailedBody')} onRetry={() => void classes.refetch()} /></View>;
  if (!cls) return <View style={{ flex: 1, padding: spacing[5], justifyContent: 'center' }}><EmptyState icon={{ ios: 'person.3', android: 'groups' }} title={t('mobile.messaging.start.noClassTitle')} body={t('mobile.messaging.start.noClassBody')} /></View>;

  const studentGroup = kind === 'STUDENT_GROUP';
  const enough = !studentGroup || chosen.size >= MIN_STUDENTS;
  const ready = !!kind && enough && (!studentGroup || title.trim().length > 0);

  async function submit() {
    if (!kind || !cls || busy || !ready) return;
    setBusy(true);
    setProblem(null);
    if (!key.current) key.current = randomUUID();
    try {
      const group = await createGroup(cls.schoolId, { kind, classId: cls.id, title: title.trim() || undefined, memberUserIds: studentGroup ? [...chosen] : undefined }, key.current);
      void client.invalidateQueries({ queryKey: INBOX_PREFIX });
      void client.invalidateQueries({ queryKey: groupsKey(cls.schoolId, 'all') });
      if (group.status === 'ACTIVE') router.replace({ pathname: '/messages/[conversationId]', params: { conversationId: group.id } });
      else router.replace({ pathname: '/messages/groups', params: { schoolId: cls.schoolId } });
    } catch (error) {
      key.current = null;
      setProblem(problemOf(error)?.detail ?? t('mobile.messaging.errors.generic'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <ScrollView contentContainerStyle={{ padding: spacing[5], gap: spacing[4] }} keyboardShouldPersistTaps="handled">
        {mine.length > 1 ? <Chips accessibilityLabel={t('mobile.messaging.start.pickClass')} value={cls.id} onChange={(value) => { setClassId(value); setChosen(new Set()); }} chips={mine.map((item) => ({ value: item.id, label: item.name }))} /> : null}
        {caps.isPending ? <ActivityIndicator color={colors.primary} /> : null}
        {caps.isError ? <ProblemBanner error={caps.error} fallbackTitle={t('mobile.messaging.start.loadFailedTitle')} fallbackBody={t('mobile.messaging.start.loadFailedBody')} onRetry={() => void caps.refetch()} /> : null}
        {caps.data && kinds.length === 0 ? <Banner icon={{ ios: 'info.circle', android: 'info' }} body={t('mobile.messaging.newGroup.notAllowed')} /> : null}
        {kind ? (
          <>
            <Chips accessibilityLabel={t('mobile.messaging.newGroup.kind')} value={kind} onChange={(value) => setKindPick(value as PhoneKind)} chips={kinds.map((item) => ({ value: item, label: t(GROUP_KIND_LABEL[item]) }))} />
            <Text tone="muted">{t(GROUP_KIND_HINT[kind])}</Text>
            <TextField label={studentGroup ? t('mobile.messaging.newGroup.titleLabel') : t('mobile.messaging.newGroup.titleOptional')} value={title} onChangeText={setTitle} maxLength={120} />
            {studentGroup ? (
              <View style={{ gap: spacing[2] }}>
                <Text variant="label">{t('mobile.messaging.newGroup.pickStudents')}</Text>
                <Text variant="caption" tone={enough ? 'muted' : 'destructive'} accessibilityLiveRegion="polite">
                  {t('mobile.messaging.newGroup.studentsNeeded', { chosen: chosen.size, min: MIN_STUDENTS })}
                </Text>
                {roster.isPending ? <ActivityIndicator color={colors.primary} /> : null}
                {(roster.data ?? []).map((entry) => (
                  <StudentCheck
                    key={entry.id}
                    name={entry.name}
                    selected={chosen.has(entry.id)}
                    onToggle={() => setChosen((current) => { const next = new Set(current); if (!next.delete(entry.id)) next.add(entry.id); return next; })}
                  />
                ))}
                <Banner tone="safety" icon={{ ios: 'shield.fill', android: 'shield' }} body={t('mobile.messaging.newGroup.studentGroupNotice')} />
              </View>
            ) : null}
            {problem ? <Text tone="destructive" accessibilityLiveRegion="polite">{problem}</Text> : null}
            <Button label={t('mobile.messaging.newGroup.create')} loading={busy} disabled={!ready} onPress={() => void submit()} />
          </>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

function StudentCheck({ name, selected, onToggle }: { name: string; selected: boolean; onToggle: () => void }) {
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="checkbox"
      accessibilityState={{ checked: selected }}
      accessibilityLabel={name}
      onPress={onToggle}
      style={{ minHeight: minTouchTarget + 4, flexDirection: 'row', alignItems: 'center', gap: spacing[3], padding: spacing[3], borderRadius: radii.xl, borderWidth: 1, borderColor: selected ? colors.primary : colors.border, backgroundColor: selected ? colors.accent : colors.card }}>
      <SymbolView name={{ ios: selected ? 'checkmark.circle.fill' : 'circle', android: selected ? 'check_circle' : 'radio_button_unchecked', web: 'circle' } as never} size={24} tintColor={selected ? colors.primary : colors.mutedForeground} />
      <Text variant="label" style={{ flex: 1 }}>
        {name}
      </Text>
    </Pressable>
  );
}

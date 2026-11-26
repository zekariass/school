import { randomUUID } from 'expo-crypto';
import { router } from 'expo-router';
import { useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { Banner } from '@/components/messaging/banner';
import { useParticipantText } from '@/components/messaging/use-participant-text';
import { Chips } from '@/components/feed/chips';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Text } from '@/components/ui';
import { ApiRequestError, problemOf } from '@/lib/api-error';
import { useMyChildren } from '@/lib/family/queries';
import { useContacts, useMessagingCapabilities } from '@/lib/messaging/queries';
import { startDirect } from '@/lib/messaging/requests';
import type { Contact } from '@/lib/messaging/types';
import { useMyClasses, useRoster } from '@/lib/people/queries';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * Starting a conversation. There is no search and no directory: a guardian picks one of their children and one of that child's
 * teachers; a teacher picks a class, one of their students and then a guardian (a role label such as "Abebe's mother", never a
 * name) or, only when the school allows it, the student (with the safeguarding notice). Students cannot start conversations here:
 * they have no directory of people. The API decides who may be reached; a refusal is always the same plain sentence.
 */
export default function NewConversation() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { activeRole } = useSession();
  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      {activeRole === 'guardian' ? (
        <GuardianStart />
      ) : activeRole === 'teacher' ? (
        <TeacherStart />
      ) : (
        <View style={{ flex: 1, padding: spacing[5], justifyContent: 'center' }}>
          <EmptyState icon={{ ios: 'bubble.left.and.bubble.right', android: 'forum' }} title={t('mobile.messaging.start.studentTitle')} body={t('mobile.messaging.start.studentBody')}>
            <Button label={t('common.back')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
          </EmptyState>
        </View>
      )}
    </SafeAreaView>
  );
}

/** Opens the conversation with a person: the existing thread when there is one, else the API starts it (201) or returns it (200). */
function useStarter(schoolId: string | undefined) {
  const t = useTranslations();
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const key = useRef<{ id: string; for: string } | null>(null);
  async function start(userId: string, studentId: string | null, existing?: string | null) {
    if (existing) {
      router.replace({ pathname: '/messages/[conversationId]', params: { conversationId: existing } });
      return;
    }
    if (!schoolId || busy) return;
    const attempt = `${userId}:${studentId ?? ''}`;
    // The same pick keeps the same Idempotency-Key, so a retry after a lost answer finds the thread the first call made.
    if (key.current?.for !== attempt) key.current = { id: randomUUID(), for: attempt };
    setBusy(userId);
    setProblem(null);
    try {
      const started = await startDirect(schoolId, userId, studentId, key.current.id);
      router.replace({ pathname: '/messages/[conversationId]', params: { conversationId: started.conversation.summary.id } });
    } catch (error) {
      const code = problemOf(error)?.code;
      // Refusals are deliberately vague: the same words whatever the reason (a block, a setting, an unknown person).
      setProblem(error instanceof ApiRequestError && (error.status === 403 || error.status === 404) && code !== 'messaging.you_blocked' ? t('mobile.messaging.start.refused') : (problemOf(error)?.detail ?? t('mobile.messaging.errors.generic')));
    } finally {
      setBusy(null);
    }
  }
  return { start, busy, problem };
}

function PickRow({ title, subtitle, onPress, busy, disabled }: { title: string; subtitle?: string | undefined; onPress: () => void; busy?: boolean; disabled?: boolean }) {
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={subtitle ? `${title}. ${subtitle}` : title}
      accessibilityState={{ disabled: !!disabled, busy: !!busy }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => ({ minHeight: minTouchTarget + 12, flexDirection: 'row', alignItems: 'center', gap: spacing[3], padding: spacing[3], borderRadius: radii.xl, backgroundColor: pressed ? colors.muted : colors.card, borderWidth: 1, borderColor: colors.border, opacity: disabled ? 0.5 : 1 })}>
      <View style={{ flex: 1 }}>
        <Text variant="label">{title}</Text>
        {subtitle ? <Text variant="caption">{subtitle}</Text> : null}
      </View>
      {busy ? <ActivityIndicator color={colors.primary} /> : null}
    </Pressable>
  );
}

// --- a guardian: a child, then one of the child's teachers ---------------------------------------------------------------

function GuardianStart() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const children = useMyChildren();
  const kids = useMemo(() => children.data?.children ?? [], [children.data]);
  const [childId, setChildId] = useState<string | null>(null);
  const child = kids.find((kid) => kid.studentId === childId) ?? kids[0];
  const schools = child?.schools ?? [];
  const [schoolPick, setSchoolPick] = useState<string | null>(null);
  const school = schools.find((item) => item.schoolId === schoolPick) ?? schools[0];
  const caps = useMessagingCapabilities(school?.schoolId);
  const contacts = useContacts(school?.schoolId, child?.studentId, null, caps.data?.canStartParentTeacher === true);
  const starter = useStarter(school?.schoolId);

  if (children.isPending) return <Loading />;
  if (children.isError) return <View style={{ padding: spacing[5] }}><ProblemBanner error={children.error} fallbackTitle={t('mobile.messaging.start.loadFailedTitle')} fallbackBody={t('mobile.messaging.start.loadFailedBody')} onRetry={() => void children.refetch()} /></View>;
  if (!child || !school) {
    return <View style={{ flex: 1, padding: spacing[5], justifyContent: 'center' }}><EmptyState icon={{ ios: 'person.2', android: 'family_restroom' }} title={t('mobile.messaging.start.noChildTitle')} body={t('mobile.messaging.start.noChildBody')} /></View>;
  }

  return (
    <ScrollView contentContainerStyle={{ padding: spacing[5], gap: spacing[4] }}>
      <Text tone="muted">{t('mobile.messaging.start.guardianIntro')}</Text>
      {kids.length > 1 ? (
        <Chips accessibilityLabel={t('mobile.messaging.start.pickChild')} value={child.studentId} onChange={(value) => { setChildId(value); setSchoolPick(null); }} chips={kids.map((kid) => ({ value: kid.studentId, label: kid.givenName }))} />
      ) : null}
      {schools.length > 1 ? (
        <Chips accessibilityLabel={t('mobile.messaging.start.pickSchool')} value={school.schoolId} onChange={setSchoolPick} chips={schools.map((item) => ({ value: item.schoolId, label: item.schoolName }))} />
      ) : null}
      <Text variant="heading" accessibilityRole="header">
        {t('mobile.messaging.start.teachersOf', { child: child.givenName })}
      </Text>
      <ContactList query={caps} contacts={contacts} allowed={caps.data?.canStartParentTeacher === true} viewer="GUARDIAN" starter={starter} studentId={child.studentId} />
    </ScrollView>
  );
}

function Loading() {
  const { colors, spacing } = useAppTheme();
  return <View style={{ padding: spacing[8] }}><ActivityIndicator color={colors.primary} /></View>;
}

/** The people one may write to about a child, as the API lists them. */
function ContactList({
  query,
  contacts,
  allowed,
  viewer,
  starter,
  studentId,
}: {
  query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown };
  contacts: ReturnType<typeof useContacts>;
  allowed: boolean;
  viewer: 'GUARDIAN' | 'TEACHER';
  starter: ReturnType<typeof useStarter>;
  studentId: string;
}) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const personText = useParticipantText();
  if (query.isPending) return <Loading />;
  if (query.isError) return <ProblemBanner error={query.error} fallbackTitle={t('mobile.messaging.start.loadFailedTitle')} fallbackBody={t('mobile.messaging.start.loadFailedBody')} onRetry={() => void query.refetch()} />;
  if (!allowed) return <Banner icon={{ ios: 'info.circle', android: 'info' }} body={t('mobile.messaging.start.notAvailable')} />;
  if (contacts.isPending) return <Loading />;
  if (contacts.isError) return <ProblemBanner error={contacts.error} fallbackTitle={t('mobile.messaging.start.loadFailedTitle')} fallbackBody={t('mobile.messaging.start.loadFailedBody')} onRetry={() => void contacts.refetch()} />;
  const list: Contact[] = contacts.data ?? [];
  if (list.length === 0) return <Text tone="muted">{viewer === 'GUARDIAN' ? t('mobile.messaging.start.noTeachers') : t('mobile.messaging.start.noGuardians')}</Text>;
  return (
    <View style={{ gap: spacing[2] }}>
      {starter.problem ? <Text tone="destructive" accessibilityLiveRegion="polite">{starter.problem}</Text> : null}
      {list.map((contact) => {
        const label = contact.label;
        const title = label ? personText(label.actingAs === 'GUARDIAN' ? { self: false, actingAs: 'GUARDIAN', displayName: label.displayName, guardian: label.guardian } : { self: false, actingAs: label.actingAs, displayName: label.displayName }, viewer) : t('mobile.messaging.label.member');
        const subtitle = [label?.className, label?.subjectName].filter(Boolean).join(' · ');
        return (
          <PickRow
            key={contact.userId}
            title={title}
            subtitle={contact.conversationId ? [subtitle, t('mobile.messaging.start.existing')].filter(Boolean).join(' · ') : subtitle || undefined}
            busy={starter.busy === contact.userId}
            disabled={starter.busy !== null}
            onPress={() => void starter.start(contact.userId, studentId, contact.conversationId)}
          />
        );
      })}
    </View>
  );
}

// --- a teacher: a class, a student, then a guardian (or the student) ------------------------------------------------------

function TeacherStart() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const classes = useMyClasses();
  const mine = useMemo(() => (classes.data ?? []).filter((cls) => cls.role === 'TEACHER' && cls.status === 'ACTIVE'), [classes.data]);
  const [classId, setClassId] = useState<string | null>(null);
  const cls = mine.find((item) => item.id === classId) ?? mine[0];
  const roster = useRoster(cls?.schoolId, cls?.id);
  const [studentId, setStudentId] = useState<string | null>(null);
  const student = roster.data?.find((entry) => entry.id === studentId) ?? null;
  const caps = useMessagingCapabilities(cls?.schoolId);
  const contacts = useContacts(cls?.schoolId, student?.id, null, caps.data?.canStartParentTeacher === true && !!student);
  const starter = useStarter(cls?.schoolId);

  if (classes.isPending) return <Loading />;
  if (classes.isError) return <View style={{ padding: spacing[5] }}><ProblemBanner error={classes.error} fallbackTitle={t('mobile.messaging.start.loadFailedTitle')} fallbackBody={t('mobile.messaging.start.loadFailedBody')} onRetry={() => void classes.refetch()} /></View>;
  if (!cls) return <View style={{ flex: 1, padding: spacing[5], justifyContent: 'center' }}><EmptyState icon={{ ios: 'person.3', android: 'groups' }} title={t('mobile.messaging.start.noClassTitle')} body={t('mobile.messaging.start.noClassBody')} /></View>;

  return (
    <ScrollView contentContainerStyle={{ padding: spacing[5], gap: spacing[4] }}>
      <Text tone="muted">{t('mobile.messaging.start.teacherIntro')}</Text>
      {mine.length > 1 ? <Chips accessibilityLabel={t('mobile.messaging.start.pickClass')} value={cls.id} onChange={(value) => { setClassId(value); setStudentId(null); }} chips={mine.map((item) => ({ value: item.id, label: item.name }))} /> : null}
      <Text variant="heading" accessibilityRole="header">{student ? t('mobile.messaging.start.whoAbout', { child: student.firstName }) : t('mobile.messaging.start.pickStudent')}</Text>
      {roster.isPending ? <Loading /> : null}
      {roster.isError ? <ProblemBanner error={roster.error} fallbackTitle={t('mobile.messaging.start.loadFailedTitle')} fallbackBody={t('mobile.messaging.start.loadFailedBody')} onRetry={() => void roster.refetch()} /> : null}
      {!student ? (
        <View style={{ gap: spacing[2] }}>
          {(roster.data ?? []).map((entry) => (
            <PickRow key={entry.id} title={entry.name} onPress={() => setStudentId(entry.id)} />
          ))}
        </View>
      ) : (
        <>
          <Button variant="ghost" fullWidth={false} label={t('mobile.messaging.start.changeStudent')} onPress={() => setStudentId(null)} />
          <Text variant="label">{t('mobile.messaging.start.guardiansOf', { child: student.firstName })}</Text>
          <ContactList query={caps} contacts={contacts} allowed={caps.data?.canStartParentTeacher === true} viewer="TEACHER" starter={starter} studentId={student.id} />
          {caps.data?.canStartTeacherStudent ? (
            <View style={{ gap: spacing[2] }}>
              <Text variant="label">{t('mobile.messaging.start.studentThread')}</Text>
              <Banner tone="safety" icon={{ ios: 'shield.fill', android: 'shield' }} title={t('mobile.messaging.conversation.teacherStudentTitle')} body={t('mobile.messaging.conversation.teacherStudentBody')} />
              <PickRow title={t('mobile.messaging.start.messageStudent', { child: student.firstName })} busy={starter.busy === student.id} disabled={starter.busy !== null} onPress={() => void starter.start(student.id, null)} />
            </View>
          ) : null}
        </>
      )}
    </ScrollView>
  );
}


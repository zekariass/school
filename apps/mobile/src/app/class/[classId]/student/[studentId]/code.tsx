import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';
import { Stack, router, useLocalSearchParams } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, AppState, View, useWindowDimensions } from 'react-native';
import QRCode from 'react-native-qrcode-svg';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, Screen, Text } from '@/components/ui';
import { api } from '@/lib/api';
import { ApiRequestError, request, unwrap } from '@/lib/api-error';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { toCodeCard, type CodeCardData } from '@/lib/auth/login-code';
import { useMyClass, useRoster } from '@/lib/people/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

const KEEP_AWAKE_TAG = 'student-login-code';

type Phase =
  | { name: 'confirm' }
  | { name: 'loading' }
  | { name: 'shown'; card: CodeCardData }
  | { name: 'error'; error: unknown };

/**
 * A student's login code for the teacher to hand over. The plaintext code exists only in this
 * component's state: it is fetched after the teacher confirms, never put in a query, the cache,
 * storage or a log, and it is dropped (and the screen closed) when the app leaves the foreground
 * or the screen goes away. Issuing replaces the student's earlier code.
 */
export default function StudentLoginCode() {
  const t = useTranslations();
  const { spacing, colors, radii } = useAppTheme();
  const { width } = useWindowDimensions();
  const formatInstant = useFormatInstant();
  const { classId, studentId } = useLocalSearchParams<{ classId: string; studentId: string }>();
  const { cls } = useMyClass(classId);
  const roster = useRoster(cls?.schoolId, classId);
  const entry = roster.data?.find((student) => student.id === studentId);
  const name = entry?.firstName ?? '';
  const [phase, setPhase] = useState<Phase>({ name: 'confirm' });
  const issuing = useRef(false);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  // Never leave a code behind: drop it and close the screen as soon as the app is not in front.
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') return;
      setPhase({ name: 'confirm' });
      if (router.canGoBack()) router.back();
    });
    return () => subscription.remove();
  }, []);

  const shown = phase.name === 'shown';
  useEffect(() => {
    if (!shown) return;
    activateKeepAwakeAsync(KEEP_AWAKE_TAG).catch(() => undefined);
    return () => {
      deactivateKeepAwake(KEEP_AWAKE_TAG).catch(() => undefined);
    };
  }, [shown]);

  async function issue() {
    if (!cls || !studentId || issuing.current) return;
    issuing.current = true;
    setPhase({ name: 'loading' });
    try {
      const issued = await request(async () =>
        unwrap(
          await api.POST('/api/v1/schools/{schoolId}/classes/{classId}/login-codes', {
            params: { path: { schoolId: cls.schoolId, classId: cls.id } },
            body: { studentIds: [studentId], type: 'BOTH' },
          }),
        ),
      );
      const mine = issued.find((code) => code.studentId === studentId);
      if (!alive.current) return;
      setPhase(mine ? { name: 'shown', card: toCodeCard(mine) } : { name: 'error', error: new ApiRequestError(404) });
    } catch (error) {
      if (alive.current) setPhase({ name: 'error', error });
    } finally {
      issuing.current = false;
    }
  }

  const qrSize = Math.min(width - spacing[5] * 2 - 48, 250);
  const forbidden = phase.name === 'error' && phase.error instanceof ApiRequestError && phase.error.status === 403;

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[5] }}>
      <Stack.Screen options={{ title: t('mobile.classes.code.title'), gestureEnabled: phase.name !== 'loading' }} />

      {phase.name === 'confirm' || phase.name === 'loading' ? (
        <Card>
          <Text variant="heading">{t('mobile.classes.code.confirmTitle', { name })}</Text>
          <Text>{t('mobile.classes.code.confirmBody', { name })}</Text>
          <Button
            label={t('mobile.classes.code.confirmAction')}
            onPress={issue}
            loading={phase.name === 'loading'}
            disabled={!cls || !entry}
          />
          <Button variant="ghost" label={t('common.cancel')} onPress={() => router.back()} disabled={phase.name === 'loading'} />
        </Card>
      ) : null}

      {phase.name === 'error' ? (
        <View style={{ gap: spacing[3] }}>
          <ProblemBanner
            error={phase.error}
            fallbackTitle={t(forbidden ? 'mobile.classes.forbiddenTitle' : 'status.errorTitle')}
            fallbackBody={t(forbidden ? 'mobile.classes.forbiddenBody' : 'mobile.classes.code.failed')}
          />
          <Button label={t('mobile.classes.code.confirmAction')} onPress={issue} />
          <Button variant="ghost" label={t('common.back')} onPress={() => router.back()} />
        </View>
      ) : null}

      {phase.name === 'loading' ? <ActivityIndicator color={colors.primary} /> : null}

      {phase.name === 'shown' ? (
        <View style={{ alignItems: 'center', gap: spacing[5] }}>
          <View style={{ alignItems: 'center', gap: spacing[1] }}>
            <Text variant="title" style={{ textAlign: 'center' }}>
              {t('mobile.classes.code.forStudent', { name: phase.card.displayName })}
            </Text>
            <Text tone="muted" style={{ textAlign: 'center' }}>
              {t('mobile.classes.code.giveTo')}
            </Text>
          </View>

          {phase.card.qrToken ? (
            <View
              accessible
              accessibilityLabel={t('mobile.classes.code.qrLabel', { name: phase.card.displayName })}
              style={{ backgroundColor: '#FFFFFF', padding: 20, borderRadius: radii['2xl'] }}>
              {/* Always black on white, whatever the theme: scanners need the contrast. */}
              <QRCode value={phase.card.qrToken} size={qrSize} color="#000000" backgroundColor="#FFFFFF" ecl="M" quietZone={0} />
            </View>
          ) : null}

          {phase.card.textCode ? (
            <View style={{ alignItems: 'center', gap: spacing[2] }}>
              <Text tone="muted">{t('mobile.classes.code.orType')}</Text>
              <Text
                variant="title"
                accessibilityLabel={phase.card.textCode.replace('-', ' ').split('').join(' ')}
                allowFontScaling={false}
                style={{ fontSize: 40, lineHeight: 52, letterSpacing: 4, textAlign: 'center' }}>
                {phase.card.textCode}
              </Text>
            </View>
          ) : null}

          {phase.card.expiresAt ? (
            <Text variant="caption" style={{ textAlign: 'center' }}>
              {t('mobile.classes.code.validUntil', { date: formatInstant(phase.card.expiresAt) })}
            </Text>
          ) : null}
          <Text variant="caption" style={{ textAlign: 'center' }}>
            {t('mobile.classes.code.shownOnce')}
          </Text>
          <Button label={t('common.done')} onPress={() => router.back()} />
        </View>
      ) : null}
    </Screen>
  );
}

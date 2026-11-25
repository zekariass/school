import { useMutation } from '@tanstack/react-query';
import { CameraView, useCameraPermissions } from 'expo-camera';
import * as Haptics from 'expo-haptics';
import { router, useIsFocused } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { AppState, Linking, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Button, Card, Screen, Text, TextField } from '@/components/ui';
import { studentCodeMessageFor, studentCodeRetryAfter, type StudentCodeMessage } from '@/lib/auth/auth-problems';
import { signInWithLoginCode } from '@/lib/auth/auth-requests';
import { SCAN_COOLDOWN_MS, classifyScan, formatTextCode, isCompleteTextCode } from '@/lib/auth/login-code';
import { nowMs, secondsLeft } from '@/lib/auth/otp-timer';
import { useNow } from '@/lib/auth/use-now';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

interface Failure {
  message: StudentCodeMessage;
  /** Rate limit: the instant the child may try again. */
  until?: number;
}

type ScanState = 'looking' | 'found' | 'notACode';

/** Haptics are a nicety: never let a device without them break sign-in. */
function buzz(kind: 'success' | 'error') {
  const type = kind === 'success' ? Haptics.NotificationFeedbackType.Success : Haptics.NotificationFeedbackType.Error;
  Haptics.notificationAsync(type).catch(() => undefined);
}

/**
 * Students sign in with the QR code or text code their teacher gave them. The QR scan is the quick
 * way; typing the code is always there, which is also what simulators and phones without camera
 * access use. Expired class-code sessions come back here, not to the phone screen.
 */
export default function StudentCodeScreen() {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const { completeSignIn, endReason, dismissEndReason } = useSession();
  const focused = useIsFocused();
  const [permission, requestPermission] = useCameraPermissions();
  const [appActive, setAppActive] = useState(AppState.currentState === 'active');
  const [manualCode, setManualCode] = useState('');
  const [cameraError, setCameraError] = useState(false);
  const [scan, setScan] = useState<ScanState>('looking');
  const [failure, setFailure] = useState<Failure>();
  const busy = useRef(false);
  const now = useNow();

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => setAppActive(state === 'active'));
    return () => subscription.remove();
  }, []);

  const blockedFor = failure?.until ? secondsLeft(failure.until, now) : 0;

  const login = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: async (code: string) => {
      await completeSignIn(await signInWithLoginCode(code), 'loginCode');
    },
    onSuccess: () => router.replace('/'),
    onError: (error) => {
      const retryAfter = studentCodeRetryAfter(error);
      setFailure({ message: studentCodeMessageFor(error), ...(retryAfter ? { until: nowMs() + retryAfter * 1000 } : {}) });
      buzz('error');
    },
    onSettled: () => {
      // The camera sees the same QR code many times a second: wait a moment before reading again.
      setTimeout(() => {
        busy.current = false;
        setScan('looking');
      }, SCAN_COOLDOWN_MS);
    },
  });

  function submit(code: string) {
    if (login.isPending || blockedFor > 0) return;
    setFailure(undefined);
    login.mutate(code);
  }

  function onScanned(data: string) {
    if (busy.current) return;
    busy.current = true;
    const result = classifyScan(data);
    if (result.kind === 'unknown') {
      setScan('notACode');
      buzz('error');
      setTimeout(() => {
        busy.current = false;
        setScan('looking');
      }, SCAN_COOLDOWN_MS);
      return;
    }
    setScan('found');
    buzz('success');
    if (result.kind === 'text') setManualCode(result.code);
    submit(result.code);
  }

  const showCamera = permission?.granted && focused && appActive && !cameraError;
  const frameColor = scan === 'found' ? colors.success : scan === 'notACode' ? colors.destructive : '#FFFFFF';
  const scanText =
    scan === 'found'
      ? t('mobile.studentCode.found')
      : scan === 'notACode'
        ? t('mobile.studentCode.notACode')
        : t('mobile.studentCode.scanHint');

  const failureText =
    failure && (!failure.until || blockedFor > 0)
      ? failure.until
        ? t(failure.message.key, { seconds: blockedFor })
        : t(failure.message.key, failure.message.values)
      : undefined;

  const ready = isCompleteTextCode(manualCode);

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[5] }}>
      {endReason ? (
        <View
          accessibilityRole="alert"
          accessibilityLiveRegion="polite"
          style={{
            gap: spacing[2],
            padding: spacing[4],
            borderRadius: radii.xl,
            borderWidth: 1,
            borderColor: colors.border,
            backgroundColor: colors.muted,
          }}>
          <Text variant="heading">{t('mobile.studentCode.sessionEndedTitle')}</Text>
          <Text>{t('mobile.studentCode.sessionEndedBody')}</Text>
          <Button variant="ghost" fullWidth={false} label={t('mobile.studentCode.gotIt')} onPress={dismissEndReason} />
        </View>
      ) : null}

      <Text variant="heading" style={{ textAlign: 'center' }}>
        {t('mobile.studentCode.subtitle')}
      </Text>

      {showCamera ? (
        <View style={{ gap: spacing[3] }}>
          <View
            style={{ aspectRatio: 1, borderRadius: radii['2xl'], overflow: 'hidden', backgroundColor: colors.muted }}
            accessibilityLabel={t('auth.scanClassCode')}>
            <CameraView
              style={{ flex: 1 }}
              facing="back"
              barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
              onBarcodeScanned={({ data }) => onScanned(data)}
              onMountError={() => setCameraError(true)}
            />
            <View
              pointerEvents="none"
              style={{
                position: 'absolute',
                top: '14%',
                left: '14%',
                right: '14%',
                bottom: '14%',
                borderRadius: radii['2xl'],
                borderWidth: 5,
                borderColor: frameColor,
              }}
            />
          </View>
          <Text
            accessibilityLiveRegion="polite"
            variant="label"
            tone={scan === 'notACode' ? 'destructive' : 'default'}
            style={{ textAlign: 'center' }}>
            {scanText}
          </Text>
        </View>
      ) : (
        <Card accessibilityLabel={t('auth.scanClassCode')}>
          {cameraError ? (
            <Text tone="muted">{t('mobile.studentCode.cameraUnavailable')}</Text>
          ) : !permission ? (
            <Text tone="muted">{t('common.loading')}</Text>
          ) : permission.granted ? null : permission.canAskAgain ? (
            <>
              <Text tone="muted">{t('mobile.studentCode.permissionBody')}</Text>
              <Button label={t('mobile.studentCode.allowCamera')} onPress={() => requestPermission()} />
            </>
          ) : (
            <>
              <Text tone="muted">{t('mobile.studentCode.permissionDenied')}</Text>
              <Button variant="secondary" label={t('mobile.studentCode.openSettings')} onPress={() => Linking.openSettings()} />
            </>
          )}
        </Card>
      )}

      <View style={{ gap: spacing[3] }}>
        <Text variant="heading" style={{ textAlign: 'center' }}>
          {t('mobile.studentCode.typeInstead')}
        </Text>
        <TextField
          label={t('mobile.studentCode.manualLabel')}
          hint={t('mobile.studentCode.manualHint')}
          error={failureText}
          value={manualCode}
          onChangeText={(value) => {
            setManualCode(formatTextCode(value));
            if (failure && !failure.until) setFailure(undefined);
          }}
          placeholder="XXXXX-XXXXX"
          autoCapitalize="characters"
          autoCorrect={false}
          autoComplete="off"
          spellCheck={false}
          maxLength={16}
          returnKeyType="go"
          onSubmitEditing={() => ready && submit(manualCode)}
          editable={!login.isPending}
          style={{ textAlign: 'center', fontSize: 26, letterSpacing: 3 }}
        />
        <Button
          label={t('mobile.studentCode.submit')}
          onPress={() => submit(manualCode)}
          loading={login.isPending}
          disabled={!ready || blockedFor > 0}
        />
      </View>

      <Button
        variant="ghost"
        label={t('mobile.studentCode.teacherLink')}
        onPress={() => router.replace('/(auth)/sign-in')}
        disabled={login.isPending}
      />
    </Screen>
  );
}

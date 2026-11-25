import { useMutation } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useRef, useState, type ComponentRef } from 'react';
import { TextInput, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Button, Screen, Text, TextField } from '@/components/ui';
import { authMessageFor, retryAfterOf, type AuthMessage } from '@/lib/auth/auth-problems';
import { requestOtp, verifyOtp } from '@/lib/auth/auth-requests';
import {
  DEFAULT_CODE_LENGTH,
  RESEND_COOLDOWN_SECONDS,
  formatCountdown,
  intParam,
  nowMs,
  sanitizeCode,
  secondsLeft,
} from '@/lib/auth/otp-timer';
import { useNow } from '@/lib/auth/use-now';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

const DEFAULT_EXPIRES_IN = 300;

interface Failure {
  message: AuthMessage;
  /** Rate limit: the instant the user may try again. */
  until?: number;
}

export default function VerifyScreen() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { completeSignIn } = useSession();
  const params = useLocalSearchParams<{ phone?: string; codeLength?: string; expiresIn?: string; sentAt?: string }>();
  const phone = typeof params.phone === 'string' ? params.phone : '';
  const sentAt = intParam(params.sentAt, nowMs());

  const [codeLength, setCodeLength] = useState(() => intParam(params.codeLength, DEFAULT_CODE_LENGTH));
  const [expiresAt, setExpiresAt] = useState(() => sentAt + intParam(params.expiresIn, DEFAULT_EXPIRES_IN) * 1000);
  const [resendAt, setResendAt] = useState(() => sentAt + RESEND_COOLDOWN_SECONDS * 1000);
  const [code, setCode] = useState('');
  const [failure, setFailure] = useState<Failure>();
  const [resent, setResent] = useState(false);
  const input = useRef<ComponentRef<typeof TextInput>>(null);
  const now = useNow();

  const expired = secondsLeft(expiresAt, now) === 0;
  const blockedFor = failure?.until ? secondsLeft(failure.until, now) : 0;
  const resendIn = Math.max(secondsLeft(resendAt, now), blockedFor);

  const verify = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: async (value: string) => {
      await completeSignIn(await verifyOtp(phone, value));
    },
    onSuccess: () => router.replace('/'),
    onError: (error) => {
      const retryAfter = retryAfterOf(error);
      setFailure({ message: authMessageFor(error), ...(retryAfter ? { until: nowMs() + retryAfter * 1000 } : {}) });
      setCode('');
      input.current?.focus();
    },
  });

  const resend = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: () => requestOtp(phone),
    onSuccess: (challenge) => {
      const sent = nowMs();
      setCodeLength(challenge.codeLength);
      setExpiresAt(sent + challenge.expiresIn * 1000);
      setResendAt(sent + RESEND_COOLDOWN_SECONDS * 1000);
      setCode('');
      setFailure(undefined);
      setResent(true);
      input.current?.focus();
    },
    onError: (error) => {
      const retryAfter = retryAfterOf(error);
      setFailure({ message: authMessageFor(error), ...(retryAfter ? { until: nowMs() + retryAfter * 1000 } : {}) });
    },
  });

  const busy = verify.isPending || resend.isPending;

  function submit(value: string) {
    if (value.length !== codeLength || busy || expired || blockedFor > 0) return;
    setFailure(undefined);
    setResent(false);
    verify.mutate(value);
  }

  function onChange(raw: string) {
    const next = sanitizeCode(raw, codeLength);
    setCode(next);
    if (failure && !failure.until) setFailure(undefined);
    if (next.length === codeLength) submit(next);
  }


  const failureText =
    failure && (!failure.until || blockedFor > 0)
      ? failure.until
        ? t(failure.message.key, { seconds: blockedFor })
        : t(failure.message.key, failure.message.values)
      : undefined;

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[5] }}>
      <Text tone="muted">{t('auth.enterCode', { length: codeLength, destination: phone })}</Text>

      <TextField
        ref={input}
        label={t('mobile.verify.codeLabel')}
        error={failureText}
        value={code}
        onChangeText={onChange}
        keyboardType="number-pad"
        textContentType="oneTimeCode"
        autoComplete="sms-otp"
        autoCorrect={false}
        autoFocus
        maxLength={codeLength + 4}
        returnKeyType="go"
        onSubmitEditing={() => submit(code)}
        editable={!busy}
        style={{ textAlign: 'center', fontSize: 28, letterSpacing: 8 }}
      />

      <View style={{ gap: spacing[1] }} accessibilityLiveRegion="polite">
        {resent ? <Text tone="muted">{t('mobile.verify.resent')}</Text> : null}
        <Text tone="muted" variant="caption">
          {expired
            ? t('mobile.verify.expired')
            : t('mobile.verify.expiresIn', { time: formatCountdown(secondsLeft(expiresAt, now)) })}
        </Text>
      </View>

      <Button
        label={t('auth.signIn')}
        onPress={() => submit(code)}
        loading={verify.isPending}
        disabled={code.length !== codeLength || expired || blockedFor > 0 || resend.isPending}
      />

      <View style={{ gap: spacing[3] }}>
        <Button
          variant="ghost"
          label={resendIn > 0 ? t('mobile.verify.resendIn', { seconds: resendIn }) : t('mobile.verify.resend')}
          onPress={() => resend.mutate()}
          loading={resend.isPending}
          disabled={resendIn > 0 || verify.isPending}
        />
        <Button variant="ghost" label={t('mobile.verify.changeNumber')} onPress={() => router.back()} disabled={busy} />
      </View>
    </Screen>
  );
}

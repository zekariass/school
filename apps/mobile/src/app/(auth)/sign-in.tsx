import { useMutation } from '@tanstack/react-query';
import { Link, router } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { phoneSchema, toFieldErrors } from '@school/validation';

import { ProblemBanner } from '@/components/problem-banner';
import { Button, Screen, Text, TextField } from '@/components/ui';
import { untypedApi } from '@/lib/api';
import { assertOk, request } from '@/lib/api-error';
import { useAppTheme } from '@/lib/theme/theme-provider';

const phoneValidator = phoneSchema('ET');

export default function SignInScreen() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const [phone, setPhone] = useState('');
  const [fieldError, setFieldError] = useState<string>();

  // The OTP endpoint arrives with M1. Until then the backend answers with a problem,
  // which we show as-is: no pretend success.
  const requestCode = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: async (e164: string) =>
      request(async () => {
        const result = await untypedApi.POST('/api/v1/auth/otp/request', { body: { phone: e164 } });
        assertOk(result);
        return e164;
      }),
  });

  function submit() {
    const parsed = phoneValidator.safeParse(phone);
    if (!parsed.success) {
      const error = toFieldErrors(parsed.error)[''];
      setFieldError(error ? t(error.key as 'validation.phone', error.values) : t('validation.phone'));
      return;
    }
    setFieldError(undefined);
    requestCode.mutate(parsed.data);
  }

  return (
    <Screen scroll edges={['top', 'bottom']} contentStyle={{ justifyContent: 'center', gap: spacing[5] }}>
      <View style={{ gap: spacing[2] }}>
        <Text variant="title">{t('auth.signInTitle')}</Text>
        <Text tone="muted">{t('auth.signInSubtitle')}</Text>
      </View>

      <TextField
        label={t('auth.phoneNumber')}
        hint={t('auth.phoneHint')}
        error={fieldError}
        value={phone}
        onChangeText={(value) => {
          setPhone(value);
          if (fieldError) setFieldError(undefined);
          if (!requestCode.isIdle) requestCode.reset();
        }}
        keyboardType="phone-pad"
        textContentType="telephoneNumber"
        autoComplete="tel"
        returnKeyType="send"
        onSubmitEditing={submit}
        editable={!requestCode.isPending}
      />

      {requestCode.isError ? (
        <ProblemBanner
          error={requestCode.error}
          fallbackTitle={t('status.errorTitle')}
          fallbackBody={t('auth.requestFailed')}
        />
      ) : null}
      {requestCode.isSuccess ? (
        <Text accessibilityLiveRegion="polite">{t('auth.codeRequested', { destination: requestCode.data })}</Text>
      ) : null}

      <Button label={t('auth.sendCode')} onPress={submit} loading={requestCode.isPending} />

      <View style={{ gap: spacing[3] }}>
        <Button
          variant="secondary"
          label={t('mobile.signIn.studentLink')}
          onPress={() => router.push('/(auth)/student-code')}
        />
      </View>

      <View style={{ alignItems: 'center', gap: spacing[1] }}>
        <Link href="/status" accessibilityRole="link" style={{ paddingVertical: spacing[2] }}>
          <Text tone="primary" variant="label">
            {t('marketing.statusLink')}
          </Text>
        </Link>
        <Link href="/settings" accessibilityRole="link" style={{ paddingVertical: spacing[2] }}>
          <Text tone="primary" variant="label">
            {t('mobile.signIn.settingsLink')}
          </Text>
        </Link>
        {__DEV__ ? (
          <Link href="/dev" accessibilityRole="link" style={{ paddingVertical: spacing[2] }}>
            <Text tone="muted" variant="caption">
              {t('mobile.signIn.devLink')}
            </Text>
          </Link>
        ) : null}
      </View>
    </Screen>
  );
}

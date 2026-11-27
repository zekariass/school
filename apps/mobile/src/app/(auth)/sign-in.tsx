import { useMutation } from '@tanstack/react-query';
import { Link, router } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { phoneSchema, toFieldErrors } from '@school/validation';

import { PhoneCountryPicker } from '@/components/phone-country-picker';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Screen, Text, TextField } from '@/components/ui';
import { requestOtp } from '@/lib/auth/auth-requests';
import { endNoticeKey } from '@/lib/auth/auth-problems';
import { nowMs } from '@/lib/auth/otp-timer';
import { usePhoneCountry } from '@/lib/country/use-phone-country';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

export default function SignInScreen() {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const [phone, setPhone] = useState('');
  const [fieldError, setFieldError] = useState<string>();
  // The country the number is read in: the platform default from /meta, or the person's pick when several are enabled (D42).
  const phoneCountry = usePhoneCountry();

  const { endReason, dismissEndReason } = useSession();

  // The backend answers 202 whether or not the number has an account; the code step follows.
  const requestCode = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: async (e164: string) => ({ phone: e164, ...(await requestOtp(e164, phoneCountry.country?.code)) }),
    onSuccess: ({ phone: e164, codeLength, expiresIn }) => {
      router.push({
        pathname: '/(auth)/verify',
        params: { phone: e164, codeLength: String(codeLength), expiresIn: String(expiresIn), sentAt: String(nowMs()) },
      });
      // Coming back shows the form again, not a stale success.
      requestCode.reset();
    },
  });


  function submit() {
    const parsed = phoneSchema(phoneCountry.regionCode).safeParse(phone);
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
          <Text>{t(endNoticeKey(endReason))}</Text>
          <Button variant="ghost" fullWidth={false} label={t('common.done')} onPress={dismissEndReason} />
        </View>
      ) : null}

      {phoneCountry.showPicker ? (
        <PhoneCountryPicker
          countries={phoneCountry.countries}
          value={phoneCountry.country?.code}
          onChange={phoneCountry.select}
          disabled={requestCode.isPending}
        />
      ) : null}

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

      <Button label={t('auth.sendCode')} onPress={submit} loading={requestCode.isPending || phoneCountry.loading} />

      <View style={{ gap: spacing[3] }}>
        <Button
          variant="secondary"
          label={t('mobile.signIn.studentLink')}
          onPress={() => router.push('/(auth)/student-code')}
        />
        {/* A parent with a printed code has no account yet: the code is accepted before signing in. */}
        <Button variant="ghost" label={t('family.connect.open')} onPress={() => router.push('/parent-code')} />
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

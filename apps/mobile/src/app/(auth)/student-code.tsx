import { useMutation } from '@tanstack/react-query';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useIsFocused } from 'expo-router';
import { useRef, useState } from 'react';
import { Linking, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, Screen, Text, TextField } from '@/components/ui';
import { untypedApi } from '@/lib/api';
import { assertOk, request } from '@/lib/api-error';
import { useAppTheme } from '@/lib/theme/theme-provider';

export default function StudentCodeScreen() {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const focused = useIsFocused();
  const [permission, requestPermission] = useCameraPermissions();
  const [manualCode, setManualCode] = useState('');
  const [cameraError, setCameraError] = useState(false);
  const handled = useRef(false);

  // The login-code endpoint arrives with M1; show whatever problem the backend returns.
  const login = useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: async (code: string) =>
      request(async () => {
        const result = await untypedApi.POST('/api/v1/auth/login-code', { body: { code } });
        assertOk(result);
        return code;
      }),
    onSettled: () => {
      handled.current = false;
    },
  });

  function submit(raw: string) {
    const code = raw.trim();
    if (!code || login.isPending) return;
    login.mutate(code);
  }

  function onScanned(data: string) {
    if (handled.current) return;
    handled.current = true;
    setManualCode(data);
    submit(data);
  }

  const showCamera = permission?.granted && focused && !cameraError;

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[5] }}>
      <Text tone="muted">{t('mobile.studentCode.subtitle')}</Text>

      {showCamera ? (
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
        </View>
      ) : (
        <Card>
          <Text variant="heading">{t('auth.scanClassCode')}</Text>
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
              <Button label={t('mobile.studentCode.openSettings')} onPress={() => Linking.openSettings()} />
            </>
          )}
        </Card>
      )}

      <TextField
        label={t('mobile.studentCode.manualLabel')}
        hint={t('mobile.studentCode.manualHint')}
        value={manualCode}
        onChangeText={setManualCode}
        autoCapitalize="characters"
        autoCorrect={false}
        returnKeyType="go"
        onSubmitEditing={() => submit(manualCode)}
        editable={!login.isPending}
      />

      {login.isError ? (
        <ProblemBanner
          error={login.error}
          fallbackTitle={t('status.errorTitle')}
          fallbackBody={t('auth.requestFailed')}
        />
      ) : null}

      <Button
        label={t('auth.signIn')}
        onPress={() => submit(manualCode)}
        loading={login.isPending}
        disabled={manualCode.trim().length === 0}
      />
    </Screen>
  );
}

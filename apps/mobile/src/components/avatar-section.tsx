import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useReducer, useState } from 'react';
import { Linking, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { api } from '@/lib/api';
import { ApiRequestError, request, unwrap } from '@/lib/api-error';
import { loadMe } from '@/lib/auth/auth-requests';
import { policyFor, validateFile, typeLabels, maxMegabytes, type FileProblem } from '@/lib/media/policies';
import { UploadError, pickPhoto, prepareImage, uploadImage, type PickSource } from '@/lib/media/upload';
import { IDLE, isBusy, uploadReducer, type UploadFailure } from '@/lib/media/upload-state';
import { useMediaPolicies } from '@/lib/media/use-media';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Avatar } from './avatar';
import { Button, Card, Text } from './ui';

const ME_KEY = ['me'] as const;

/** Profile photo of the signed-in user: take or choose a picture, upload it, remove it. */
export function AvatarSection() {
  const t = useTranslations();
  const { spacing, colors } = useAppTheme();
  const queryClient = useQueryClient();
  const me = useQuery({ queryKey: ME_KEY, queryFn: loadMe });
  const policies = useMediaPolicies();
  const policy = policies.data ? policyFor(policies.data, 'AVATAR') : undefined;
  const [state, dispatch] = useReducer(uploadReducer, IDLE);
  const [problem, setProblem] = useState<{ message: string; openSettings?: boolean } | null>(null);
  const busy = isBusy(state);

  const remove = useMutation({
    mutationFn: () => request(async () => unwrap(await api.DELETE('/api/v1/me/avatar'))),
    onSuccess: (next) => queryClient.setQueryData(ME_KEY, next),
    onError: (error) => setProblem({ message: serverMessage(error) ?? t('media.errors.network') }),
  });

  function describeFile(file: FileProblem): string {
    switch (file.code) {
      case 'empty':
        return t('media.errors.empty');
      case 'type':
        return t('media.errors.type', { types: file.types.join(', ') });
      case 'size':
        return t('media.errors.size', { size: file.maxMb });
    }
  }

  function describeFailure(error: unknown, reason: UploadFailure): string {
    const server = error instanceof UploadError ? error.problem?.detail : serverMessage(error);
    return server ?? t(`media.errors.${reason === 'other' ? 'network' : reason}`);
  }

  async function choose(source: PickSource) {
    setProblem(null);
    if (!policy) {
      setProblem({ message: t('media.errors.policies') });
      return;
    }
    dispatch({ type: 'start' });
    try {
      const picked = await pickPhoto(source);
      if (picked.status === 'cancelled') {
        dispatch({ type: 'reset' });
        return;
      }
      if (picked.status !== 'picked') {
        dispatch({ type: 'reset' });
        setProblem(
          picked.status === 'denied'
            ? { message: t('media.picker.cameraDenied'), openSettings: !picked.canAskAgain }
            : { message: picked.status === 'unavailable' ? t('media.picker.cameraUnavailable') : t('media.errors.processing') },
        );
        return;
      }
      const image = await prepareImage(picked.asset);
      const invalid = validateFile({ type: image.mimeType, size: image.sizeBytes }, policy);
      if (invalid) {
        dispatch({ type: 'reset' });
        setProblem({ message: describeFile(invalid) });
        return;
      }
      const mediaId = await uploadImage({
        purpose: 'AVATAR',
        image,
        onStage: (stage) => dispatch({ type: 'stage', stage }),
        onProgress: (bytesSent, totalBytes) => dispatch({ type: 'progress', bytesSent, totalBytes }),
      });
      const next = await request(async () => unwrap(await api.PUT('/api/v1/me/avatar', { body: { mediaId } })));
      queryClient.setQueryData(ME_KEY, next);
      dispatch({ type: 'ready', mediaId });
    } catch (error) {
      const reason: UploadFailure = error instanceof UploadError ? error.reason : 'other';
      dispatch({ type: 'failed', reason });
      setProblem({ message: describeFailure(error, reason) });
    }
  }

  const status =
    state.phase === 'running'
      ? state.stage === 'uploading'
        ? t('media.upload.uploading', { percent: state.percent })
        : t(
            state.stage === 'completing'
              ? 'media.upload.checking'
              : state.stage === 'processing'
                ? 'media.upload.processing'
                : 'media.upload.preparing',
          )
      : null;

  const profile = me.data;
  return (
    <Card>
      <Text variant="heading">{t('media.avatar.title')}</Text>
      <Text variant="caption">{t('media.avatar.hint')}</Text>
      <View style={{ alignItems: 'center', gap: spacing[3] }}>
        <Avatar
          mediaId={profile?.avatar?.id}
          name={profile?.displayName ?? '?'}
          size={112}
          accessibilityLabel={t('media.avatar.alt', { name: profile?.displayName ?? '' })}
        />
      </View>
      {status ? (
        <View accessibilityLiveRegion="polite" style={{ gap: spacing[2] }}>
          <View
            accessibilityRole="progressbar"
            accessibilityLabel={t('media.upload.progressLabel')}
            accessibilityValue={state.phase === 'running' && state.stage === 'uploading' ? { min: 0, max: 100, now: state.percent } : {}}
            style={{ height: 8, borderRadius: 4, backgroundColor: colors.muted, overflow: 'hidden' }}>
            <View
              style={{
                height: 8,
                width: state.phase === 'running' && state.stage === 'uploading' ? `${state.percent}%` : '100%',
                backgroundColor: colors.primary,
                opacity: state.phase === 'running' && state.stage === 'uploading' ? 1 : 0.4,
              }}
            />
          </View>
          <Text variant="caption">{status}</Text>
        </View>
      ) : null}
      {problem ? (
        <View accessibilityRole="alert" style={{ gap: spacing[2] }}>
          <Text tone="destructive">{problem.message}</Text>
          {problem.openSettings ? (
            <Button variant="ghost" fullWidth={false} label={t('mobile.studentCode.openSettings')} onPress={() => void Linking.openSettings()} />
          ) : null}
        </View>
      ) : null}
      {policy ? (
        <Text variant="caption">
          {t('media.upload.accept', { types: typeLabels(Object.keys(policy.maxBytesByMimeType)).join(', '), size: maxMegabytes(policy) })}
        </Text>
      ) : null}
      <Button
        label={t('media.picker.chooseFromLibrary')}
        variant="secondary"
        disabled={busy || !policy}
        onPress={() => void choose('library')}
      />
      <Button label={t('media.picker.takePhoto')} variant="ghost" disabled={busy || !policy} onPress={() => void choose('camera')} />
      {profile?.avatar ? (
        <Button
          label={t('media.avatar.remove')}
          variant="ghost"
          loading={remove.isPending}
          disabled={busy}
          onPress={() => remove.mutate()}
        />
      ) : null}
    </Card>
  );
}

function serverMessage(error: unknown): string | undefined {
  return error instanceof ApiRequestError ? error.problem?.detail : undefined;
}

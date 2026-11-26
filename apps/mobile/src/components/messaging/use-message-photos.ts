import { randomUUID } from 'expo-crypto';
import type * as ImagePicker from 'expo-image-picker';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import { policyFor, typeLabels, validateFile } from '@/lib/media/policies';
import { UploadError, deletePrepared, discardUpload, pickPhoto, prepareImage, uploadImage, type PickSource, type PreparedImage } from '@/lib/media/upload';
import { useMediaPolicies } from '@/lib/media/use-media';

export const MAX_PHOTOS = 4;

export interface Photo {
  key: string;
  uri: string;
  status: 'uploading' | 'ready' | 'failed';
  percent: number;
  mediaId?: string;
  message?: string;
}

/**
 * Photos for one message: picked, converted to JPEG and shrunk on the device (no location data), uploaded straight to storage with
 * purpose MESSAGE for this conversation, kept as a failed photo with a retry when anything goes wrong. The converted copies on the
 * device are deleted as soon as they are uploaded and when the screen closes; an upload that was never sent is discarded.
 */
export function useMessagePhotos(conversationId: string | undefined) {
  const t = useTranslations();
  const policies = useMediaPolicies();
  const policy = policies.data ? policyFor(policies.data, 'MESSAGE') : undefined;
  const [photos, setPhotos] = useState<Photo[]>([]);
  const assets = useRef(new Map<string, ImagePicker.ImagePickerAsset>());
  const prepared = useRef(new Map<string, PreparedImage>());
  const aborts = useRef(new Map<string, AbortController>());
  const uploaded = useRef(new Set<string>());
  const sent = useRef(new Set<string>());

  const patch = useCallback((key: string, change: Partial<Photo>) => setPhotos((list) => list.map((photo) => (photo.key === key ? { ...photo, ...change } : photo))), []);

  const describe = useCallback(
    (error: unknown): string => {
      if (error instanceof UploadError) return error.problem?.detail ?? t(`media.errors.${error.reason === 'other' ? 'network' : error.reason}`);
      return problemOf(error)?.detail ?? t('media.errors.network');
    },
    [t],
  );

  const run = useCallback(
    async (key: string) => {
      const asset = assets.current.get(key);
      if (!asset || !conversationId) return;
      const controller = new AbortController();
      aborts.current.set(key, controller);
      try {
        let image = prepared.current.get(key);
        if (!image) {
          image = await prepareImage(asset);
          prepared.current.set(key, image);
        }
        if (policy) {
          const invalid = validateFile({ type: image.mimeType, size: image.sizeBytes }, policy);
          if (invalid) {
            patch(key, {
              status: 'failed',
              message: invalid.code === 'size' ? t('media.errors.size', { size: invalid.maxMb }) : invalid.code === 'type' ? t('media.errors.type', { types: invalid.types.join(', ') }) : t('media.errors.empty'),
            });
            return;
          }
        }
        patch(key, { status: 'uploading', percent: 0 });
        const mediaId = await uploadImage({
          purpose: 'MESSAGE',
          context: { type: 'CONVERSATION', id: conversationId },
          image,
          signal: controller.signal,
          onProgress: (done, total) => patch(key, { percent: total > 0 ? (done / total) * 100 : 0 }),
        });
        uploaded.current.add(mediaId);
        patch(key, { status: 'ready', percent: 100, mediaId });
        deletePrepared(image.uri);
        prepared.current.delete(key);
      } catch (error) {
        if (controller.signal.aborted) return;
        patch(key, { status: 'failed', message: describe(error) });
      } finally {
        aborts.current.delete(key);
      }
    },
    [conversationId, describe, patch, policy, t],
  );

  /** Opens the camera or the library. Answers with a message to show when there is no photo to add, else null. */
  const add = useCallback(
    async (source: PickSource): Promise<string | null> => {
      if (photos.length >= MAX_PHOTOS) return t('mobile.messaging.composer.tooManyPhotos', { max: MAX_PHOTOS });
      const picked = await pickPhoto(source);
      switch (picked.status) {
        case 'cancelled':
          return null;
        case 'denied':
          return source === 'camera' ? t('media.picker.cameraDenied') : t('media.picker.libraryDenied');
        case 'unavailable':
          return t('media.picker.cameraUnavailable');
        case 'unsupported':
          return policy ? t('media.errors.type', { types: typeLabels(Object.keys(policy.maxBytesByMimeType)).join(', ') }) : t('media.errors.processing');
        case 'picked': {
          const key = randomUUID();
          assets.current.set(key, picked.asset);
          setPhotos((list) => [...list, { key, uri: picked.asset.uri, status: 'uploading', percent: 0 }]);
          void run(key);
          return null;
        }
      }
    },
    [photos.length, policy, run, t],
  );

  const retry = useCallback(
    (key: string) => {
      patch(key, { status: 'uploading', percent: 0 });
      void run(key);
    },
    [patch, run],
  );

  const remove = useCallback(
    (photo: Photo) => {
      aborts.current.get(photo.key)?.abort();
      const image = prepared.current.get(photo.key);
      if (image) deletePrepared(image.uri);
      prepared.current.delete(photo.key);
      assets.current.delete(photo.key);
      setPhotos((list) => list.filter((item) => item.key !== photo.key));
      if (photo.mediaId && uploaded.current.has(photo.mediaId)) {
        uploaded.current.delete(photo.mediaId);
        void discardUpload(photo.mediaId);
      }
    },
    [],
  );

  /** The message was handed to the outbox: its photos belong to it now, so closing the screen must not delete them. */
  const adopt = useCallback((mediaIds: readonly string[]) => {
    for (const id of mediaIds) sent.current.add(id);
    setPhotos([]);
  }, []);

  useEffect(() => {
    const running = aborts.current;
    const files = prepared.current;
    const done = uploaded.current;
    const kept = sent.current;
    const picked = assets.current;
    return () => {
      for (const controller of running.values()) controller.abort();
      for (const image of files.values()) deletePrepared(image.uri);
      for (const asset of picked.values()) if (asset.uri.includes('/ImagePicker/')) deletePrepared(asset.uri);
      for (const id of done) if (!kept.has(id)) void discardUpload(id);
    };
  }, []);

  const ready = photos.filter((photo) => photo.status === 'ready' && photo.mediaId).map((photo) => photo.mediaId!);
  const busy = photos.some((photo) => photo.status === 'uploading');
  return { photos, add, retry, remove, adopt, ready, busy, canAdd: photos.length < MAX_PHOTOS };
}

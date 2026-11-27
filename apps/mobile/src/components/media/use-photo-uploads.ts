import { randomUUID } from 'expo-crypto';
import type * as ImagePicker from 'expo-image-picker';
import { useCallback, useEffect, useRef, type Dispatch } from 'react';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import type { Attachment, AttachmentAction } from '@/lib/media/attachments';
import { policyFor, typeLabels, validateFile, type FileProblem, type MediaPurpose } from '@/lib/media/policies';
import { UploadError, deletePrepared, discardUpload, pickPhoto, prepareImage, uploadImage, type PickSource, type PreparedImage } from '@/lib/media/upload';
import { useMediaPolicies } from '@/lib/media/use-media';

export type PhotoTarget = { purpose: Extract<MediaPurpose, 'POST' | 'STORY' | 'PORTFOLIO'>; context: { type: 'CLASS' | 'SCHOOL'; id: string } };

/**
 * Picks, converts and uploads photos for a post, a story or a portfolio item, one at a time per photo, keeping the screen's composer state in
 * step (`dispatch`). The photo is shrunk to JPEG on the device first (no location data), PUT straight to storage with a
 * progress bar, and kept as a failed attachment (with a retry) when anything goes wrong: nothing the author did is lost. The
 * converted copies on the device are deleted as soon as they are uploaded and when the screen closes.
 *
 * `add` answers with a message to show when there is no photo to add (permission, no camera, wrong kind), else null.
 */
export function usePhotoUploads({ dispatch, target }: { dispatch: Dispatch<AttachmentAction>; target: PhotoTarget }) {
  const t = useTranslations();
  const policies = useMediaPolicies();
  const policy = policies.data ? policyFor(policies.data, target.purpose) : undefined;
  const assets = useRef(new Map<string, ImagePicker.ImagePickerAsset>());
  const prepared = useRef(new Map<string, PreparedImage>());
  const aborts = useRef(new Map<string, AbortController>());
  const uploaded = useRef(new Set<string>());
  const keep = useRef(new Set<string>());

  const describe = useCallback(
    (error: unknown): string => {
      if (error instanceof UploadError) {
        return error.problem?.detail ?? t(`media.errors.${error.reason === 'other' ? 'network' : error.reason}`);
      }
      return problemOf(error)?.detail ?? t('media.errors.network');
    },
    [t],
  );

  const describeFile = useCallback(
    (file: FileProblem): string => {
      switch (file.code) {
        case 'empty':
          return t('media.errors.empty');
        case 'type':
          return t('media.errors.type', { types: file.types.join(', ') });
        case 'size':
          return t('media.errors.size', { size: file.maxMb });
      }
    },
    [t],
  );

  const run = useCallback(
    async (key: string) => {
      const asset = assets.current.get(key);
      if (!asset) return;
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
            dispatch({ type: 'attachFailed', key, message: describeFile(invalid) });
            return;
          }
        }
        dispatch({ type: 'attachStatus', key, status: 'uploading' });
        const mediaId = await uploadImage({
          purpose: target.purpose,
          context: target.context,
          image,
          signal: controller.signal,
          onProgress: (sent, total) => dispatch({ type: 'attachProgress', key, percent: total > 0 ? (sent / total) * 100 : 0 }),
        });
        uploaded.current.add(mediaId);
        dispatch({ type: 'attachReady', key, mediaId });
        // The converted copy has done its job.
        deletePrepared(image.uri);
        prepared.current.delete(key);
      } catch (error) {
        if (controller.signal.aborted) return;
        dispatch({ type: 'attachFailed', key, message: describe(error) });
      } finally {
        aborts.current.delete(key);
      }
    },
    [describe, describeFile, dispatch, policy, target.context, target.purpose],
  );

  const add = useCallback(
    async (source: PickSource): Promise<string | null> => {
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
          dispatch({ type: 'attach', key, uri: picked.asset.uri });
          void run(key);
          return null;
        }
      }
    },
    [dispatch, policy, run, t],
  );

  const retry = useCallback(
    (key: string) => {
      dispatch({ type: 'attachRetry', key });
      void run(key);
    },
    [dispatch, run],
  );

  /** Takes a photo out: stops its upload and removes what was already uploaded (the post will not use it). */
  const remove = useCallback(
    (attachment: Attachment) => {
      aborts.current.get(attachment.key)?.abort();
      const image = prepared.current.get(attachment.key);
      if (image) deletePrepared(image.uri);
      prepared.current.delete(attachment.key);
      assets.current.delete(attachment.key);
      dispatch({ type: 'detach', key: attachment.key });
      if (attachment.mediaId && uploaded.current.has(attachment.mediaId)) {
        uploaded.current.delete(attachment.mediaId);
        void discardUpload(attachment.mediaId);
      }
    },
    [dispatch],
  );

  /** The post or story was made: its photos belong to it now, so closing the screen must not delete them. */
  const adopt = useCallback((mediaIds: readonly string[]) => {
    for (const id of mediaIds) keep.current.add(id);
  }, []);

  // Closing the screen: stop what is running, delete the temporary copies and any upload that was never attached.
  useEffect(() => {
    const running = aborts.current;
    const files = prepared.current;
    const sent = uploaded.current;
    const kept = keep.current;
    const picked = assets.current;
    return () => {
      for (const controller of running.values()) controller.abort();
      for (const image of files.values()) deletePrepared(image.uri);
      // The picker's own copy in the app's cache folder (never the library original).
      for (const asset of picked.values()) if (asset.uri.includes('/ImagePicker/')) deletePrepared(asset.uri);
      for (const id of sent) if (!kept.has(id)) void discardUpload(id);
    };
  }, []);

  return { add, retry, remove, adopt };
}

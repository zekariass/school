import { randomUUID } from 'expo-crypto';
import { File, UploadType } from 'expo-file-system';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';

import { api } from '@/lib/api';
import { ApiRequestError, request, unwrap } from '@/lib/api-error';

import { MAX_UPLOAD_SIDE, UPLOAD_JPEG_QUALITY, UPLOAD_MIME_TYPE, isPreparable, resizeTarget, uploadFileName } from './image-plan';
import type { MediaPurpose } from './policies';
import {
  PROCESSING_TIMEOUT_MS,
  afterStatus,
  pollDelay,
  type MediaStatus,
  type UploadFailure,
  type UploadStage,
} from './upload-state';

export type PickSource = 'camera' | 'library';

export type PickOutcome =
  | { status: 'picked'; asset: ImagePicker.ImagePickerAsset }
  | { status: 'cancelled' }
  | { status: 'denied'; canAskAgain: boolean }
  | { status: 'unavailable' }
  | { status: 'unsupported' };

/**
 * Opens the camera or the photo library. The library uses the system picker, which needs no
 * permission (the app only ever sees the photo that was chosen); the camera asks once.
 */
export async function pickPhoto(source: PickSource): Promise<PickOutcome> {
  const options: ImagePicker.ImagePickerOptions = {
    mediaTypes: ['images'],
    quality: 1,
    exif: false,
    // iOS: hand over a JPEG when the library holds HEIC; the app converts whatever it gets anyway.
    preferredAssetRepresentationMode: ImagePicker.UIImagePickerPreferredAssetRepresentationMode.Compatible,
  };
  let result: ImagePicker.ImagePickerResult;
  if (source === 'camera') {
    const permission = await ImagePicker.requestCameraPermissionsAsync();
    if (!permission.granted) return { status: 'denied', canAskAgain: permission.canAskAgain };
    try {
      result = await ImagePicker.launchCameraAsync(options);
    } catch {
      // The iOS Simulator and devices without a camera.
      return { status: 'unavailable' };
    }
  } else {
    result = await ImagePicker.launchImageLibraryAsync(options);
  }
  const asset = result.canceled ? undefined : result.assets[0];
  if (!asset) return { status: 'cancelled' };
  return isPreparable(asset) ? { status: 'picked', asset } : { status: 'unsupported' };
}

export interface PreparedImage {
  uri: string;
  fileName: string;
  mimeType: typeof UPLOAD_MIME_TYPE;
  sizeBytes: number;
}

/**
 * Converts the picked photo to JPEG (HEIC included) and shrinks it to at most 2048 px on the
 * longest side. The result is re-encoded from pixels, so it carries no EXIF or location data.
 */
export async function prepareImage(asset: ImagePicker.ImagePickerAsset): Promise<PreparedImage> {
  // The load options bound the decoder's memory for very large photos.
  const context = ImageManipulator.manipulate(asset.uri, { maxWidth: MAX_UPLOAD_SIDE, maxHeight: MAX_UPLOAD_SIDE });
  const target = resizeTarget(asset.width, asset.height);
  if (target) context.resize(target);
  const rendered = await context.renderAsync();
  const saved = await rendered.saveAsync({ format: SaveFormat.JPEG, compress: UPLOAD_JPEG_QUALITY });
  const file = new File(saved.uri);
  return {
    uri: saved.uri,
    fileName: uploadFileName(asset.fileName, Date.now()),
    mimeType: UPLOAD_MIME_TYPE,
    sizeBytes: file.size ?? 0,
  };
}

/** A failed upload, with the stage and the API's problem when it sent one. */
export class UploadError extends Error {
  constructor(
    readonly reason: UploadFailure,
    readonly stage: UploadStage,
    readonly problem?: ApiRequestError['problem'],
  ) {
    super(problem?.detail ?? problem?.title ?? `Upload failed (${reason}) while ${stage}`);
    this.name = 'UploadError';
  }
}

export interface UploadParams {
  purpose: MediaPurpose;
  image: PreparedImage;
  onStage?: (stage: UploadStage) => void;
  onProgress?: (bytesSent: number, totalBytes: number) => void;
  signal?: AbortSignal;
}

/**
 * Request an upload URL, PUT the file straight to storage (the URL points at
 * `school.storage.public-endpoint`: reachable from the phone), have the API verify it, and
 * wait for processing. Resolves with the READY media id.
 */
export async function uploadImage({ purpose, image, onStage, onProgress, signal }: UploadParams): Promise<string> {
  onStage?.('requesting');
  const ticket = await apiCall('requesting', async () =>
    unwrap(
      await api.POST('/api/v1/media/uploads', {
        headers: { 'Idempotency-Key': randomUUID() },
        body: { purpose, filename: image.fileName, mimeType: image.mimeType, sizeBytes: image.sizeBytes },
        signal,
      }),
    ),
  );

  onStage?.('uploading');
  let status: number;
  try {
    const result = await new File(image.uri).upload(ticket.upload.url, {
      httpMethod: 'PUT',
      uploadType: UploadType.BINARY_CONTENT,
      headers: ticket.upload.headers,
      onProgress: ({ bytesSent, totalBytes }) => onProgress?.(bytesSent, totalBytes),
      ...(signal ? { signal } : {}),
    });
    status = result.status;
  } catch {
    throw new UploadError('network', 'uploading');
  }
  if (status < 200 || status >= 300) throw new UploadError(status === 403 ? 'rejected' : 'network', 'uploading');

  onStage?.('completing');
  await apiCall('completing', async () =>
    unwrap(await api.POST('/api/v1/media/{id}/complete', { params: { path: { id: ticket.mediaId } }, signal })),
  );

  onStage?.('processing');
  const startedAt = Date.now();
  for (let attempt = 0; ; attempt++) {
    const media = await apiCall('processing', async () =>
      unwrap(await api.GET('/api/v1/media/{id}', { params: { path: { id: ticket.mediaId } }, signal })),
    );
    const next = afterStatus(media.status as MediaStatus);
    if (next === 'ready') return media.id;
    if (next !== 'wait') throw new UploadError(next, 'processing');
    if (Date.now() - startedAt > PROCESSING_TIMEOUT_MS) throw new UploadError('timeout', 'processing');
    await new Promise((resolve) => setTimeout(resolve, pollDelay(attempt)));
  }
}

async function apiCall<T>(stage: UploadStage, call: () => Promise<T>): Promise<T> {
  try {
    return await request(call);
  } catch (error) {
    if (error instanceof UploadError) throw error;
    if (error instanceof ApiRequestError) {
      const rejected = error.problem?.code === 'media.type_mismatch' || error.problem?.code === 'media.size_mismatch';
      throw new UploadError(rejected ? 'rejected' : error.status ? 'other' : 'network', stage, error.problem);
    }
    throw new UploadError('network', stage);
  }
}

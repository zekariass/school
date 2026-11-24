/**
 * Pictures from the camera or library are converted on the device before upload: HEIC (and
 * anything else the platform can decode) becomes JPEG, and large photos are shrunk, so
 * uploads are small, work everywhere and match the API's accepted types.
 */

/** Longest side of the uploaded picture, in pixels. */
export const MAX_UPLOAD_SIDE = 2048;

/** JPEG quality of the converted picture. */
export const UPLOAD_JPEG_QUALITY = 0.85;

export const UPLOAD_MIME_TYPE = 'image/jpeg';

/** Pictures the platform can turn into JPEG (HEIC/HEIF come from iPhones). */
const CONVERTIBLE = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif']);

/** True for a picked asset the app can prepare: an image of a known type, or one without a reported type. */
export function isPreparable(asset: { mimeType?: string | null | undefined; type?: string | null | undefined }): boolean {
  if (asset.type && asset.type !== 'image') return false;
  if (!asset.mimeType) return true;
  return CONVERTIBLE.has(asset.mimeType.split(';')[0]?.trim().toLowerCase() ?? '');
}

/**
 * The size to shrink to: the longest side becomes {@link MAX_UPLOAD_SIDE}, the aspect ratio
 * stays. `null` when the picture is already small enough (or its size is unknown, where the
 * decoder's own downsampling limit applies).
 */
export function resizeTarget(width: number, height: number, maxSide = MAX_UPLOAD_SIDE): { width: number } | { height: number } | null {
  if (!(width > 0) || !(height > 0)) return null;
  if (Math.max(width, height) <= maxSide) return null;
  return width >= height ? { width: maxSide } : { height: maxSide };
}

/** A file name for the converted picture: the original's name with a .jpg extension. */
export function uploadFileName(original: string | null | undefined, now: number): string {
  const base = (original ?? '').replace(/\.[^./\\]*$/, '').replace(/[^\w.-]+/g, '_').slice(0, 60);
  return `${base || `photo-${now}`}.jpg`;
}

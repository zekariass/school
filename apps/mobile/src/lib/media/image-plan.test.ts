import { describe, expect, it } from 'vitest';

import { isPreparable, MAX_UPLOAD_SIDE, resizeTarget, uploadFileName } from './image-plan';

describe('resizeTarget', () => {
  it('shrinks the longest side to 2048 and keeps the ratio', () => {
    expect(resizeTarget(4032, 3024)).toEqual({ width: MAX_UPLOAD_SIDE });
    expect(resizeTarget(3024, 4032)).toEqual({ height: MAX_UPLOAD_SIDE });
    expect(resizeTarget(5000, 5000)).toEqual({ width: MAX_UPLOAD_SIDE });
  });

  it('leaves small pictures alone, and unknown sizes to the decoder', () => {
    expect(resizeTarget(2048, 1000)).toBeNull();
    expect(resizeTarget(800, 600)).toBeNull();
    expect(resizeTarget(0, 0)).toBeNull();
  });
});

describe('isPreparable', () => {
  it('accepts phone photo formats, including HEIC', () => {
    for (const mimeType of ['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/HEIF']) {
      expect(isPreparable({ mimeType, type: 'image' })).toBe(true);
    }
    expect(isPreparable({ mimeType: null, type: 'image' })).toBe(true);
  });

  it('refuses videos and unknown types', () => {
    expect(isPreparable({ mimeType: 'video/mp4', type: 'video' })).toBe(false);
    expect(isPreparable({ mimeType: 'image/tiff', type: 'image' })).toBe(false);
    expect(isPreparable({ mimeType: 'application/pdf' })).toBe(false);
  });
});

describe('uploadFileName', () => {
  it('uses the original name with a .jpg extension', () => {
    expect(uploadFileName('IMG_0001.HEIC', 1)).toBe('IMG_0001.jpg');
    expect(uploadFileName('my photo (1).png', 1)).toBe('my_photo_1_.jpg');
  });

  it('falls back to a timestamp name', () => {
    expect(uploadFileName(null, 1700)).toBe('photo-1700.jpg');
    expect(uploadFileName('', 5)).toBe('photo-5.jpg');
  });
});

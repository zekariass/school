import { describe, expect, it } from 'vitest';

import { maxMegabytes, policyFor, validateFile, type PurposePolicy } from './policies';

const MB = 1024 * 1024;
const avatar: PurposePolicy = {
  purpose: 'AVATAR',
  contextTypes: ['USER'],
  maxBytesByMimeType: { 'image/jpeg': 10 * MB, 'image/png': 10 * MB },
};

describe('validateFile', () => {
  it('accepts an allowed type within the limit', () => {
    expect(validateFile({ type: 'image/jpeg', size: 3 * MB }, avatar)).toBeNull();
  });

  it('rejects empty, wrong-type and oversized files', () => {
    expect(validateFile({ type: 'image/jpeg', size: 0 }, avatar)).toEqual({ code: 'empty' });
    expect(validateFile({ type: 'image/heic', size: 100 }, avatar)).toEqual({ code: 'type', types: ['JPEG', 'PNG'] });
    expect(validateFile({ type: 'image/png', size: 10 * MB + 1 }, avatar)).toEqual({ code: 'size', maxMb: 10 });
  });
});

describe('policy lookup', () => {
  it('finds a purpose and its largest limit', () => {
    expect(policyFor({ purposes: [avatar] }, 'AVATAR')).toBe(avatar);
    expect(policyFor({ purposes: [avatar] }, 'POST')).toBeUndefined();
    expect(maxMegabytes(avatar)).toBe(10);
  });
});

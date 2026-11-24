import { describe, expect, it } from 'vitest';

import { afterStatus, IDLE, isBusy, percentOf, pollDelay, uploadReducer, type UploadState } from './upload-state';

describe('uploadReducer', () => {
  it('walks through the stages to ready', () => {
    let state: UploadState = IDLE;
    state = uploadReducer(state, { type: 'start' });
    expect(state).toEqual({ phase: 'running', stage: 'preparing', percent: 0 });
    state = uploadReducer(state, { type: 'stage', stage: 'requesting' });
    state = uploadReducer(state, { type: 'stage', stage: 'uploading' });
    state = uploadReducer(state, { type: 'progress', bytesSent: 30, totalBytes: 120 });
    expect(state).toEqual({ phase: 'running', stage: 'uploading', percent: 25 });
    state = uploadReducer(state, { type: 'stage', stage: 'completing' });
    state = uploadReducer(state, { type: 'stage', stage: 'processing' });
    expect(isBusy(state)).toBe(true);
    state = uploadReducer(state, { type: 'ready', mediaId: 'm1' });
    expect(state).toEqual({ phase: 'ready', mediaId: 'm1' });
    expect(isBusy(state)).toBe(false);
  });

  it('ignores progress outside the uploading stage', () => {
    const running = uploadReducer(IDLE, { type: 'start' });
    expect(uploadReducer(running, { type: 'progress', bytesSent: 5, totalBytes: 10 })).toBe(running);
    expect(uploadReducer(IDLE, { type: 'stage', stage: 'uploading' })).toBe(IDLE);
  });

  it('records a failure, resets and restarts', () => {
    const failed = uploadReducer(uploadReducer(IDLE, { type: 'start' }), { type: 'failed', reason: 'network' });
    expect(failed).toEqual({ phase: 'failed', reason: 'network' });
    expect(uploadReducer(failed, { type: 'reset' })).toBe(IDLE);
    expect(uploadReducer(failed, { type: 'start' })).toMatchObject({ phase: 'running', stage: 'preparing' });
  });
});

describe('helpers', () => {
  it('computes percentages within 0..100', () => {
    expect(percentOf(1, 3)).toBe(33);
    expect(percentOf(1, 0)).toBe(0);
    expect(percentOf(9, 3)).toBe(100);
  });

  it('polls quickly, then every 2 seconds', () => {
    expect([0, 1, 2, 3, 4, 9].map(pollDelay)).toEqual([400, 600, 1000, 1500, 2000, 2000]);
  });

  it('maps media statuses to the next step', () => {
    expect(afterStatus('READY')).toBe('ready');
    expect(afterStatus('PROCESSING')).toBe('wait');
    expect(afterStatus('UPLOADED')).toBe('wait');
    expect(afterStatus('QUARANTINED')).toBe('rejected');
    expect(afterStatus('FAILED')).toBe('processing');
  });
});

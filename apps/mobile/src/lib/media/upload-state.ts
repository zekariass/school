/** The upload as a small state machine (pure, unit-tested); the same stages as the web app. */

export type UploadStage = 'preparing' | 'requesting' | 'uploading' | 'completing' | 'processing';

export type UploadFailure = 'network' | 'rejected' | 'processing' | 'timeout' | 'other';

export type UploadState =
  | { phase: 'idle' }
  | { phase: 'running'; stage: UploadStage; percent: number }
  | { phase: 'ready'; mediaId: string }
  | { phase: 'failed'; reason: UploadFailure };

export type UploadEvent =
  | { type: 'start' }
  | { type: 'stage'; stage: UploadStage }
  | { type: 'progress'; bytesSent: number; totalBytes: number }
  | { type: 'ready'; mediaId: string }
  | { type: 'failed'; reason: UploadFailure }
  | { type: 'reset' };

export const IDLE: UploadState = { phase: 'idle' };

export function uploadReducer(state: UploadState, event: UploadEvent): UploadState {
  switch (event.type) {
    case 'start':
      return { phase: 'running', stage: 'preparing', percent: 0 };
    case 'stage':
      if (state.phase !== 'running') return state;
      return { phase: 'running', stage: event.stage, percent: event.stage === 'uploading' ? 0 : state.percent };
    case 'progress':
      if (state.phase !== 'running' || state.stage !== 'uploading') return state;
      return { ...state, percent: percentOf(event.bytesSent, event.totalBytes) };
    case 'ready':
      return { phase: 'ready', mediaId: event.mediaId };
    case 'failed':
      return { phase: 'failed', reason: event.reason };
    case 'reset':
      return IDLE;
  }
}

export function percentOf(done: number, total: number): number {
  if (!(total > 0)) return 0;
  return Math.min(100, Math.max(0, Math.floor((done / total) * 100)));
}

export function isBusy(state: UploadState): boolean {
  return state.phase === 'running';
}

const POLL_DELAYS_MS = [400, 600, 1000, 1500, 2000] as const;

export function pollDelay(attempt: number): number {
  return POLL_DELAYS_MS[Math.min(Math.max(attempt, 0), POLL_DELAYS_MS.length - 1)]!;
}

export const PROCESSING_TIMEOUT_MS = 60_000;

export type MediaStatus = 'PENDING_UPLOAD' | 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED' | 'QUARANTINED' | 'DELETED';

export function afterStatus(status: MediaStatus): 'wait' | 'ready' | UploadFailure {
  switch (status) {
    case 'READY':
      return 'ready';
    case 'QUARANTINED':
      return 'rejected';
    case 'FAILED':
    case 'DELETED':
      return 'processing';
    default:
      return 'wait';
  }
}

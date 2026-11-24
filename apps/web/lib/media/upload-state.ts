/**
 * The upload as a small state machine (pure, so it is unit-tested): request an upload URL,
 * send the bytes, ask the API to verify them, then wait for processing to finish.
 */

export type UploadStage = "requesting" | "uploading" | "completing" | "processing";

export type UploadState =
  | { phase: "idle" }
  | { phase: "running"; stage: UploadStage; percent: number }
  | { phase: "ready"; mediaId: string }
  | { phase: "failed"; reason: UploadFailure };

/** What went wrong, for the message shown to the person (the API's own message is used when it sent one). */
export type UploadFailure = "network" | "rejected" | "processing" | "timeout" | "other";

export type UploadEvent =
  | { type: "start" }
  | { type: "stage"; stage: UploadStage }
  | { type: "progress"; loaded: number; total: number }
  | { type: "ready"; mediaId: string }
  | { type: "failed"; reason: UploadFailure }
  | { type: "reset" };

export const IDLE: UploadState = { phase: "idle" };

export function uploadReducer(state: UploadState, event: UploadEvent): UploadState {
  switch (event.type) {
    case "start":
      return { phase: "running", stage: "requesting", percent: 0 };
    case "stage":
      if (state.phase !== "running") return state;
      return { phase: "running", stage: event.stage, percent: event.stage === "uploading" ? 0 : state.percent };
    case "progress":
      if (state.phase !== "running" || state.stage !== "uploading") return state;
      return { ...state, percent: percentOf(event.loaded, event.total) };
    case "ready":
      return { phase: "ready", mediaId: event.mediaId };
    case "failed":
      return { phase: "failed", reason: event.reason };
    case "reset":
      return IDLE;
  }
}

export function percentOf(loaded: number, total: number): number {
  if (!(total > 0)) return 0;
  return Math.min(100, Math.max(0, Math.floor((loaded / total) * 100)));
}

/** True while the controls must stay disabled. */
export function isBusy(state: UploadState): boolean {
  return state.phase === "running";
}

// --- waiting for processing ----------------------------------------------------------

const POLL_DELAYS_MS = [400, 600, 1000, 1500, 2000] as const;

/** How long to wait before the next status check: quick at first, then up to 2 s. */
export function pollDelay(attempt: number): number {
  return POLL_DELAYS_MS[Math.min(Math.max(attempt, 0), POLL_DELAYS_MS.length - 1)]!;
}

/** Total time spent waiting for processing before the UI says it is slow (the media may still finish). */
export const PROCESSING_TIMEOUT_MS = 60_000;

export type MediaStatus =
  | "PENDING_UPLOAD"
  | "UPLOADED"
  | "PROCESSING"
  | "READY"
  | "FAILED"
  | "QUARANTINED"
  | "DELETED";

/** What a status means for the wait: keep polling, done, or failed (and why). */
export function afterStatus(status: MediaStatus): "wait" | "ready" | UploadFailure {
  switch (status) {
    case "READY":
      return "ready";
    case "QUARANTINED":
      return "rejected";
    case "FAILED":
    case "DELETED":
      return "processing";
    default:
      return "wait";
  }
}

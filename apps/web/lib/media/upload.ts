import { isProblemDetail, type ProblemDetail } from "@school/api-client";
import { getBrowserApi } from "@/lib/api-client";
import { ApiRequestError, unwrap } from "@/lib/api-error";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import type { MediaPurpose, MediaView } from "./policies";
import {
  afterStatus,
  PROCESSING_TIMEOUT_MS,
  pollDelay,
  type MediaStatus,
  type UploadFailure,
  type UploadStage,
} from "./upload-state";
import { resolveUploadMode, uploadTarget, type UploadMode } from "./upload-mode";

/** The default is read at build time (NEXT_PUBLIC_*), so it is the same on server and client. */
const DEFAULT_MODE: UploadMode = resolveUploadMode(process.env.NEXT_PUBLIC_UPLOAD_MODE);

/** A failed upload, with the stage it failed in and the API's problem when it sent one. */
export class UploadError extends Error {
  constructor(
    readonly reason: UploadFailure,
    readonly stage: UploadStage,
    readonly problem?: ProblemDetail,
  ) {
    super(problem?.detail ?? problem?.title ?? `Upload failed (${reason}) while ${stage}`);
    this.name = "UploadError";
  }
}

export interface UploadParams {
  purpose: MediaPurpose;
  file: File;
  /** What the media belongs to; omitted for avatars (the signed-in user). */
  context?: { type: "USER" | "SCHOOL" | "ORGANIZATION"; id: string };
  onStage?: (stage: UploadStage) => void;
  onProgress?: (loaded: number, total: number) => void;
  signal?: AbortSignal;
  mode?: UploadMode;
}

/**
 * The whole upload as the browser performs it: ask the API for a presigned URL, PUT the bytes
 * (straight to storage, or through the BFF in proxy mode), have the API verify them, and wait
 * until processing finishes. Resolves with the READY media; rejects with {@link UploadError}.
 */
export async function uploadMedia(params: UploadParams): Promise<MediaView> {
  const { file, purpose, context, signal } = params;
  const api = getBrowserApi();

  params.onStage?.("requesting");
  const ticket = await apiCall("requesting", async () =>
    unwrap(
      await api.POST("/api/v1/media/uploads", {
        headers: { "Idempotency-Key": newIdempotencyKey() },
        body: { purpose, filename: file.name, mimeType: file.type, sizeBytes: file.size, context },
        signal,
      }),
    ),
  );

  params.onStage?.("uploading");
  const target = uploadTarget(params.mode ?? DEFAULT_MODE, ticket.mediaId, ticket.upload.url);
  await putBytes(target.url, file, { ...ticket.upload.headers, ...target.extraHeaders }, params.onProgress, signal);

  params.onStage?.("completing");
  await apiCall("completing", async () =>
    unwrap(await api.POST("/api/v1/media/{id}/complete", { params: { path: { id: ticket.mediaId } }, signal })),
  );

  params.onStage?.("processing");
  return waitUntilReady(ticket.mediaId, signal);
}

async function waitUntilReady(mediaId: string, signal?: AbortSignal): Promise<MediaView> {
  const api = getBrowserApi();
  const startedAt = Date.now();
  for (let attempt = 0; ; attempt++) {
    const media = await apiCall("processing", async () =>
      unwrap(await api.GET("/api/v1/media/{id}", { params: { path: { id: mediaId } }, signal })),
    );
    const next = afterStatus(media.status as MediaStatus);
    if (next === "ready") return media;
    if (next !== "wait") throw new UploadError(next, "processing");
    if (Date.now() - startedAt > PROCESSING_TIMEOUT_MS) throw new UploadError("timeout", "processing");
    await sleep(pollDelay(attempt), signal);
  }
}

/** An API call whose failure is reported as an UploadError of the given stage. */
async function apiCall<T>(stage: UploadStage, call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch (error) {
    if (error instanceof UploadError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    if (error instanceof ApiRequestError) {
      // A failed type check at completion is the one rejection people can act on.
      const rejected = error.problem?.code === "media.type_mismatch" || error.problem?.code === "media.size_mismatch";
      throw new UploadError(rejected ? "rejected" : error.status ? "other" : "network", stage, error.problem);
    }
    throw new UploadError("network", stage);
  }
}

/** XHR rather than fetch: only XHR reports upload progress. */
function putBytes(
  url: string,
  file: File,
  headers: Record<string, string>,
  onProgress: UploadParams["onProgress"],
  signal: AbortSignal | undefined,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", url);
    for (const [name, value] of Object.entries(headers)) xhr.setRequestHeader(name, value);
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.(event.loaded, event.total);
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress?.(file.size, file.size);
        resolve();
      } else {
        reject(new UploadError(xhr.status === 403 ? "rejected" : "network", "uploading", problemOf(xhr)));
      }
    };
    xhr.onerror = () => reject(new UploadError("network", "uploading"));
    xhr.ontimeout = () => reject(new UploadError("network", "uploading"));
    xhr.onabort = () => reject(new DOMException("Upload cancelled", "AbortError"));
    const abort = () => xhr.abort();
    signal?.addEventListener("abort", abort, { once: true });
    xhr.onloadend = () => signal?.removeEventListener("abort", abort);
    if (signal?.aborted) return abort();
    xhr.send(file);
  });
}

function problemOf(xhr: XMLHttpRequest): ProblemDetail | undefined {
  try {
    const body: unknown = JSON.parse(xhr.responseText);
    return isProblemDetail(body) ? body : undefined;
  } catch {
    return undefined;
  }
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener(
      "abort",
      () => {
        clearTimeout(timer);
        reject(new DOMException("Upload cancelled", "AbortError"));
      },
      { once: true },
    );
  });
}

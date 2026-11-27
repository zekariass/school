"use client";

import { ImagePlusIcon, Trash2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useId, useReducer, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { errorMessage } from "@/lib/console/errors";
import { acceptAttribute, maxMegabytes, policyFor, restrictPolicy, typeLabels, validateFile, type FileProblem, type MediaPurpose, type MediaView } from "@/lib/media/policies";
import { useMediaPolicies } from "@/lib/media/queries";
import { UploadError, uploadMedia } from "@/lib/media/upload";
import { IDLE, isBusy, uploadReducer, type UploadFailure } from "@/lib/media/upload-state";

interface ImageUploaderProps {
  purpose: MediaPurpose;
  /** What the media belongs to; omit for avatars. */
  context?: { type: "USER" | "SCHOOL" | "ORGANIZATION" | "CLASS" | "CONVERSATION"; id: string };
  hasImage: boolean;
  /** Called with the READY media; attach it to its owner (set the avatar, the logo, ...) and resolve when done. */
  onUploaded: (mediaId: string, media: MediaView) => Promise<void>;
  /** Called to remove the current image; omit to hide the remove button. */
  onRemove?: () => Promise<void>;
  labels: { choose: string; change: string; remove: string };
  disabled?: boolean;
  /** Keeps the "JPEG, PNG up to 10 MB" line for screen readers only (tight layouts such as a composer). */
  compact?: boolean;
  /** Narrows the purpose's allowed file types (e.g. a PDF-only document); the API still decides. */
  allowedMimeTypes?: readonly string[];
}

/**
 * Choose a file, validate it with the rules from `GET /media/policies`, upload it with
 * progress and hand the READY media to `onUploaded`. Shows what happens and what went wrong.
 */
export function ImageUploader({ purpose, context, hasImage, onUploaded, onRemove, labels, disabled, compact, allowedMimeTypes }: ImageUploaderProps) {
  const t = useTranslations("media");
  const inputId = useId();
  const input = useRef<HTMLInputElement>(null);
  const abort = useRef<AbortController | null>(null);
  const [state, dispatch] = useReducer(uploadReducer, IDLE);
  const [problem, setProblem] = useState<string | null>(null);
  const policies = useMediaPolicies();
  const basePolicy = policies.data ? policyFor(policies.data, purpose) : undefined;
  const policy = basePolicy && allowedMimeTypes ? restrictPolicy(basePolicy, allowedMimeTypes) : basePolicy;
  const busy = isBusy(state);

  function describe(file: FileProblem): string {
    switch (file.code) {
      case "empty":
        return t("errors.empty");
      case "type":
        return t("errors.type", { types: file.types.join(", ") });
      case "size":
        return t("errors.size", { size: file.maxMb });
    }
  }

  function describeFailure(error: unknown, reason: UploadFailure): string {
    if (error instanceof UploadError && error.problem?.detail) return error.problem.detail;
    return errorMessage(error, t(`errors.${reason === "other" ? "network" : reason}`));
  }

  async function onFile(file: File) {
    setProblem(null);
    if (!policy) {
      setProblem(t("errors.policies"));
      return;
    }
    const invalid = validateFile(file, policy);
    if (invalid) {
      setProblem(describe(invalid));
      return;
    }
    const controller = new AbortController();
    abort.current = controller;
    dispatch({ type: "start" });
    try {
      const media = await uploadMedia({
        purpose,
        file,
        context,
        signal: controller.signal,
        onStage: (stage) => dispatch({ type: "stage", stage }),
        onProgress: (loaded, total) => dispatch({ type: "progress", loaded, total }),
      });
      await onUploaded(media.id, media);
      dispatch({ type: "ready", mediaId: media.id });
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        dispatch({ type: "reset" });
        return;
      }
      const reason: UploadFailure = error instanceof UploadError ? error.reason : "other";
      dispatch({ type: "failed", reason });
      setProblem(describeFailure(error, reason));
    } finally {
      abort.current = null;
      if (input.current) input.current.value = "";
    }
  }

  async function remove() {
    if (!onRemove) return;
    setProblem(null);
    dispatch({ type: "start" });
    try {
      await onRemove();
      dispatch({ type: "reset" });
    } catch (error) {
      dispatch({ type: "failed", reason: "other" });
      setProblem(errorMessage(error, t("errors.network")));
    }
  }

  const status =
    state.phase === "running"
      ? state.stage === "uploading"
        ? t("upload.uploading", { percent: state.percent })
        : t(`upload.${state.stage === "requesting" ? "preparing" : state.stage === "completing" ? "checking" : "processing"}`)
      : null;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <input
          ref={input}
          id={inputId}
          type="file"
          className="sr-only"
          accept={policy ? acceptAttribute(policy) : "image/jpeg,image/png,image/webp"}
          disabled={disabled || busy || !policy}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) void onFile(file);
          }}
        />
        <Button type="button" variant="outline" disabled={disabled || busy || !policy} onClick={() => input.current?.click()}>
          <ImagePlusIcon aria-hidden />
          {hasImage ? labels.change : labels.choose}
        </Button>
        {hasImage && onRemove ? (
          <Button type="button" variant="ghost" disabled={disabled || busy} onClick={() => void remove()}>
            <Trash2Icon aria-hidden />
            {labels.remove}
          </Button>
        ) : null}
        {busy && state.phase === "running" && state.stage === "uploading" ? (
          <Button type="button" variant="ghost" size="sm" onClick={() => abort.current?.abort()}>
            {t("upload.cancel")}
          </Button>
        ) : null}
      </div>

      {policy ? (
        <p className={compact ? "sr-only" : "text-sm text-muted-foreground"}>
          {t("upload.accept", {
            types: typeLabels(Object.keys(policy.maxBytesByMimeType)).join(", "),
            size: maxMegabytes(policy),
          })}
        </p>
      ) : null}

      {state.phase === "running" ? (
        <div className="flex flex-col gap-1" aria-live="polite">
          <progress
            className="h-2 w-full max-w-xs overflow-hidden rounded-full accent-primary"
            aria-label={t("upload.progressLabel")}
            max={100}
            {...(state.stage === "uploading" ? { value: state.percent } : {})}
          />
          <p className="text-sm text-muted-foreground">{status}</p>
        </div>
      ) : null}

      {problem ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {problem}
        </p>
      ) : null}
    </div>
  );
}

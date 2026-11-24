import type { components } from "@school/api-client";

type S = components["schemas"];

export type MediaPolicies = S["PoliciesResponse"];
export type PurposePolicy = S["PurposePolicy"];
export type MediaView = S["MediaResponse"];
export type MediaSummary = S["MediaSummary"];
export type MediaPurpose = PurposePolicy["purpose"];

/** Why a file was refused before any upload started; the UI maps each to a translated message. */
export type FileProblem =
  | { code: "empty" }
  | { code: "type"; types: string[] }
  | { code: "size"; maxMb: number };

const MB = 1024 * 1024;

/** "JPEG, PNG, WebP" from the allowed MIME types (those of one kind, in policy order). */
export function typeLabels(mimeTypes: readonly string[]): string[] {
  const names: Record<string, string> = {
    "image/jpeg": "JPEG",
    "image/png": "PNG",
    "image/webp": "WebP",
    "image/gif": "GIF",
    "application/pdf": "PDF",
    "video/mp4": "MP4",
    "video/quicktime": "MOV",
    "video/webm": "WebM",
  };
  return mimeTypes.map((m) => names[m] ?? m);
}

/** The policy of a purpose, or undefined when the API does not offer uploads for it. */
export function policyFor(policies: MediaPolicies, purpose: MediaPurpose): PurposePolicy | undefined {
  return policies.purposes.find((p) => p.purpose === purpose);
}

/** The `accept` attribute of a file input. */
export function acceptAttribute(policy: PurposePolicy): string {
  return Object.keys(policy.maxBytesByMimeType).join(",");
}

/** The largest size (whole MB) any allowed type of the policy accepts, for a general hint. */
export function maxMegabytes(policy: PurposePolicy): number {
  return Math.floor(Math.max(...Object.values(policy.maxBytesByMimeType)) / MB);
}

/**
 * Checks a chosen file against the purpose's rules, the same ones the API enforces (the API
 * still decides). `null` means the file may be uploaded.
 */
export function validateFile(
  file: { type: string; size: number },
  policy: PurposePolicy,
): FileProblem | null {
  if (file.size <= 0) return { code: "empty" };
  const mimeType = file.type.split(";")[0]?.trim().toLowerCase() ?? "";
  const limit = policy.maxBytesByMimeType[mimeType];
  if (limit === undefined) {
    return { code: "type", types: typeLabels(Object.keys(policy.maxBytesByMimeType)) };
  }
  if (file.size > limit) return { code: "size", maxMb: Math.floor(limit / MB) };
  return null;
}

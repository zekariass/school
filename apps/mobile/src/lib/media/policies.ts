import type { components } from '@school/api-client';

type S = components['schemas'];

export type MediaPolicies = S['PoliciesResponse'];
export type PurposePolicy = S['PurposePolicy'];
export type MediaSummary = S['MediaSummary'];
export type MediaPurpose = PurposePolicy['purpose'];

export type FileProblem =
  | { code: 'empty' }
  | { code: 'type'; types: string[] }
  | { code: 'size'; maxMb: number };

const MB = 1024 * 1024;

const TYPE_NAMES: Record<string, string> = {
  'image/jpeg': 'JPEG',
  'image/png': 'PNG',
  'image/webp': 'WebP',
  'application/pdf': 'PDF',
  'video/mp4': 'MP4',
};

export function typeLabels(mimeTypes: readonly string[]): string[] {
  return mimeTypes.map((m) => TYPE_NAMES[m] ?? m);
}

export function policyFor(policies: MediaPolicies, purpose: MediaPurpose): PurposePolicy | undefined {
  return policies.purposes.find((p) => p.purpose === purpose);
}

export function maxMegabytes(policy: PurposePolicy): number {
  return Math.floor(Math.max(...Object.values(policy.maxBytesByMimeType)) / MB);
}

/**
 * Checks the file that will be uploaded against the purpose's rules (the same ones the API
 * enforces; the API still decides). `null` means it may be uploaded.
 */
export function validateFile(file: { type: string; size: number }, policy: PurposePolicy): FileProblem | null {
  if (file.size <= 0) return { code: 'empty' };
  const mimeType = file.type.split(';')[0]?.trim().toLowerCase() ?? '';
  const limit = policy.maxBytesByMimeType[mimeType];
  if (limit === undefined) return { code: 'type', types: typeLabels(Object.keys(policy.maxBytesByMimeType)) };
  if (file.size > limit) return { code: 'size', maxMb: Math.floor(limit / MB) };
  return null;
}

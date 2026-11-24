import { useQuery } from '@tanstack/react-query';

import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';

import { mediaUrlKey, urlStaleTimeMs } from './url-cache';

export type MediaVariantName = 'THUMB' | 'SMALL' | 'MEDIUM';

export const mediaPoliciesKey = ['media', 'policies'] as const;

export function useMediaPolicies() {
  return useQuery({
    queryKey: mediaPoliciesKey,
    queryFn: () => request(async () => unwrap(await api.GET('/api/v1/media/policies'))),
    staleTime: 10 * 60_000,
  });
}

/**
 * A fresh signed link for an image variant: reused for up to 4 minutes (never past its own
 * expiry) and not persisted, because it is dead after a restart.
 */
export function useMediaUrl(mediaId: string | null | undefined, variant: MediaVariantName) {
  return useQuery({
    queryKey: mediaUrlKey(mediaId ?? 'none', variant),
    queryFn: () =>
      request(async () =>
        unwrap(await api.GET('/api/v1/media/{id}/url', { params: { path: { id: mediaId! }, query: { variant } } })),
      ),
    enabled: !!mediaId,
    staleTime: (query) => urlStaleTimeMs(query.state.data?.expiresAt, Date.now()),
    gcTime: 5 * 60_000,
  });
}

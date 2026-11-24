"use client";

import { useQuery } from "@tanstack/react-query";
import { getBrowserApi } from "@/lib/api-client";
import { unwrap } from "@/lib/api-error";
import type { MediaPolicies } from "./policies";
import { mediaUrlKey, urlStaleTimeMs, type MediaVariantName } from "./url-cache";

export const mediaPoliciesKey = ["media", "policies"] as const;

export async function fetchMediaPolicies(): Promise<MediaPolicies> {
  return unwrap(await getBrowserApi().GET("/api/v1/media/policies"));
}

export function useMediaPolicies() {
  return useQuery({ queryKey: mediaPoliciesKey, queryFn: fetchMediaPolicies, staleTime: 10 * 60_000 });
}

export async function fetchMediaUrl(mediaId: string, variant: MediaVariantName) {
  return unwrap(
    await getBrowserApi().GET("/api/v1/media/{id}/url", {
      params: { path: { id: mediaId }, query: { variant } },
    }),
  );
}

/**
 * A fresh signed link for an image variant. It is reused for up to 4 minutes (and never
 * past the link's own expiry); the API lets browsers cache the response for the same time.
 */
export function useMediaUrl(mediaId: string | null | undefined, variant: MediaVariantName) {
  return useQuery({
    queryKey: mediaUrlKey(mediaId ?? "none", variant),
    queryFn: () => fetchMediaUrl(mediaId!, variant),
    enabled: !!mediaId,
    staleTime: (query) => urlStaleTimeMs(query.state.data?.expiresAt, Date.now()),
    gcTime: 5 * 60_000,
    refetchOnWindowFocus: false,
  });
}

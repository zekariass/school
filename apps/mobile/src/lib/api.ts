import Constants from 'expo-constants';

import { createApiClient } from '@school/api-client';

import { resolveApiBaseUrl } from '@/lib/api-url';
import { getCurrentLocale } from '@/lib/i18n/locale';
import { getAccessToken } from '@/lib/session/tokens';

export const API_BASE_URL = resolveApiBaseUrl({
  envUrl: process.env.EXPO_PUBLIC_API_URL,
  hostUri: Constants.expoConfig?.hostUri,
  isDev: __DEV__,
});

const REQUEST_TIMEOUT_MS = 15_000;

/** fetch with a timeout, so an unreachable host fails instead of hanging the UI. */
const fetchWithTimeout: typeof fetch = async (input, init) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    return await fetch(input, { ...init, signal: init?.signal ?? controller.signal });
  } finally {
    clearTimeout(timer);
  }
};

export const api = createApiClient({
  baseUrl: API_BASE_URL,
  getAccessToken,
  getLocale: getCurrentLocale,
  fetch: fetchWithTimeout,
});

/**
 * Minimal view of the client for endpoints that exist on the backend roadmap (M1)
 * but not yet in the generated OpenAPI types. Remove once `npm run codegen` knows
 * these paths and call `api.POST(...)` directly.
 */
interface UntypedClient {
  POST(
    path: string,
    init: { body: unknown },
  ): Promise<{ data?: unknown; error?: unknown; response: Response }>;
}

export const untypedApi = api as unknown as UntypedClient;

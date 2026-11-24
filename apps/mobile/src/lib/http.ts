import Constants from 'expo-constants';

import { createApiClient } from '@school/api-client';

import { resolveApiBaseUrl } from '@/lib/api-url';
import { getCurrentLocale } from '@/lib/i18n/locale';

export const API_BASE_URL = resolveApiBaseUrl({
  envUrl: process.env.EXPO_PUBLIC_API_URL,
  hostUri: Constants.expoConfig?.hostUri,
  isDev: __DEV__,
});

const REQUEST_TIMEOUT_MS = 15_000;

/** fetch with a timeout, so an unreachable host fails instead of hanging the UI. */
export const fetchWithTimeout: typeof fetch = async (input, init) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    return await fetch(input, { ...init, signal: init?.signal ?? controller.signal });
  } finally {
    clearTimeout(timer);
  }
};

/**
 * Client for the unauthenticated auth endpoints (OTP request/verify, refresh, logout).
 * It never attaches an access token and never refreshes, so the refresh call itself
 * cannot recurse into the refresh logic.
 */
export const authApi = createApiClient({
  baseUrl: API_BASE_URL,
  getLocale: getCurrentLocale,
  fetch: fetchWithTimeout,
});

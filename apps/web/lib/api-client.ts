import { createApiClient, type ApiClient } from "@school/api-client";

let client: ApiClient | undefined;

/**
 * Browser API client. It talks to our own origin's BFF (/api/bff/…), which
 * attaches the session token server-side, so no token is ever readable here.
 */
export function getBrowserApi(): ApiClient {
  client ??= createApiClient({
    baseUrl: `${window.location.origin}/api/bff`,
    getLocale: () => document.documentElement.lang || undefined,
  });
  return client;
}

import "server-only";
import { getLocale } from "next-intl/server";
import { createApiClient } from "@school/api-client";
import { getApiOrigin } from "./env";
import { getAccessToken } from "./session";

/**
 * Typed API client for Server Components, Server Actions and Route Handlers.
 * Talks to the API directly (no BFF hop) with the session's bearer token.
 *
 * `locale` is the Accept-Language sent upstream. In Server Components omit it
 * (next-intl resolves it); Server Actions and Route Handlers can't read root
 * params, so they pass it explicitly.
 */
export async function createServerApi(locale?: string) {
  const resolved = locale ?? (await getLocale());
  return createApiClient({
    baseUrl: getApiOrigin(),
    getAccessToken,
    getLocale: () => resolved,
  });
}

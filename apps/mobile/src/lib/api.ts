import { createApiClient } from '@school/api-client';

import { authFetch, getValidAccessToken } from '@/lib/auth/session-auth';
import { API_BASE_URL, authApi } from '@/lib/http';
import { getCurrentLocale } from '@/lib/i18n/locale';

export { API_BASE_URL, authApi };

/**
 * The authenticated client. The access token is renewed ahead of expiry and on a 401
 * (single-flight, one retry); see `lib/auth/session-auth.ts`. Auth endpoints that need no
 * token use `authApi`.
 */
export const api = createApiClient({
  baseUrl: API_BASE_URL,
  getAccessToken: getValidAccessToken,
  getLocale: getCurrentLocale,
  fetch: authFetch,
});

/**
 * Minimal view of the client for endpoints that exist on the backend roadmap but not yet
 * in the generated OpenAPI types (`/api/v1/auth/login-code`, M3). Remove once
 * `npm run codegen` knows the path and call `api.POST(...)` directly.
 */
interface UntypedClient {
  POST(
    path: string,
    init: { body: unknown },
  ): Promise<{ data?: unknown; error?: unknown; response: Response }>;
}

export const untypedApi = api as unknown as UntypedClient;

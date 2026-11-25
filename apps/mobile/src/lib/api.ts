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

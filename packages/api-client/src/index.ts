import createClient, { type Middleware } from 'openapi-fetch';

import type { components, paths } from './generated/schema';

export type { components, paths };
export type ProblemDetail = components['schemas']['ProblemDetail'];

export interface ApiClientOptions {
  /** Backend origin, e.g. "http://localhost:8080". */
  baseUrl: string;
  /**
   * Returns the current access token, or null when signed out. Web passes nothing
   * here: its BFF route handlers attach tokens server-side from httpOnly cookies.
   */
  getAccessToken?: () => string | null | Promise<string | null>;
  /** UI locale sent as Accept-Language, so server messages come back translated. */
  getLocale?: () => string | undefined;
  fetch?: typeof globalThis.fetch;
}

/**
 * Typed client for the School API, generated from the backend OpenAPI spec.
 *
 *   const api = createApiClient({ baseUrl, getAccessToken });
 *   const { data, error } = await api.GET('/api/v1/meta');
 */
export function createApiClient({ baseUrl, getAccessToken, getLocale, fetch }: ApiClientOptions) {
  const client = createClient<paths>({ baseUrl, ...(fetch ? { fetch } : {}) });

  const headers: Middleware = {
    async onRequest({ request }) {
      const token = await getAccessToken?.();
      if (token) request.headers.set('Authorization', `Bearer ${token}`);
      const locale = getLocale?.();
      if (locale) request.headers.set('Accept-Language', locale);
      return request;
    },
  };
  client.use(headers);

  return client;
}

export type ApiClient = ReturnType<typeof createApiClient>;

/** Narrow an unknown error body to an RFC 9457 problem detail. */
export function isProblemDetail(value: unknown): value is ProblemDetail {
  return (
    typeof value === 'object' &&
    value !== null &&
    ('title' in value || 'status' in value || 'code' in value)
  );
}

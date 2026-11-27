import { queryOptions } from '@tanstack/react-query';

import { request, unwrap } from '@/lib/api-error';
import { authApi } from '@/lib/http';

/**
 * `GET /meta` through the client without a token (it is public): the platform default country and the enabled
 * countries with their configuration (D42). The same answer for everyone and nothing personal, so it may sit in the
 * query cache for a while. Not the status screen's `metaQuery` (that one measures the server clock).
 */
export const countriesQuery = queryOptions({
  queryKey: ['meta', 'countries'],
  queryFn: () => request(async () => unwrap(await authApi.GET('/api/v1/meta'))),
  staleTime: 10 * 60_000,
  retry: 1,
});

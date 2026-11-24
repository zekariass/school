import { queryOptions } from '@tanstack/react-query';

import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';
import { computeClockSkewMs } from '@/lib/calendar/format-instant';

/** GET /api/v1/meta plus how far the server clock is from this device's. */
async function fetchMeta() {
  const started = Date.now();
  const meta = await request(async () => unwrap(await api.GET('/api/v1/meta')));
  const finished = Date.now();
  return { meta, skewMs: computeClockSkewMs(meta.serverTime, started, finished), roundTripMs: finished - started };
}

export const metaQuery = queryOptions({
  queryKey: ['meta'],
  queryFn: fetchMeta,
  // The status screen shows live data; settings reuses whatever is cached.
  staleTime: 0,
});

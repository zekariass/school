import { queryOptions } from "@tanstack/react-query";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";

/**
 * `GET /meta` (public): the platform default country and the enabled countries with their configuration (D42).
 * Nothing personal and no token: the same answer for everyone, so it may be kept in memory for a while.
 */
export const metaQuery = queryOptions({
  queryKey: ["meta"],
  queryFn: async () => unwrap(await getBrowserApi().GET("/api/v1/meta")),
  staleTime: 10 * 60_000,
  retry: false,
});

"use client";

import {
  useMutation,
  useQueryClient,
  type MutationFunction,
  type QueryKey,
} from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";
import { errorMessage } from "@/lib/console/errors";
import { newIdempotencyKey } from "@/lib/console/idempotency";

interface Options<TVars, TData> {
  /** Receives the variables and an `Idempotency-Key` for create endpoints. */
  mutationFn: (vars: TVars, idempotencyKey: string) => Promise<TData>;
  /** Query keys (prefixes) to refetch after success. */
  invalidate?: QueryKey[];
  successMessage?: string;
  errorFallback: string;
  /** Show a toast with the API's message on failure (off when the form shows field errors itself). */
  toastOnError?: boolean;
  onSuccess?: (data: TData, vars: TVars) => void;
}

/**
 * A mutation with an `Idempotency-Key` per submission: the key stays the same
 * while the submission is retried after a failure (so a request that did reach
 * the API is not applied twice) and is renewed after a success. Toasts the
 * outcome and refreshes the queries that depend on it.
 */
export function useSubmit<TVars, TData>(options: Options<TVars, TData>) {
  const queryClient = useQueryClient();
  const [key, setKey] = useState(newIdempotencyKey);
  const mutationFn: MutationFunction<TData, TVars> = (vars) => options.mutationFn(vars, key);

  return useMutation({
    mutationFn,
    onSuccess: async (data, vars) => {
      setKey(newIdempotencyKey());
      if (options.successMessage) toast.success(options.successMessage);
      await Promise.all(
        (options.invalidate ?? []).map((queryKey) => queryClient.invalidateQueries({ queryKey })),
      );
      options.onSuccess?.(data, vars);
    },
    onError: (error) => {
      if (options.toastOnError !== false) toast.error(errorMessage(error, options.errorFallback));
    },
  });
}

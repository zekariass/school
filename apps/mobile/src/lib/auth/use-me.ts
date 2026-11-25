import { useQuery } from '@tanstack/react-query';

import { loadMe } from './auth-requests';

export const ME_KEY = ['me'] as const;

/** The signed-in user's profile (`GET /me`), seeded at sign-in and kept in the offline cache. */
export function useMe() {
  return useQuery({ queryKey: ME_KEY, queryFn: loadMe });
}

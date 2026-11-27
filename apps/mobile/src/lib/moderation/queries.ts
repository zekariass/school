import { onlineManager, useQuery } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';

import { useMe } from '@/lib/auth/use-me';

import { emergencyCountry, emergencyText } from './emergency';
import { MODERATION_GC_MS, emergencyInfoKey } from './keys';
import { fetchEmergencyInfo } from './requests';

/**
 * The safety text for the person and (when known) the school of what they are reporting, as the API wrote it. Asked for only while
 * a report sheet is open (`enabled`); memory only (`moderation` root), dropped after five minutes and at sign-out. `null` while it
 * loads, when the country has no text, and when the request fails: the sheet then simply shows no safety line.
 */
export function useSafetyText(schoolId: string | null | undefined, enabled: boolean): string | null {
  const me = useMe();
  const country = emergencyCountry(me.data, schoolId);
  const query = useQuery({
    queryKey: emergencyInfoKey(country ?? 'none'),
    queryFn: () => fetchEmergencyInfo(country!),
    enabled: enabled && !!country,
    staleTime: MODERATION_GC_MS,
    gcTime: MODERATION_GC_MS,
    retry: false,
  });
  return emergencyText(query.data);
}

/** Whether the phone has a connection (a report is never queued: it needs one). */
export function useIsOnline(): boolean {
  return useSyncExternalStore(
    (listener) => onlineManager.subscribe(listener),
    () => onlineManager.isOnline(),
    () => true,
  );
}

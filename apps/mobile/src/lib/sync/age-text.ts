import { useCallback } from 'react';
import { useTranslations } from 'use-intl';

import { ageOf } from '@/lib/attendance/roll-model';

/** "Updated 3 h ago": how old the phone's copy of something is. */
export function useAgeText(): (fetchedAt: number, now: number) => string {
  const t = useTranslations();
  return useCallback(
    (fetchedAt, now) => {
      const age = ageOf(fetchedAt, now);
      switch (age.unit) {
        case 'now':
          return t('mobile.sync.age.now');
        case 'minutes':
          return t('mobile.sync.age.minutes', { count: age.value });
        case 'hours':
          return t('mobile.sync.age.hours', { count: age.value });
        default:
          return t('mobile.sync.age.days', { count: age.value });
      }
    },
    [t],
  );
}

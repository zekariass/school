import { getCalendars } from 'expo-localization';
import { useMemo } from 'react';
import { useTranslations } from 'use-intl';

import { useMe } from '@/lib/auth/use-me';
import { useClock } from '@/lib/feed/use-clock';
import { DISPLAY_TIME_ZONE } from '@/lib/i18n/locale';
import { usePreferences } from '@/lib/preferences/preferences-provider';

import type { IsoDate } from './civil';
import type { LabelContext, Translate } from './labels';
import { dateIn, usableZone } from './zone';

/** The phone's zone. Used ONLY to say when an event's zone differs from it, never to read a time. */
export function useDeviceZone(): string | null {
  try {
    return getCalendars()[0]?.timeZone ?? null;
  } catch {
    return null;
  }
}

/**
 * The zone that decides what "today" is: the account's own (`GET /me` timezone), else the app's display zone. Explicit either
 * way; the phone's setting does not change which day the calendar opens on.
 */
export function useTodayZone(): string {
  const me = useMe();
  return usableZone(me.data?.timezone ?? DISPLAY_TIME_ZONE);
}

export function useToday(): IsoDate {
  const zone = useTodayZone();
  const now = useClock(60_000);
  return dateIn(now, zone);
}

/** The translator for keys chosen at run time (kind, answer and problem names): same catalog, keys not checked by the compiler. */
export function useLooseT(): Translate {
  return useTranslations() as unknown as Translate;
}

export function useLabelContext(): LabelContext {
  const t = useTranslations();
  const { locale, calendar } = usePreferences();
  const deviceZone = useDeviceZone();
  return useMemo(() => ({ t: t as unknown as Translate, locale, calendar, deviceZone }), [t, locale, calendar, deviceZone]);
}

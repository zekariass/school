import type { ReactNode } from 'react';
import { IntlProvider } from 'use-intl';

import { messages } from '@school/i18n';

import { DISPLAY_TIME_ZONE } from '@/lib/i18n/locale';
import { usePreferences } from '@/lib/preferences/preferences-provider';

/** ICU message catalogs from @school/i18n through use-intl (the engine behind next-intl on the web). */
export function AppIntlProvider({ children }: { children: ReactNode }) {
  const { locale } = usePreferences();
  return (
    <IntlProvider locale={locale} messages={messages[locale]} timeZone={DISPLAY_TIME_ZONE}>
      {children}
    </IntlProvider>
  );
}

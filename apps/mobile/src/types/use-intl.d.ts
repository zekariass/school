import type { Locale, Messages } from '@school/i18n';

// Type-checks translation keys and ICU argument names against the shared catalog.
declare module 'use-intl' {
  interface AppConfig {
    Locale: Locale;
    Messages: Messages;
  }
}

import am from '../messages/am.json';
import en from '../messages/en.json';
import type { Locale } from './locales';

export * from './locales';

/**
 * Message catalogs in ICU MessageFormat (plural/select syntax), consumed by the
 * web (next-intl) and mobile (i18next + ICU) runtimes. English is the source of
 * truth for keys; every other catalog must have exactly the same keys and
 * placeholders (enforced by catalogs.test.ts).
 *
 * Amharic strings are a first draft pending native-speaker review.
 */
export type Messages = typeof en;

export const messages: Record<Locale, Messages> = { en, am };

/** Dot-separated key of every leaf message, e.g. "auth.signIn". */
export type MessageKey = LeafKeys<Messages>;

type LeafKeys<T, Prefix extends string = ''> = {
  [K in keyof T & string]: T[K] extends string ? `${Prefix}${K}` : LeafKeys<T[K], `${Prefix}${K}.`>;
}[keyof T & string];

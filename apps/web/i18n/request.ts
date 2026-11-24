import { notFound } from "next/navigation";
import * as rootParams from "next/root-params";
import { hasLocale } from "next-intl";
import { getRequestConfig } from "next-intl/server";
import { messages } from "@school/i18n";
import { routing } from "./routing";

export default getRequestConfig(async ({ locale: override }) => {
  // `next/root-params` only works in Server Components; an explicit locale
  // (Server Actions, Route Handlers) arrives through `override`.
  const candidate = override ?? (await rootParams.locale());
  if (!hasLocale(routing.locales, candidate)) {
    notFound();
  }
  return { locale: candidate, messages: messages[candidate] };
});

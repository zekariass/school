import type { Metadata } from "next";
import { Nunito, Noto_Sans_Ethiopic } from "next/font/google";
import { notFound } from "next/navigation";
import { locale as getRootLocale } from "next/root-params";
import { hasLocale, NextIntlClientProvider } from "next-intl";
import { getTranslations } from "next-intl/server";
import { SUPPORTED_LOCALES } from "@school/i18n";
import { Providers } from "@/components/providers";
import { routing } from "@/i18n/routing";
import { THEME_INIT_SCRIPT } from "@/lib/theme";
import "../globals.css";

const nunito = Nunito({
  variable: "--font-nunito",
  subsets: ["latin", "latin-ext"],
  display: "swap",
});

const notoSansEthiopic = Noto_Sans_Ethiopic({
  variable: "--font-ethiopic",
  subsets: ["ethiopic"],
  display: "swap",
});

export function generateStaticParams() {
  return routing.locales.map((locale) => ({ locale }));
}

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("app");
  const name = t("name");
  return {
    title: { default: name, template: `%s | ${name}` },
    description: (await getTranslations("marketing"))("valueProposition"),
  };
}

export default async function RootLayout({ children }: LayoutProps<"/[locale]">) {
  const locale = await getRootLocale();
  if (!hasLocale(routing.locales, locale)) notFound();
  const dir = SUPPORTED_LOCALES.find((l) => l.code === locale)?.dir ?? "ltr";

  return (
    <html
      lang={locale}
      dir={dir}
      className={`${nunito.variable} ${notoSansEthiopic.variable} h-full`}
      suppressHydrationWarning
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
      </head>
      <body className="flex min-h-full flex-col">
        <NextIntlClientProvider>
          <Providers>{children}</Providers>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}

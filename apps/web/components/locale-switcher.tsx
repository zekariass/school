"use client";

import { LanguagesIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { SUPPORTED_LOCALES } from "@school/i18n";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Link, usePathname } from "@/i18n/navigation";
import { cn } from "@/lib/utils";

export function LocaleSwitcher() {
  const t = useTranslations("locale");
  const locale = useLocale();
  const pathname = usePathname();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="h-11 gap-2 rounded-xl px-3" aria-label={t("label")}>
          <LanguagesIcon className="size-5" aria-hidden />
          <span className="uppercase">{locale}</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-40 rounded-xl p-1.5">
        {SUPPORTED_LOCALES.map((l) => (
          <DropdownMenuItem key={l.code} asChild className="min-h-10 rounded-lg">
            <Link
              href={pathname}
              locale={l.code}
              lang={l.code}
              hrefLang={l.code}
              className={cn(l.code === locale && "font-semibold")}
              aria-current={l.code === locale ? "true" : undefined}
            >
              {l.nativeName}
            </Link>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

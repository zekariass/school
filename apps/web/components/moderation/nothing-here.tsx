"use client";

import { SearchXIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Link } from "@/i18n/navigation";

/**
 * What a person sees where the API answered 404, and what a person without the permission for a safeguarding page sees:
 * the same words as an unknown page. It never says "you may not see this" and never hints that something exists, because
 * for incidents (and for cases a person may not work) the API itself answers as if nothing existed.
 */
export function NothingHere({ homeHref = "/" }: { homeHref?: string }) {
  const t = useTranslations("errors");
  return (
    <Card data-testid="nothing-here">
      <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
        <span className="grid size-14 place-items-center rounded-2xl bg-muted text-muted-foreground">
          <SearchXIcon className="size-7" aria-hidden />
        </span>
        <h1 className="text-xl font-bold">{t("notFoundTitle")}</h1>
        <p className="max-w-sm text-muted-foreground">{t("notFoundBody")}</p>
        <Button asChild>
          <Link href={homeHref}>{t("goHome")}</Link>
        </Button>
      </CardContent>
    </Card>
  );
}

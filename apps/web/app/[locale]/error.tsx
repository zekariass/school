"use client";

import { useEffect } from "react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";

export default function ErrorPage({ error, retry }: { error: Error & { digest?: string }; retry: () => void }) {
  const t = useTranslations();

  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-4 px-4 text-center">
      <h1 className="text-2xl font-bold">{t("errors.title")}</h1>
      <p className="text-muted-foreground">{t("errors.body")}</p>
      <div className="flex flex-wrap justify-center gap-3">
        <Button size="lg" onClick={() => retry()}>
          {t("common.retry")}
        </Button>
        <Button asChild size="lg" variant="outline">
          <Link href="/">{t("errors.goHome")}</Link>
        </Button>
      </div>
    </main>
  );
}

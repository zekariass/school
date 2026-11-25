"use client";

import { LockKeyholeIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Card, CardContent } from "@/components/ui/card";
import { useSchool } from "./school-context";

/**
 * Shows `children` when the user holds one of the permissions, otherwise a friendly
 * "not available to you" notice. Navigation already hides the link; this covers typed URLs.
 * It is a convenience: every API call is checked on the server.
 */
export function RequirePermission({
  anyOf,
  children,
}: {
  anyOf: readonly string[];
  children: React.ReactNode;
}) {
  const t = useTranslations("school.common");
  const { permissions } = useSchool();
  if (anyOf.some((p) => permissions.has(p))) return <>{children}</>;
  return (
    <Card>
      <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
        <span className="grid size-14 place-items-center rounded-2xl bg-muted text-muted-foreground">
          <LockKeyholeIcon className="size-7" aria-hidden />
        </span>
        <h1 className="text-xl font-bold">{t("noAccessTitle")}</h1>
        <p className="max-w-sm text-muted-foreground">{t("noAccessBody")}</p>
      </CardContent>
    </Card>
  );
}

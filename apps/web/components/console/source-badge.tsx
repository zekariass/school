"use client";

import { useTranslations } from "next-intl";
import { Badge } from "@/components/ui/badge";

/** Which level of the hierarchy a configuration value comes from. */
export function SourceBadge({ source }: { source: string }) {
  const t = useTranslations("console.source");
  const known = ["SCHOOL", "ORGANIZATION", "COUNTRY", "PLATFORM", "DEFAULT"].includes(source);
  return (
    <Badge variant={source === "DEFAULT" ? "outline" : "secondary"}>
      {known ? t(source as "SCHOOL") : source}
    </Badge>
  );
}

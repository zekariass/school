"use client";

import { useTranslations } from "next-intl";
import { Badge } from "@/components/ui/badge";

type Known = "ONBOARDING" | "ACTIVE" | "SUSPENDED" | "ARCHIVED";

const VARIANTS: Record<Known, "default" | "secondary" | "destructive" | "outline"> = {
  ACTIVE: "default",
  ONBOARDING: "secondary",
  SUSPENDED: "destructive",
  ARCHIVED: "outline",
};

/** Status of a school, campus or organization. */
export function StatusBadge({ status }: { status: string }) {
  const t = useTranslations("console.status");
  const known = status in VARIANTS;
  return <Badge variant={known ? VARIANTS[status as Known] : "outline"}>{known ? t(status as Known) : status}</Badge>;
}

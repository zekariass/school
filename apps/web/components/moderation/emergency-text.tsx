"use client";

import { LifeBuoyIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import type { EmergencyInfo } from "@/lib/moderation/types";

/**
 * The safety text of a country exactly as the API sent it (`GET /safety/emergency-info`): this app never writes
 * emergency wording or numbers itself, and shows nothing for a country the API has no text for.
 */
export function EmergencyText({ info }: { info: EmergencyInfo }) {
  const t = useTranslations("moderation.emergency");
  return (
    <aside className="flex items-start gap-3 rounded-2xl border-2 border-primary/40 bg-primary/5 p-4" aria-label={t("title")} data-testid="emergency-info">
      <LifeBuoyIcon className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
      <div className="flex flex-col gap-1">
        <p className="font-semibold">{t("title")}</p>
        <p className="whitespace-pre-wrap [overflow-wrap:anywhere]">{info.text}</p>
      </div>
    </aside>
  );
}

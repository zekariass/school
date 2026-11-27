import { getTranslations } from "next-intl/server";
import { IncidentsView } from "@/components/safeguarding/incidents-view";
import { SchoolModerationScope } from "@/components/moderation/scope";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("safeguarding") };
}

export default function SchoolSafeguardingPage() {
  return (
    <SchoolModerationScope>
      <IncidentsView />
    </SchoolModerationScope>
  );
}

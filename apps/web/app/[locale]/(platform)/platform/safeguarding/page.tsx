import { getTranslations } from "next-intl/server";
import { IncidentsView } from "@/components/safeguarding/incidents-view";
import { PlatformModerationScope } from "@/components/moderation/scope";

export async function generateMetadata() {
  const t = await getTranslations("console.nav");
  return { title: t("safeguarding") };
}

export default function PlatformSafeguardingPage() {
  return (
    <PlatformModerationScope>
      <IncidentsView />
    </PlatformModerationScope>
  );
}

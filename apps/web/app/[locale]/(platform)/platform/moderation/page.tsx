import { getTranslations } from "next-intl/server";
import { QueueView } from "@/components/moderation/queue-view";
import { PlatformModerationScope } from "@/components/moderation/scope";

export async function generateMetadata() {
  const t = await getTranslations("console.nav");
  return { title: t("moderation") };
}

export default function PlatformModerationPage() {
  return (
    <PlatformModerationScope>
      <QueueView />
    </PlatformModerationScope>
  );
}

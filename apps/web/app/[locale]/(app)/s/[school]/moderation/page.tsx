import { getTranslations } from "next-intl/server";
import { QueueView } from "@/components/moderation/queue-view";
import { SchoolModerationScope } from "@/components/moderation/scope";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("moderation") };
}

export default function SchoolModerationPage() {
  return (
    <SchoolModerationScope>
      <QueueView />
    </SchoolModerationScope>
  );
}

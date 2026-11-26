import { getTranslations } from "next-intl/server";
import { ComposerView } from "@/components/announcements/composer-view";

export async function generateMetadata() {
  const t = await getTranslations("school.announcements.composer");
  return { title: t("newTitle") };
}

export default function NewAnnouncementPage() {
  return <ComposerView />;
}

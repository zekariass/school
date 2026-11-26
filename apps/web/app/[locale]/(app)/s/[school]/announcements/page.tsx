import { getTranslations } from "next-intl/server";
import { SchoolAnnouncementsView } from "@/components/announcements/school-announcements-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("announcements") };
}

export default function SchoolAnnouncementsPage() {
  return <SchoolAnnouncementsView />;
}

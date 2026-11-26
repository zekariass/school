import { getTranslations } from "next-intl/server";
import { SchoolFeedView } from "@/components/feed/school-feed-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("feed") };
}

export default function SchoolFeedPage() {
  return <SchoolFeedView />;
}

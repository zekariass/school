import { getTranslations } from "next-intl/server";
import { SchoolSettingsView } from "@/components/school/school-settings";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("settings") };
}

export default function SettingsPage() {
  return <SchoolSettingsView />;
}

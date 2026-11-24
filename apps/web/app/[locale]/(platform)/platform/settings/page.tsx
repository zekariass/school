import { getTranslations } from "next-intl/server";
import { SettingsView } from "@/components/console/settings-view";

export async function generateMetadata() {
  const t = await getTranslations("console.nav");
  return { title: t("settings") };
}

export default function SettingsPage() {
  return <SettingsView />;
}

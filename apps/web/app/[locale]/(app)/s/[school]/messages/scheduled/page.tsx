import { getTranslations } from "next-intl/server";
import { ScheduledView } from "@/components/messaging/scheduled-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("messaging") };
}

export default function Page() {
  return <ScheduledView />;
}

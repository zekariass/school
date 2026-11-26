import { getTranslations } from "next-intl/server";
import { BlockedView } from "@/components/messaging/blocked-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("messaging") };
}

export default function Page() {
  return <BlockedView />;
}

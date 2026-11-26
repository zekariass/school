import { getTranslations } from "next-intl/server";
import { InboxView } from "@/components/messaging/inbox-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("messaging") };
}

export default function Page() {
  return <InboxView />;
}

import { getTranslations } from "next-intl/server";
import { NewConversationView } from "@/components/messaging/new-conversation-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("messaging") };
}

export default function Page() {
  return <NewConversationView />;
}

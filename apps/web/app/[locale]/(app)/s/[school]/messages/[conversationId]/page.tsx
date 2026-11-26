import { getTranslations } from "next-intl/server";
import { ConversationRoute } from "@/components/messaging/conversation-route";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("messaging") };
}

export default function Page({ params }: PageProps<"/[locale]/s/[school]/messages/[conversationId]">) {
  return <ConversationRoute params={params} />;
}

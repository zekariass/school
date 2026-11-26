import { getTranslations } from "next-intl/server";
import { GroupsView } from "@/components/messaging/groups-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("messaging") };
}

export default function Page() {
  return <GroupsView />;
}

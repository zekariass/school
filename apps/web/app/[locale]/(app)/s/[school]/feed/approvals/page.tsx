import { getTranslations } from "next-intl/server";
import { ApprovalsView } from "@/components/feed/approvals-view";

export async function generateMetadata() {
  const t = await getTranslations("school.feed.approvals");
  return { title: t("title") };
}

export default function ApprovalsPage() {
  return <ApprovalsView />;
}

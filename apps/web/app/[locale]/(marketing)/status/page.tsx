import { getTranslations } from "next-intl/server";
import { StatusView } from "./status-view";

export async function generateMetadata() {
  const t = await getTranslations("status");
  return { title: t("title") };
}

export default function StatusPage() {
  return <StatusView />;
}

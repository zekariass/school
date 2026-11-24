import { getTranslations } from "next-intl/server";
import { SchoolsList } from "@/components/console/schools-list";

export async function generateMetadata() {
  const t = await getTranslations("console.nav");
  return { title: t("schools") };
}

export default function SchoolsPage() {
  return <SchoolsList />;
}

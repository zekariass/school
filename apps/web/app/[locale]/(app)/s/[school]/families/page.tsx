import { getTranslations } from "next-intl/server";
import { FamiliesView } from "@/components/school/families-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("families") };
}

export default function FamiliesPage() {
  return <FamiliesView />;
}

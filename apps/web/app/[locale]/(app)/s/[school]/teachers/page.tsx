import { getTranslations } from "next-intl/server";
import { TeachersView } from "@/components/school/teachers-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("teachers") };
}

export default function TeachersPage() {
  return <TeachersView />;
}

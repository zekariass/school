import { getTranslations } from "next-intl/server";
import { ClassesView } from "@/components/school/classes-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("classes") };
}

export default function ClassesPage() {
  return <ClassesView />;
}

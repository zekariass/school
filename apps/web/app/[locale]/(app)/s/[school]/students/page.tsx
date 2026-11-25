import { getTranslations } from "next-intl/server";
import { StudentsView } from "@/components/school/students-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("students") };
}

export default function StudentsPage() {
  return <StudentsView />;
}

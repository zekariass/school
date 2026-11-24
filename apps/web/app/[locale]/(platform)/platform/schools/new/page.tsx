import { getTranslations } from "next-intl/server";
import { SchoolForm } from "@/components/console/school-form";

export async function generateMetadata() {
  const t = await getTranslations("console.schools");
  return { title: t("create") };
}

export default function NewSchoolPage() {
  return <SchoolForm />;
}

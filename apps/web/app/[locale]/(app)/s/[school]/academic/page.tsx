import { getTranslations } from "next-intl/server";
import { AcademicView } from "@/components/school/academic-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("academic") };
}

export default function AcademicPage() {
  return <AcademicView />;
}

import { getTranslations } from "next-intl/server";
import { ImportNew } from "@/components/school/import/import-new";

export async function generateMetadata() {
  const t = await getTranslations("school.import.new");
  return { title: t("title") };
}

export default function NewImportPage() {
  return <ImportNew />;
}

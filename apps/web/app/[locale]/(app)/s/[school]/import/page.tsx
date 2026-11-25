import { getTranslations } from "next-intl/server";
import { ImportHistory } from "@/components/school/import/import-history";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("import") };
}

export default function ImportPage() {
  return <ImportHistory />;
}

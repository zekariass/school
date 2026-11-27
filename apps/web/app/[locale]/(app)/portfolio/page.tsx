import { getTranslations } from "next-intl/server";
import { StudentPortfolioPage } from "@/components/portfolio/student-shell";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("portfolio") };
}

/** A student's own portfolio. The document title never carries an item's title. */
export default function PortfolioPage() {
  return <StudentPortfolioPage />;
}

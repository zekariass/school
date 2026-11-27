import { getTranslations } from "next-intl/server";
import { SchoolPortfolioView } from "@/components/portfolio/school-portfolio-view";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("portfolio") };
}

export default function SchoolPortfolioPage() {
  return <SchoolPortfolioView />;
}

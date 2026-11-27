import { getTranslations } from "next-intl/server";
import { FamilyShell } from "@/components/family/family-shell";
import { FamilyPortfolio } from "@/components/portfolio/family-portfolio";

export async function generateMetadata() {
  const t = await getTranslations("family.portfolio");
  return { title: t("title") };
}

/** Everything the guardian may see of their children's portfolios. */
export default function FamilyPortfolioPage() {
  return (
    <FamilyShell>
      <FamilyPortfolio />
    </FamilyShell>
  );
}

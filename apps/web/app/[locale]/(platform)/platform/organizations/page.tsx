import { getTranslations } from "next-intl/server";
import { OrganizationsView } from "@/components/console/organizations-view";

export async function generateMetadata() {
  const t = await getTranslations("console.nav");
  return { title: t("organizations") };
}

export default function OrganizationsPage() {
  return <OrganizationsView />;
}

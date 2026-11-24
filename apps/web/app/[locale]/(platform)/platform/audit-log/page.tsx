import { getTranslations } from "next-intl/server";
import { AuditView } from "@/components/console/audit-view";

export async function generateMetadata() {
  const t = await getTranslations("console.nav");
  return { title: t("audit") };
}

export default function AuditLogPage() {
  return <AuditView />;
}

import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { ParentCodePrintView } from "@/components/school/parent-code-print";
import { Skeleton } from "@/components/ui/skeleton";

export async function generateMetadata() {
  const t = await getTranslations("school.families.code");
  return { title: t("label") };
}

export default function ParentCodePrintPage({
  params,
}: PageProps<"/[locale]/s/[school]/classes/[classId]/parent-codes/print">) {
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Sheet params={params} />
    </Suspense>
  );
}

async function Sheet({
  params,
}: {
  params: PageProps<"/[locale]/s/[school]/classes/[classId]/parent-codes/print">["params"];
}) {
  const { classId } = await params;
  return <ParentCodePrintView classId={classId} />;
}

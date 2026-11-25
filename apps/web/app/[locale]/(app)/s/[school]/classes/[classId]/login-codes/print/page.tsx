import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { PrintSheetView } from "@/components/school/print-sheet";
import { Skeleton } from "@/components/ui/skeleton";

export async function generateMetadata() {
  const t = await getTranslations("school.print");
  return { title: t("title") };
}

export default function PrintPage({ params }: PageProps<"/[locale]/s/[school]/classes/[classId]/login-codes/print">) {
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Sheet params={params} />
    </Suspense>
  );
}

async function Sheet({
  params,
}: {
  params: PageProps<"/[locale]/s/[school]/classes/[classId]/login-codes/print">["params"];
}) {
  const { classId } = await params;
  return <PrintSheetView classId={classId} />;
}

import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { ImportJobScreen } from "@/components/school/import/import-job";
import { Skeleton } from "@/components/ui/skeleton";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("import") };
}

export default function ImportJobPage({ params }: PageProps<"/[locale]/s/[school]/import/[importId]">) {
  // `importId` is runtime data, so it is read inside Suspense (cacheComponents).
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Job params={params} />
    </Suspense>
  );
}

async function Job({ params }: { params: PageProps<"/[locale]/s/[school]/import/[importId]">["params"] }) {
  const { importId } = await params;
  return <ImportJobScreen jobId={importId} />;
}

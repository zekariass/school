import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { StudentDetail } from "@/components/school/student-detail";
import { Skeleton } from "@/components/ui/skeleton";

export async function generateMetadata() {
  const t = await getTranslations("nav");
  return { title: t("students") };
}

export default function StudentDetailPage({ params }: PageProps<"/[locale]/s/[school]/students/[studentId]">) {
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Detail params={params} />
    </Suspense>
  );
}

async function Detail({ params }: { params: PageProps<"/[locale]/s/[school]/students/[studentId]">["params"] }) {
  const { studentId } = await params;
  return <StudentDetail studentId={studentId} />;
}

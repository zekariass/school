import { Suspense } from "react";
import { SchoolShell, SchoolShellSkeleton } from "@/components/app-shell/school-shell";

export default function SchoolLayout({ children, params }: LayoutProps<"/[locale]/s/[school]">) {
  // `school` is runtime data (not in generateStaticParams), so it's read inside
  // Suspense: the static shell prerenders, the workspace streams in.
  return (
    <Suspense fallback={<SchoolShellSkeleton />}>
      <ShellWithParams params={params}>{children}</ShellWithParams>
    </Suspense>
  );
}

async function ShellWithParams({
  params,
  children,
}: {
  params: LayoutProps<"/[locale]/s/[school]">["params"];
  children: React.ReactNode;
}) {
  const { school } = await params;
  return <SchoolShell school={school}>{children}</SchoolShell>;
}

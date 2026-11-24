import { Suspense } from "react";
import { SchoolShell, SchoolShellSkeleton } from "@/components/app-shell/school-shell";
import { getSchoolBySlug, requireSession } from "@/lib/server/dal";
import { roleNamesForSchool } from "@/lib/roles";

export default function SchoolLayout({ children, params }: LayoutProps<"/[locale]/s/[school]">) {
  // `school` is runtime data (not in generateStaticParams), so it's read inside
  // Suspense: the static shell prerenders, the workspace streams in.
  return (
    <Suspense fallback={<SchoolShellSkeleton />}>
      <Workspace params={params}>{children}</Workspace>
    </Suspense>
  );
}

async function Workspace({
  params,
  children,
}: {
  params: LayoutProps<"/[locale]/s/[school]">["params"];
  children: React.ReactNode;
}) {
  const { school: slug } = await params;
  const me = await requireSession();
  // 404 for unknown slugs and for schools this user may not read (same answer).
  const school = await getSchoolBySlug(slug);
  const roles = roleNamesForSchool(me.roleAssignments, school.slug);
  return (
    <SchoolShell
      slug={school.slug}
      schoolName={school.name}
      user={{ name: me.displayName, detail: roles.join(", ") || undefined }}
    >
      {children}
    </SchoolShell>
  );
}

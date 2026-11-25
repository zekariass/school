import { redirect } from "next/navigation";
import { getLocale, getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { SchoolShell, SchoolShellSkeleton } from "@/components/app-shell/school-shell";
import { PointsProvider } from "@/components/points/points-provider";
import { IssuedCodesProvider } from "@/components/school/issued-codes-context";
import { SchoolProvider } from "@/components/school/school-context";
import { roleNamesForSchool } from "@/lib/roles";
import { isStudentOnly, navItems, schoolAccess } from "@/lib/school/access";
import { getWorkspaceSchool, requireSession } from "@/lib/server/dal";

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
  // 404 for unknown slugs and for schools this user has no part in (same answer).
  const school = await getWorkspaceSchool(slug);
  const access = schoolAccess(me, school.slug);
  // Students use the mobile app: there is no web workspace for them.
  if (isStudentOnly(access)) redirect(`/${await getLocale()}/student-app`);

  const tRoles = await getTranslations("roles");
  const roles = roleNamesForSchool(me.roleAssignments, school.slug);
  if (access.isTeacher && !roles.length) roles.push(tRoles("teacher"));

  return (
    <SchoolProvider
      school={{ id: school.id, slug: school.slug, name: school.name }}
      userId={me.id}
      permissions={[...access.permissions].sort()}
      isTeacher={access.isTeacher}
      isStaff={access.isStaff}
    >
      <IssuedCodesProvider>
        <PointsProvider>
        <SchoolShell
          slug={school.slug}
          schoolName={school.name}
          items={navItems(access)}
          user={{ name: me.displayName, detail: roles.join(", ") || undefined }}
        >
          {children}
        </SchoolShell>
        </PointsProvider>
      </IssuedCodesProvider>
    </SchoolProvider>
  );
}

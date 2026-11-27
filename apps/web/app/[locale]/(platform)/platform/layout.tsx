import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { PlatformShell, PlatformShellSkeleton } from "@/components/console/platform-shell";
import { userPlaces } from "@school/validation/country";
import { UserPlaceProvider } from "@/components/display-place";
import { hasConsoleAccess } from "@/lib/post-sign-in";
import { permissionsOf, requireSession } from "@/lib/server/dal";

export default function PlatformLayout({ children }: LayoutProps<"/[locale]/platform">) {
  // The session check reads cookies, so it streams in behind Suspense.
  return (
    <Suspense fallback={<PlatformShellSkeleton />}>
      <PlatformGate>{children}</PlatformGate>
    </Suspense>
  );
}

async function PlatformGate({ children }: { children: React.ReactNode }) {
  const me = await requireSession();
  // People without an administrative role above school level don't learn the console exists.
  if (!hasConsoleAccess(me)) notFound();
  const t = await getTranslations("nav");
  const roles = [...new Set(me.roleAssignments.map((a) => a.roleName))];
  const { place, schools, preference } = userPlaces(me);
  return (
    <UserPlaceProvider place={place} schools={schools} preference={preference}>
      <PlatformShell
        user={{ name: me.displayName, detail: roles.join(", ") || undefined }}
        permissions={[...permissionsOf(me)].sort()}
        userId={me.id}
        skipLabel={t("skipToContent")}
      >
        {children}
      </PlatformShell>
    </UserPlaceProvider>
  );
}

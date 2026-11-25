import { useTranslations } from "next-intl";
import { Brand } from "@/components/brand";
import { HeaderControls } from "@/components/header-controls";
import type { NavId } from "@/lib/school/access";
import { SidebarNav } from "./sidebar-nav";

export function SchoolShell({
  slug,
  schoolName,
  items,
  user,
  children,
}: {
  slug: string;
  schoolName: string;
  items: readonly NavId[];
  user: { name: string; detail?: string };
  children: React.ReactNode;
}) {
  const t = useTranslations("nav");
  return (
    <div className="flex min-h-dvh print:block">
      <a
        href="#main"
        className="print:hidden sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:rounded-xl focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-foreground"
      >
        {t("skipToContent")}
      </a>
      <aside className="hidden w-64 shrink-0 flex-col gap-6 border-e bg-card p-4 md:flex print:hidden">
        <Brand />
        <SidebarNav school={slug} items={items} orientation="vertical" />
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-10 border-b bg-background/90 backdrop-blur print:hidden">
          <div className="flex h-16 items-center justify-between gap-3 px-4">
            <div className="md:hidden">
              <Brand />
            </div>
            <p className="hidden truncate text-sm font-semibold text-muted-foreground md:block">
              {schoolName}
            </p>
            <HeaderControls user={user} />
          </div>
          <div className="border-t md:hidden">
            <SidebarNav school={slug} items={items} orientation="horizontal" />
          </div>
        </header>
        <main id="main" className="flex-1 p-4 md:p-8 print:p-0">
          {children}
        </main>
      </div>
    </div>
  );
}

export function SchoolShellSkeleton() {
  return (
    <div className="flex min-h-dvh" aria-hidden>
      <aside className="hidden w-64 shrink-0 border-e bg-card md:block" />
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="h-16 border-b" />
      </div>
    </div>
  );
}

import { useTranslations } from "next-intl";
import { Brand } from "@/components/brand";
import { HeaderControls } from "@/components/header-controls";
import { SidebarNav } from "./sidebar-nav";

export function SchoolShell({ school, children }: { school: string; children: React.ReactNode }) {
  const t = useTranslations("nav");
  return (
    <div className="flex min-h-dvh">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:rounded-xl focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-foreground"
      >
        {t("skipToContent")}
      </a>
      <aside className="hidden w-64 shrink-0 flex-col gap-6 border-e bg-card p-4 md:flex">
        <Brand />
        <SidebarNav school={school} orientation="vertical" />
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-10 border-b bg-background/90 backdrop-blur">
          <div className="flex h-16 items-center justify-between gap-3 px-4">
            <div className="md:hidden">
              <Brand />
            </div>
            <p className="hidden truncate text-sm font-semibold text-muted-foreground md:block">
              {school}
            </p>
            <HeaderControls />
          </div>
          <div className="border-t md:hidden">
            <SidebarNav school={school} orientation="horizontal" />
          </div>
        </header>
        <main id="main" className="flex-1 p-4 md:p-8">
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

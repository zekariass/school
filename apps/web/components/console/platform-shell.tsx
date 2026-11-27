import { Brand } from "@/components/brand";
import { HeaderControls } from "@/components/header-controls";
import { RealtimeMount } from "@/components/notifications/realtime-mount";
import { ConsoleProvider } from "./console-context";
import { PlatformNav } from "./platform-nav";

export function PlatformShell({
  user,
  permissions,
  userId,
  skipLabel,
  children,
}: {
  user: { name: string; detail?: string };
  permissions: string[];
  userId: string;
  skipLabel: string;
  children: React.ReactNode;
}) {
  return (
    <ConsoleProvider permissions={permissions} userId={userId}>
      <RealtimeMount />
      <div className="flex min-h-dvh">
        <a
          href="#main"
          className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:rounded-xl focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-foreground"
        >
          {skipLabel}
        </a>
        <aside className="hidden w-64 shrink-0 flex-col gap-6 border-e bg-card p-4 md:flex">
          <Brand />
          <PlatformNav orientation="vertical" />
        </aside>
        <div className="flex min-w-0 flex-1 flex-col">
          <header className="sticky top-0 z-10 border-b bg-background/90 backdrop-blur">
            <div className="flex h-16 items-center justify-between gap-3 px-4">
              <div className="md:hidden">
                <Brand />
              </div>
              <span className="hidden md:block" />
              <HeaderControls user={user} />
            </div>
            <div className="border-t md:hidden">
              <PlatformNav orientation="horizontal" />
            </div>
          </header>
          <main id="main" className="mx-auto w-full max-w-6xl flex-1 p-4 md:p-8">
            {children}
          </main>
        </div>
      </div>
    </ConsoleProvider>
  );
}

export function PlatformShellSkeleton() {
  return (
    <div className="flex min-h-dvh" aria-hidden>
      <aside className="hidden w-64 shrink-0 border-e bg-card md:block" />
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="h-16 border-b" />
      </div>
    </div>
  );
}

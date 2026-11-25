import { Brand } from "@/components/brand";
import { HeaderControls } from "@/components/header-controls";

export function SiteHeader({ user }: { user?: { name: string; detail?: string } }) {
  return (
    <header className="sticky top-0 z-10 border-b bg-background/90 backdrop-blur">
      <div className="mx-auto flex h-16 max-w-5xl items-center justify-between gap-2 px-3 sm:gap-3 sm:px-4">
        <Brand />
        <HeaderControls user={user} />
      </div>
    </header>
  );
}

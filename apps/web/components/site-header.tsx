import { Brand } from "@/components/brand";
import { HeaderControls } from "@/components/header-controls";

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-10 border-b bg-background/90 backdrop-blur">
      <div className="mx-auto flex h-16 max-w-5xl items-center justify-between gap-3 px-4">
        <Brand />
        <HeaderControls />
      </div>
    </header>
  );
}

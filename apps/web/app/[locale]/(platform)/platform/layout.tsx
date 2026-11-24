import { SiteHeader } from "@/components/site-header";

export default function PlatformLayout({ children }: LayoutProps<"/[locale]/platform">) {
  return (
    <>
      <SiteHeader />
      <main id="main" className="mx-auto w-full max-w-5xl flex-1 px-4 py-10">
        {children}
      </main>
    </>
  );
}

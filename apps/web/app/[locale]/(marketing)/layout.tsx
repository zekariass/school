import { SiteHeader } from "@/components/site-header";

export default function MarketingLayout({ children }: LayoutProps<"/[locale]">) {
  return (
    <>
      <SiteHeader />
      <main id="main" className="mx-auto flex w-full max-w-5xl flex-1 flex-col px-4 py-10">
        {children}
      </main>
    </>
  );
}

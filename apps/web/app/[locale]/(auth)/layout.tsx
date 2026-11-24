import { SiteHeader } from "@/components/site-header";

export default function AuthLayout({ children }: LayoutProps<"/[locale]">) {
  return (
    <>
      <SiteHeader />
      <main id="main" className="flex flex-1 items-center justify-center px-4 py-10">
        {children}
      </main>
    </>
  );
}

import { Suspense } from "react";
import { SiteHeader } from "@/components/site-header";

/**
 * Public pages that carry a secret in the URL (the invitation link). Like the sign-in layout, but the
 * header is read inside Suspense: it uses the pathname, which here is runtime data (`[token]` is not
 * known at build time).
 */
export default function InviteLayout({ children }: LayoutProps<"/[locale]">) {
  return (
    <>
      <Suspense fallback={<div className="h-16 border-b" aria-hidden />}>
        <SiteHeader />
      </Suspense>
      <main id="main" className="flex flex-1 items-center justify-center px-4 py-10">
        {children}
      </main>
    </>
  );
}

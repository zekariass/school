import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { looksLikeInviteToken } from "@school/validation/guardians";
import { DeadInvitation, InviteFlow } from "@/components/invite/invite-flow";
import { Skeleton } from "@/components/ui/skeleton";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("invite");
  return {
    title: t("pageTitle"),
    // The link is a credential: keep it out of search results and Referer headers.
    robots: { index: false, follow: false },
    referrer: "no-referrer",
  };
}

/**
 * Public page of an invitation link (SMS or email). It lives outside the protected routes: the
 * visitor has no account yet. `params` is read inside Suspense (cacheComponents).
 */
export default function InvitePage({ params }: PageProps<"/[locale]/invite/[token]">) {
  return (
    <Suspense fallback={<Skeleton className="h-80 w-full max-w-md rounded-2xl" />}>
      <Invite params={params} />
    </Suspense>
  );
}

async function Invite({ params }: { params: PageProps<"/[locale]/invite/[token]">["params"] }) {
  const { token } = await params;
  // A token that cannot be one gets the same page as a dead invitation, without asking the API.
  if (!looksLikeInviteToken(token)) return <DeadInvitation />;
  return <InviteFlow keyOf={{ token }} />;
}

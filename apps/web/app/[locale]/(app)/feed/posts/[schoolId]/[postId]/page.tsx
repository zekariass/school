import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { LinkedPost } from "@/components/feed/linked-views";
import { LinkedShell } from "@/components/feed/linked-shell";
import { isUuid } from "@/lib/feed/ids";

export async function generateMetadata() {
  const t = await getTranslations("school.feed.linked");
  return { title: t("postTitle") };
}

export default function PostPage({ params }: PageProps<"/[locale]/feed/posts/[schoolId]/[postId]">) {
  return (
    <LinkedShell>
      <Suspense fallback={null}>
        <Post params={params} />
      </Suspense>
    </LinkedShell>
  );
}

async function Post({ params }: { params: PageProps<"/[locale]/feed/posts/[schoolId]/[postId]">["params"] }) {
  const { schoolId, postId } = await params;
  // Both ids go into API paths: only well-formed ids pass.
  if (!isUuid(schoolId) || !isUuid(postId)) notFound();
  return <LinkedPost schoolId={schoolId} postId={postId} />;
}

import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Suspense } from "react";
import { LinkedStory } from "@/components/feed/linked-views";
import { LinkedShell } from "@/components/feed/linked-shell";
import { isUuid } from "@/lib/feed/ids";

export async function generateMetadata() {
  const t = await getTranslations("school.feed.linked");
  return { title: t("storyTitle") };
}

export default function StoryPage({ params }: PageProps<"/[locale]/feed/stories/[schoolId]/[storyId]">) {
  return (
    <LinkedShell>
      <Suspense fallback={null}>
        <Story params={params} />
      </Suspense>
    </LinkedShell>
  );
}

async function Story({ params }: { params: PageProps<"/[locale]/feed/stories/[schoolId]/[storyId]">["params"] }) {
  const { schoolId, storyId } = await params;
  if (!isUuid(schoolId) || !isUuid(storyId)) notFound();
  return <LinkedStory schoolId={schoolId} storyId={storyId} />;
}

"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { EmptyNotice, LoadingRows } from "@/components/console/states";
import { Link, useRouter } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { fetchPost, fetchStory, fk } from "@/lib/feed/queries";
import { PostCard } from "./post-card";
import { StoryViewer } from "./story-viewer";

function BackHome() {
  const t = useTranslations("school.feed.linked");
  return (
    <Link href="/" className="inline-flex min-h-11 items-center gap-2 self-start text-sm font-semibold text-primary hover:underline">
      <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
      {t("home")}
    </Link>
  );
}

/** A single post opened from a notification (`/feed/posts/{school}/{post}`), with its comments open. */
export function LinkedPost({ schoolId, postId }: { schoolId: string; postId: string }) {
  const t = useTranslations("school.feed.linked");
  const post = useQuery({ queryKey: fk.post(schoolId, postId), queryFn: () => fetchPost(schoolId, postId), retry: false });
  return (
    <div className="flex flex-col gap-4">
      <BackHome />
      {post.isPending ? <LoadingRows rows={3} /> : post.isError ? <EmptyNotice>{errorMessage(post.error, t("postGone"))}</EmptyNotice> : <PostCard post={post.data} defaultCommentsOpen />}
    </div>
  );
}

/** A single story opened from a notification (`/feed/stories/{school}/{story}`) in the full-screen viewer. */
export function LinkedStory({ schoolId, storyId }: { schoolId: string; storyId: string }) {
  const t = useTranslations("school.feed.linked");
  const router = useRouter();
  const story = useQuery({ queryKey: fk.story(schoolId, storyId), queryFn: () => fetchStory(schoolId, storyId), retry: false });
  return (
    <div className="flex flex-col gap-4">
      <BackHome />
      {story.isPending ? <LoadingRows rows={3} /> : story.isError ? <EmptyNotice>{errorMessage(story.error, t("storyGone"))}</EmptyNotice> : <StoryViewer stories={[story.data]} startAt={0} onClose={() => router.push("/")} />}
    </div>
  );
}

"use client";

import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { EyeOffIcon, Languages, Trash2Icon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { EmptyNotice, ErrorNotice, LoadMore, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { addComment, deleteComment, fetchComments, fk, setCommentHidden, translateComment, type FeedComment, type Post } from "@/lib/feed/queries";
import { mergePages, nextCursorOf } from "@/lib/feed/pages";
import { AuthorName } from "./author";
import { RelativeTime } from "./relative-time";

export const MAX_COMMENT = 1000;

/** The comments of a post: loaded when opened, announced politely when new ones arrive, and a box to add one. */
export function Comments({ post, translationEnabled }: { post: Post; translationEnabled: boolean }) {
  const t = useTranslations("school.feed.comments");
  const queryClient = useQueryClient();
  const key = fk.comments(post.schoolId, post.id);
  const list = useInfiniteQuery({
    queryKey: key,
    queryFn: ({ pageParam }) => fetchComments(post.schoolId, post.id, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => nextCursorOf(last),
  });
  const comments = useMemo(() => mergePages(list.data?.pages), [list.data]);
  const canWrite = !!post.rights.canComment && !!post.commentsEnabled;

  return (
    <section aria-label={t("title")} className="flex flex-col gap-3 border-t pt-3" data-testid="comments">
      {list.isPending ? (
        <LoadingRows rows={2} />
      ) : list.isError ? (
        <ErrorNotice message={errorMessage(list.error, t("loadFailed"))} onRetry={() => void list.refetch()} />
      ) : comments.length === 0 ? (
        <EmptyNotice>{post.commentsEnabled ? t("none") : t("off")}</EmptyNotice>
      ) : (
        <ul className="flex flex-col gap-3" aria-live="polite">
          {comments.map((comment) => (
            <CommentRow
              key={comment.id}
              comment={comment}
              post={post}
              translationEnabled={translationEnabled}
              onChanged={() => void queryClient.invalidateQueries({ queryKey: fk.all })}
            />
          ))}
        </ul>
      )}
      <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()} />
      {canWrite ? <CommentForm post={post} /> : post.commentsEnabled ? null : <p className="text-sm text-muted-foreground">{t("off")}</p>}
    </section>
  );
}

function CommentForm({ post }: { post: Post }) {
  const t = useTranslations("school.feed.comments");
  const [text, setText] = useState("");
  const send = useSubmit({
    mutationFn: (body: string, idempotencyKey: string) => addComment(post.schoolId, post.id, body, idempotencyKey),
    invalidate: [fk.all],
    errorFallback: t("sendFailed"),
    onSuccess: () => setText(""),
  });
  const trimmed = text.trim();
  return (
    <form
      className="flex flex-col gap-2"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        if (trimmed && trimmed.length <= MAX_COMMENT) send.mutate(trimmed);
      }}
    >
      <label htmlFor={`comment-${post.id}`} className="text-sm font-medium">
        {t("label")}
      </label>
      <Textarea
        id={`comment-${post.id}`}
        value={text}
        maxLength={MAX_COMMENT}
        rows={2}
        onChange={(event) => setText(event.target.value)}
        disabled={send.isPending}
        data-testid="comment-input"
      />
      <Button type="submit" size="sm" className="self-end" disabled={send.isPending || !trimmed} data-testid="comment-send">
        {t("send")}
      </Button>
    </form>
  );
}

function CommentRow({
  comment,
  post,
  translationEnabled,
  onChanged,
}: {
  comment: FeedComment;
  post: Post;
  translationEnabled: boolean;
  onChanged: () => void;
}) {
  const t = useTranslations("school.feed.comments");
  const locale = useLocale();
  const [translated, setTranslated] = useState<string | null>(null);
  const hidden = comment.status === "HIDDEN";

  const remove = useMutation({
    mutationFn: () => deleteComment(post.schoolId, post.id, comment.id),
    onSuccess: onChanged,
    onError: (error) => toast.error(errorMessage(error, t("actionFailed"))),
  });
  const hide = useMutation({
    mutationFn: () => setCommentHidden(post.schoolId, post.id, comment.id, !hidden),
    onSuccess: onChanged,
    onError: (error) => toast.error(errorMessage(error, t("actionFailed"))),
  });
  const translate = useMutation({
    mutationFn: () => translateComment(post.schoolId, post.id, comment.id, locale),
    onSuccess: (view) => setTranslated(view.text),
    onError: (error) => toast.error(errorMessage(error, t("translateFailed"))),
  });

  return (
    <li className="flex flex-col gap-1 rounded-xl bg-muted/50 p-3" data-testid="comment">
      <div className="flex flex-wrap items-baseline gap-x-2 text-sm">
        <AuthorName author={comment.author} className="font-semibold" />
        <RelativeTime instant={comment.createdAt} className="text-xs text-muted-foreground" />
        {hidden ? (
          <span className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground">
            <EyeOffIcon className="size-3" aria-hidden />
            {t("hidden")}
          </span>
        ) : null}
      </div>
      <p className="break-words whitespace-pre-wrap" lang={comment.bodyLanguageCode ?? undefined}>
        {comment.body}
      </p>
      {translated ? (
        <p className="rounded-lg bg-background p-2 text-sm">
          <span className="block text-xs font-medium text-muted-foreground">{t("machineTranslated")}</span>
          {translated}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-1">
        {translationEnabled && !translated ? (
          <Button type="button" variant="ghost" size="sm" disabled={translate.isPending} onClick={() => translate.mutate()}>
            <Languages aria-hidden />
            {t("translate")}
          </Button>
        ) : null}
        {comment.canHide ? (
          <Button type="button" variant="ghost" size="sm" disabled={hide.isPending} onClick={() => hide.mutate()}>
            {hidden ? t("unhide") : t("hide")}
          </Button>
        ) : null}
        {comment.canDelete ? (
          <Button type="button" variant="ghost" size="sm" disabled={remove.isPending} onClick={() => remove.mutate()}>
            <Trash2Icon aria-hidden />
            {t("delete")}
          </Button>
        ) : null}
      </div>
    </li>
  );
}

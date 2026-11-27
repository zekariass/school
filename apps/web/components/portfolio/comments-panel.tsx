"use client";

import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { EyeOffIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { useFormatInstant } from "@/components/calendar-preference";
import { useSubmit } from "@/components/console/use-submit";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { ApiRequestError } from "@/lib/api-error";
import { errorMessage } from "@/lib/console/errors";
import { MAX_REASON } from "@/lib/portfolio/actions";
import { MAX_COMMENT, cleanComment, commentProblem } from "@/lib/portfolio/comments";
import { createComment, deleteComment, fetchComments, hideComment, PORTFOLIO_GC_MS, pfk, type PortfolioComment } from "@/lib/portfolio/queries";
import { mergePages, nextCursorOf } from "@/lib/feed/pages";

/**
 * Encouraging comments on an approved item. Who may read and write them is the API's decision (the student, their own
 * guardians and the item's reviewers, never classmates): the panel asks only when the item says comments are possible
 * and shows exactly what comes back. Authors appear by role label only. Texts live in memory only.
 */
export function CommentsPanel({ schoolId, itemId, canComment }: { schoolId: string; itemId: string; canComment: boolean }) {
  const t = useTranslations("school.portfolio.comments");
  const queryClient = useQueryClient();
  const [hiding, setHiding] = useState<PortfolioComment | null>(null);
  const [deleting, setDeleting] = useState<PortfolioComment | null>(null);
  const comments = useInfiniteQuery({
    queryKey: pfk.comments(schoolId, itemId),
    queryFn: ({ pageParam }) => fetchComments(schoolId, itemId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => nextCursorOf(last),
    staleTime: 15_000,
    gcTime: PORTFOLIO_GC_MS,
    retry: false,
  });
  const items = useMemo(() => mergePages(comments.data?.pages), [comments.data]);

  const remove = useMutation({
    mutationFn: (comment: PortfolioComment) => deleteComment(schoolId, itemId, comment.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["portfolio", schoolId, "item", itemId] });
      toast.success(t("deleted"));
      setDeleting(null);
    },
  });

  return (
    <section aria-labelledby="portfolio-comments-title" className="flex flex-col gap-3" data-testid="portfolio-comments">
      <h2 id="portfolio-comments-title" className="text-lg font-bold">
        {t("title")}
      </h2>
      <p className="text-sm text-muted-foreground">{t("privacy")}</p>
      {comments.isPending ? (
        <LoadingRows rows={2} />
      ) : comments.isError ? (
        <ErrorNotice message={errorMessage(comments.error, t("loadFailed"))} onRetry={() => void comments.refetch()} />
      ) : items.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ul className="flex flex-col gap-2">
          {items.map((comment) => (
            <CommentRow key={comment.id} comment={comment} onHide={() => setHiding(comment)} onDelete={() => setDeleting(comment)} />
          ))}
        </ul>
      )}
      <LoadMore hasMore={!!comments.hasNextPage} loading={comments.isFetchingNextPage} onClick={() => void comments.fetchNextPage()} />
      {canComment ? <CommentForm schoolId={schoolId} itemId={itemId} /> : null}

      {hiding ? <HideDialog schoolId={schoolId} itemId={itemId} comment={hiding} onClose={() => setHiding(null)} /> : null}
      {deleting ? (
        <ConfirmDialog
          title={t("deleteTitle")}
          description={t("deleteBody")}
          confirmLabel={t("delete")}
          pending={remove.isPending}
          error={remove.isError ? errorMessage(remove.error, t("deleteFailed")) : null}
          onConfirm={() => remove.mutate(deleting)}
          onClose={() => {
            remove.reset();
            setDeleting(null);
          }}
        />
      ) : null}
    </section>
  );
}

function CommentRow({ comment, onHide, onDelete }: { comment: PortfolioComment; onHide: () => void; onDelete: () => void }) {
  const t = useTranslations("school.portfolio.comments");
  const format = useFormatInstant();
  const hidden = comment.status === "HIDDEN";
  // Guardians and students appear by role only; teachers by name.
  const who = comment.mine ? t("you") : comment.authorName || t(`roles.${comment.authorRole}`);
  return (
    <li className="rounded-xl border p-3 text-sm" data-testid="portfolio-comment" data-mine={comment.mine ? "true" : "false"}>
      <p className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="font-bold">{who}</span>
        <time dateTime={comment.createdAt} className="text-xs text-muted-foreground">
          {format(comment.createdAt)}
        </time>
      </p>
      {hidden ? (
        <p className="mt-1 flex items-center gap-2 text-muted-foreground">
          <EyeOffIcon className="size-4" aria-hidden />
          {t("hidden")}
        </p>
      ) : null}
      {comment.body ? <p className="mt-1 break-words whitespace-pre-wrap">{comment.body}</p> : null}
      {comment.canHide || comment.canDelete ? (
        <p className="mt-2 flex gap-2">
          {comment.canHide && !hidden ? (
            <Button type="button" variant="outline" size="sm" onClick={onHide} data-testid="portfolio-comment-hide">
              {t("hide")}
            </Button>
          ) : null}
          {comment.canDelete ? (
            <Button type="button" variant="ghost" size="sm" onClick={onDelete} data-testid="portfolio-comment-delete">
              {t("delete")}
            </Button>
          ) : null}
        </p>
      ) : null}
    </li>
  );
}

function CommentForm({ schoolId, itemId }: { schoolId: string; itemId: string }) {
  const t = useTranslations("school.portfolio.comments");
  const [text, setText] = useState("");
  const [tried, setTried] = useState(false);
  const [retryAfter, setRetryAfter] = useState<number | null>(null);
  const problem = commentProblem(text);
  // The same text keeps its Idempotency-Key while it is retried; the key is renewed after a success (see `useSubmit`).
  const send = useSubmit<string, unknown>({
    mutationFn: (body, key) => createComment(schoolId, itemId, body, key),
    invalidate: [["portfolio", schoolId, "item", itemId]],
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: () => {
      setText("");
      setTried(false);
      setRetryAfter(null);
    },
  });

  return (
    <form
      noValidate
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        setTried(true);
        setRetryAfter(null);
        if (problem) return;
        send.mutate(cleanComment(text), {
          onError: (error) => {
            if (error instanceof ApiRequestError && error.status === 429) setRetryAfter(error.retryAfter ?? 0);
          },
        });
      }}
    >
      <label htmlFor="portfolio-comment-text" className="text-sm font-medium">
        {t("write")}
      </label>
      <Textarea
        id="portfolio-comment-text"
        rows={2}
        value={text}
        maxLength={MAX_COMMENT}
        onChange={(event) => setText(event.target.value)}
        placeholder={t("placeholder")}
        disabled={send.isPending}
        data-testid="portfolio-comment-text"
      />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">{t("count", { count: text.length, max: MAX_COMMENT })}</span>
        <Button type="submit" disabled={send.isPending} data-testid="portfolio-comment-send">
          {send.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
          {t("send")}
        </Button>
      </div>
      {tried && problem ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {t(`problems.${problem}`)}
        </p>
      ) : null}
      {send.isError ? (
        <p role="alert" className="text-sm font-medium text-destructive" data-testid="portfolio-comment-error">
          {errorMessage(send.error, t("failed"))}
          {retryAfter ? ` ${t("retryAfter", { seconds: retryAfter })}` : ""}
        </p>
      ) : null}
    </form>
  );
}

function HideDialog({ schoolId, itemId, comment, onClose }: { schoolId: string; itemId: string; comment: PortfolioComment; onClose: () => void }) {
  const t = useTranslations("school.portfolio.comments");
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");
  const [tried, setTried] = useState(false);
  const hide = useMutation({
    mutationFn: () => hideComment(schoolId, itemId, comment.id, reason.trim()),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["portfolio", schoolId, "item", itemId] });
      toast.success(t("hiddenDone"));
      onClose();
    },
  });
  const missing = !reason.trim();
  return (
    <Dialog open onOpenChange={(open) => !open && !hide.isPending && onClose()}>
      <DialogContent>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            setTried(true);
            if (!missing) hide.mutate();
          }}
        >
          <DialogHeader>
            <DialogTitle>{t("hideTitle")}</DialogTitle>
            <DialogDescription>{t("hideBody")}</DialogDescription>
          </DialogHeader>
          <label htmlFor="portfolio-hide-reason" className="text-sm font-medium">
            {t("reason")}
          </label>
          <Textarea id="portfolio-hide-reason" rows={2} value={reason} maxLength={MAX_REASON} onChange={(event) => setReason(event.target.value)} disabled={hide.isPending} data-testid="portfolio-hide-reason" />
          {tried && missing ? (
            <p role="alert" className="text-sm font-medium text-destructive">
              {t("reasonRequired")}
            </p>
          ) : null}
          {hide.isError ? (
            <p role="alert" className="text-sm font-medium text-destructive">
              {errorMessage(hide.error, t("hideFailed"))}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={hide.isPending}>
              {t("cancel")}
            </Button>
            <Button type="submit" disabled={hide.isPending} data-testid="portfolio-hide-send">
              {t("hide")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

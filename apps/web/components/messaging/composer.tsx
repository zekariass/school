"use client";

import { useMutation, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { CalendarClockIcon, Loader2Icon, SendIcon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { ImageUploader } from "@/components/media/image-uploader";
import { MediaImage } from "@/components/media/media-image";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { addMedia, afterSent, fingerprint, hasContent, newDraft, nearLimit, prepareSend, removeMedia, shouldSendOnKey, textProblem, withReply, withText, type Draft } from "@/lib/messaging/composer";
import { mk } from "@/lib/messaging/keys";
import { sendMessage } from "@/lib/messaging/queries";
import { checkSchedule, defaultScheduleLocal, scheduleBounds } from "@/lib/messaging/schedule";
import { charCount, previewLine } from "@/lib/messaging/text";
import { isTombstone, upsertMessage } from "@/lib/messaging/thread";
import type { Capabilities, ConversationSummary, Message } from "@/lib/messaging/types";
import { useMessagesPlace } from "./messages-context";
import { usePersonText } from "./person-name";
import { useMessageTime } from "./use-message-time";
import { useUnsavedGuard } from "./use-unsaved-guard";

type Pages = InfiniteData<{ items: Message[]; nextCursor?: string }, string | undefined>;
type Notice = "afterHours" | "scheduled" | null;

/**
 * The composer. Its draft (text, images, the message being replied to) lives in this component's state only and is
 * never stored; leaving with an unsent draft asks first. A send carries a `clientMessageId` that stays the same while the same
 * message is retried (so a request that did arrive is not stored twice) and changes when the content changed. The composer is
 * not rendered at all for observers or closed conversations (the parent decides from the API's `canPost`/role/status).
 */
export function Composer({
  conversation,
  capabilities,
  replyTo,
  onClearReply,
}: {
  conversation: ConversationSummary;
  capabilities: Capabilities | undefined;
  replyTo: Message | null;
  onClearReply: () => void;
}) {
  const t = useTranslations("school.messaging.composer");
  const queryClient = useQueryClient();
  const { base } = useMessagesPlace();
  const { participant } = usePersonText();
  const time = useMessageTime(capabilities?.timeZone);
  const [draft, setDraft] = useState<Draft>(() => newDraft());
  const [sendLater, setSendLater] = useState(false);
  const [local, setLocal] = useState("");
  const [scheduleProblem, setScheduleProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const field = useRef<HTMLTextAreaElement>(null);
  // the newest draft, for the answer of a send that was started with an older one
  const latest = useRef(draft);
  useEffect(() => {
    latest.current = draft;
  });
  const maxLength = capabilities?.maxMessageLength ?? 2000;
  const student = conversation.myActingAs === "STUDENT";
  const imagesAllowed = !student || !!capabilities?.studentMediaEnabled;
  const canSchedule = !student && !!capabilities?.timeZone;
  const length = charCount(draft.text.trim());
  const problem = textProblem(draft, maxLength);

  useUnsavedGuard(hasContent(draft), t("draftWarning"));

  useEffect(() => {
    if (replyTo) field.current?.focus();
  }, [replyTo]);

  const send = useMutation({
    mutationFn: (request: ReturnType<typeof prepareSend>["request"]) => sendMessage(conversation.id, request),
    onSuccess: (result, request) => {
      // The answer can arrive after the person already started the next message (the live "doorbell" may show the sent one
      // first): only what was sent is cleared, never words typed since.
      const current = latest.current;
      const cleared = current.attempt !== null && current.attempt === fingerprint(current, request.sendAt ?? null);
      if (cleared) {
        setDraft(afterSent());
        onClearReply();
        setSendLater(false);
        setLocal("");
      }
      if (result.message.status === "SCHEDULED") {
        setNotice("scheduled");
        void queryClient.invalidateQueries({ queryKey: mk.scheduled });
      } else {
        setNotice(result.afterHours ? "afterHours" : null);
        queryClient.setQueryData<Pages>(mk.messages(conversation.id), (data) => (data ? { ...data, pages: upsertMessage(data.pages, result.message) } : data));
      }
      void queryClient.invalidateQueries({ queryKey: mk.messages(conversation.id) });
      void queryClient.invalidateQueries({ queryKey: mk.inboxAll });
      void queryClient.invalidateQueries({ queryKey: mk.unread });
      field.current?.focus();
    },
  });

  function submit() {
    if (send.isPending || problem) return;
    let sendAt: string | null = null;
    if (sendLater && capabilities?.timeZone) {
      const check = checkSchedule(local, capabilities.timeZone, new Date());
      if (!check.ok) {
        setScheduleProblem(t(`schedule.${check.reason}`));
        return;
      }
      sendAt = check.instant;
    }
    setScheduleProblem(null);
    setNotice(null);
    const prepared = prepareSend(withReply(draft, replyTo?.id ?? null), sendAt);
    // keep the id/attempt in state before the request goes out: a retry after a failure then reuses it
    setDraft(prepared.draft);
    send.mutate(prepared.request);
  }

  const bounds = capabilities?.timeZone ? scheduleBounds(capabilities.timeZone, new Date()) : null;
  const replyText = replyTo && !isTombstone(replyTo) ? previewLine(replyTo.body, 80) : null;
  const sendLabel = sendLater ? t("schedule.submit") : t("send");
  const picked = sendLater && local && capabilities?.timeZone ? checkSchedule(local, capabilities.timeZone, new Date()) : null;
  const willSend = picked?.ok && !scheduleProblem ? time.full(picked.instant) : null;

  return (
    <form
      className="flex flex-col gap-2 rounded-2xl border bg-card p-3"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
      aria-label={t("label")}
      data-testid="composer"
    >
      {notice === "afterHours" ? (
        <p role="status" className="rounded-xl bg-secondary p-3 text-sm text-secondary-foreground" data-testid="after-hours-notice">
          {t("afterHours")}
        </p>
      ) : null}
      {notice === "scheduled" ? (
        <p role="status" className="rounded-xl bg-secondary p-3 text-sm text-secondary-foreground" data-testid="scheduled-notice">
          {t("scheduledNotice")}{" "}
          <Link href={`${base}/scheduled`} className="font-semibold underline underline-offset-2">
            {t("seeScheduled")}
          </Link>
        </p>
      ) : null}

      {replyTo ? (
        <div className="flex items-start justify-between gap-2 rounded-xl bg-muted p-2 text-sm" data-testid="reply-bar">
          <p className="min-w-0">
            <span className="block font-semibold">{t("replyingTo", { name: replyTo.mine ? t("you") : participant(replyTo.sender) })}</span>
            <span dir="auto" className="line-clamp-1 text-muted-foreground [overflow-wrap:anywhere]">
              {replyText ?? ""}
            </span>
          </p>
          <Button type="button" variant="ghost" size="icon-sm" onClick={onClearReply} aria-label={t("cancelReply")}>
            <XIcon aria-hidden />
          </Button>
        </div>
      ) : null}

      {draft.mediaIds.length > 0 ? (
        <ul className="flex flex-wrap gap-2" aria-label={t("attached")}>
          {draft.mediaIds.map((mediaId, index) => (
            <li key={mediaId} className="relative">
              <MediaImage mediaId={mediaId} variant="THUMB" alt={t("attachedAlt", { number: index + 1 })} width={72} height={72} className="size-18 rounded-lg object-cover" />
              <Button
                type="button"
                variant="secondary"
                size="icon-xs"
                className="absolute -end-1.5 -top-1.5 rounded-full"
                aria-label={t("removeImage", { number: index + 1 })}
                onClick={() => setDraft((d) => removeMedia(d, mediaId))}
              >
                <XIcon aria-hidden />
              </Button>
            </li>
          ))}
        </ul>
      ) : null}

      <label htmlFor="composer-text" className="sr-only">
        {t("textLabel")}
      </label>
      <textarea
        id="composer-text"
        ref={field}
        value={draft.text}
        rows={2}
        placeholder={t("placeholder")}
        data-testid="composer-text"
        className="max-h-48 min-h-14 w-full resize-none rounded-xl border border-input bg-background px-3 py-2 text-base outline-none [field-sizing:content] focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50"
        onChange={(event) => setDraft((d) => withText(d, event.target.value))}
        onKeyDown={(event) => {
          if (shouldSendOnKey({ key: event.key, shiftKey: event.shiftKey, isComposing: event.nativeEvent.isComposing })) {
            event.preventDefault();
            submit();
          }
        }}
        dir="auto"
        aria-describedby="composer-hint"
      />

      <div className="flex flex-wrap items-center gap-2">
        {imagesAllowed && draft.mediaIds.length < 4 ? (
          <ImageUploader
            purpose="MESSAGE"
            context={{ type: "CONVERSATION", id: conversation.id }}
            hasImage={false}
            disabled={send.isPending}
            compact
            labels={{ choose: t("addPhoto"), change: t("addPhoto"), remove: t("addPhoto") }}
            onUploaded={async (mediaId) => setDraft((d) => addMedia(d, mediaId))}
          />
        ) : null}
        {canSchedule ? (
          <Button
            type="button"
            variant={sendLater ? "secondary" : "ghost"}
            size="sm"
            aria-pressed={sendLater}
            data-testid="send-later"
            onClick={() => {
              setSendLater((v) => !v);
              setScheduleProblem(null);
              if (!local && capabilities?.timeZone) setLocal(defaultScheduleLocal(capabilities.timeZone, new Date()));
            }}
          >
            <CalendarClockIcon aria-hidden />
            {t("schedule.toggle")}
          </Button>
        ) : null}
        <p id="composer-hint" className={nearLimit(length, maxLength) ? "ms-auto text-xs text-muted-foreground" : "ms-auto hidden text-xs text-muted-foreground sm:block"} aria-live="polite">
          {nearLimit(length, maxLength) ? t("counter", { count: length, max: maxLength }) : t("hint")}
        </p>
        <Button type="submit" disabled={send.isPending || problem !== null} data-testid="send" className="ms-auto sm:ms-0">
          {send.isPending ? <Loader2Icon className="animate-spin" aria-hidden /> : <SendIcon aria-hidden />}
          {sendLabel}
        </Button>
      </div>

      {sendLater && capabilities?.timeZone ? (
        <div className="flex flex-col gap-1 rounded-xl bg-muted p-3">
          <label htmlFor="composer-when" className="text-sm font-medium">
            {t("schedule.when", { zone: capabilities.timeZone })}
          </label>
          <Input id="composer-when" type="datetime-local" value={local} min={bounds?.min} max={bounds?.max} onChange={(e) => setLocal(e.target.value)} data-testid="schedule-when" className="max-w-xs bg-background" />
          {willSend ? <p className="text-xs text-muted-foreground">{t("schedule.willSend", { when: willSend })}</p> : null}
          {scheduleProblem ? (
            <p role="alert" className="text-sm font-medium text-destructive">
              {scheduleProblem}
            </p>
          ) : null}
        </div>
      ) : null}

      {send.isError ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="send-error">
          {errorMessage(send.error, t("failed"))}
        </p>
      ) : null}

    </form>
  );
}


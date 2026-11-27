"use client";

import { BanIcon, EyeOffIcon, FlagIcon, LanguagesIcon, MoreVerticalIcon, PencilIcon, ReplyIcon, SmilePlusIcon, Trash2Icon, UserRoundXIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { MediaImage } from "@/components/media/media-image";
import { ReportDialog } from "@/components/moderation/report-dialog";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { canEditNow, editMinutesLeft, editSecondsLeft } from "@/lib/messaging/edit-window";
import { charCount, messageSegments, previewLine } from "@/lib/messaging/text";
import { isSystem, isTombstone } from "@/lib/messaging/thread";
import { REACTIONS, type Capabilities, type Message } from "@/lib/messaging/types";
import { cn } from "@/lib/utils";
import { ReasonDialog } from "./message-dialogs";
import { usePersonText } from "./person-name";
import { useMessageActions } from "./use-message-actions";
import type { MessageTime } from "./message-list";

interface Props {
  conversationId: string;
  message: Message;
  /** The message this one replies to, when it is among the loaded ones. */
  replyTarget: Message | null;
  /** Show who wrote it (first of a run, or in groups and for observers). */
  showSender: boolean;
  /** True for the newest own message that has a read receipt. */
  showSeen: boolean;
  /** An observer reads only: no reply, reaction or edit; they may still report. */
  observer: boolean;
  canModerate: boolean;
  closed: boolean;
  capabilities: Capabilities | undefined;
  time: MessageTime;
  now: Date;
  onReply: (message: Message) => void;
}

/** One message. Text is plain text with http(s) links only; tombstones show a notice and never the text. */
export function MessageBubble({ conversationId, message, replyTarget, showSender, showSeen, observer, canModerate, closed, capabilities, time, now, onReply }: Props) {
  const t = useTranslations("school.messaging");
  const locale = useLocale();
  const { participant } = usePersonText();
  const actions = useMessageActions(conversationId, { failed: t("message.actionFailed") });
  const [editing, setEditing] = useState(false);
  const [dialog, setDialog] = useState<"report" | "block" | "delete" | "hide" | null>(null);
  const [translation, setTranslation] = useState<{ text: string; failed: boolean } | null>(null);

  const mine = message.mine;
  const tomb = isTombstone(message);
  const system = isSystem(message);
  const sender = message.sender;
  const senderName = participant(sender);
  const editWindow = capabilities?.editWindowMinutes ?? 0;
  const canEdit = !observer && !closed && canEditNow(message, editWindow, now);
  const secondsLeft = editSecondsLeft(message.sentAt, editWindow, now);
  const segments = !tomb && message.body ? messageSegments(message.body) : [];

  if (system) {
    return (
      <p className="self-center rounded-full bg-muted px-3 py-1 text-center text-xs text-muted-foreground" data-testid="system-message">
        {t(`system.${message.systemCode === "MEMBER_REMOVED" ? "MEMBER_REMOVED" : "generic"}`)}
      </p>
    );
  }

  const canReact = !observer && !closed && !tomb;
  const canReply = !observer && !closed && !tomb;
  const canReport = !mine && !tomb;
  const canBlock = !mine && !observer && !!sender && sender.role !== "OBSERVER" && !sender.self;
  const canTranslate = !mine && !tomb && !!message.body && !!capabilities?.translationEnabled;
  const hasMenu = canReply || canEdit || (mine && message.canDelete) || canReport || canBlock || canTranslate || (canModerate && (message.status === "HIDDEN" || !tomb));

  return (
    <div className={cn("group flex max-w-full flex-col gap-1", mine ? "items-end" : "items-start")} data-testid="message" data-status={message.status} data-mine={mine ? "true" : "false"}>
      {showSender && !mine ? (
        <p className="px-1 text-xs font-semibold text-muted-foreground" data-testid="message-sender">
          {senderName}
        </p>
      ) : null}
      <div className={cn("flex max-w-[92%] items-end gap-1 sm:max-w-[80%]", mine && "flex-row-reverse")}>
        <div
          className={cn(
            "min-w-0 rounded-2xl px-3.5 py-2 shadow-xs",
            tomb ? "border border-dashed bg-transparent text-muted-foreground italic" : mine ? "bg-primary text-primary-foreground" : "bg-muted text-foreground",
            message.status === "SCHEDULED" && "opacity-80",
          )}
        >
          {replyTarget || message.replyToMessageId ? <ReplyQuote target={replyTarget} mine={mine} /> : null}
          {tomb ? (
            <p data-testid="tombstone">{message.status === "HIDDEN" ? t("message.hidden") : t("message.deleted")}</p>
          ) : editing ? (
            <EditForm
              initial={message.body ?? ""}
              maxLength={capabilities?.maxMessageLength ?? 2000}
              pending={actions.edit.isPending}
              error={actions.edit.error}
              minutes={editMinutesLeft(secondsLeft)}
              onCancel={() => setEditing(false)}
              onSave={(body) => actions.edit.mutate({ messageId: message.id, body }, { onSuccess: () => setEditing(false) })}
            />
          ) : (
            <>
              {segments.length > 0 ? (
                <p dir="auto" className="whitespace-pre-wrap [overflow-wrap:anywhere]" data-testid="message-body">
                  {segments.map((s, i) =>
                    s.kind === "link" ? (
                      <a key={i} href={s.href} target="_blank" rel="noopener noreferrer nofollow ugc" className="font-medium underline underline-offset-2 hover:no-underline">
                        {s.text}
                      </a>
                    ) : (
                      <span key={i}>{s.text}</span>
                    ),
                  )}
                </p>
              ) : null}
              {message.attachments.length > 0 ? (
                <ul className={cn("flex flex-wrap gap-2", segments.length > 0 && "mt-2")} aria-label={t("message.photos")}>
                  {message.attachments.map((a) => (
                    <li key={a.mediaId}>
                      <MediaImage mediaId={a.mediaId} variant="MEDIUM" alt={t("message.photoAlt")} width={240} height={240} className="max-h-60 w-60 max-w-full rounded-xl object-cover" />
                    </li>
                  ))}
                </ul>
              ) : null}
              {translation ? (
                <p dir="auto" role={translation.failed ? "alert" : undefined} className="mt-2 border-t border-current/20 pt-2 text-sm whitespace-pre-wrap [overflow-wrap:anywhere]">
                  {translation.failed ? null : <span className="mb-0.5 block text-xs font-semibold opacity-80">{t("message.machineTranslated")}</span>}
                  {translation.text}
                </p>
              ) : null}
            </>
          )}
          <p className={cn("mt-1 flex flex-wrap items-center gap-x-2 text-[0.7rem] opacity-80", mine ? "justify-end" : "justify-start")}>
            {message.sentAt ? (
              <time dateTime={message.sentAt} title={time.full(message.sentAt)}>
                {time.clock(message.sentAt)}
              </time>
            ) : null}
            {message.editedAt && !tomb ? <span>{t("message.edited")}</span> : null}
            {message.status === "SCHEDULED" ? <span>{t("message.scheduled")}</span> : null}
          </p>
        </div>

        {hasMenu && !editing ? (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="ghost" size="icon-sm" aria-label={t("message.more")} data-testid="message-menu" className="shrink-0 opacity-70 focus-visible:opacity-100 group-hover:opacity-100">
                <MoreVerticalIcon aria-hidden />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align={mine ? "end" : "start"} className="min-w-52">
              {canReact ? (
                <>
                  <DropdownMenuLabel className="flex items-center gap-2 text-xs">
                    <SmilePlusIcon className="size-3.5" aria-hidden />
                    {t("message.react")}
                  </DropdownMenuLabel>
                  <div role="group" aria-label={t("message.react")} className="grid grid-cols-6 gap-1 px-1 pb-1">
                    {REACTIONS.map((emoji) => (
                      <DropdownMenuItem key={emoji} aria-label={emoji} data-testid="react-option" className="justify-center text-lg" onSelect={() => actions.react.mutate({ message, emoji })}>
                        {emoji}
                      </DropdownMenuItem>
                    ))}
                  </div>
                  <DropdownMenuSeparator />
                </>
              ) : null}
              {canReply ? (
                <DropdownMenuItem data-testid="message-reply" onSelect={() => onReply(message)}>
                  <ReplyIcon aria-hidden />
                  {t("message.reply")}
                </DropdownMenuItem>
              ) : null}
              {canEdit ? (
                <DropdownMenuItem data-testid="message-edit" onSelect={() => setEditing(true)}>
                  <PencilIcon aria-hidden />
                  {t("message.edit")}
                </DropdownMenuItem>
              ) : null}
              {mine && message.canDelete && !closed ? (
                <DropdownMenuItem data-testid="message-delete" onSelect={() => setDialog("delete")}>
                  <Trash2Icon aria-hidden />
                  {t("message.delete")}
                </DropdownMenuItem>
              ) : null}
              {canTranslate ? (
                <DropdownMenuItem
                  onSelect={() =>
                    actions.translate.mutate(
                      { messageId: message.id, language: locale },
                      {
                        onSuccess: (r) => setTranslation({ text: r.text, failed: false }),
                        onError: (e) => setTranslation({ text: errorMessage(e, t("message.translateFailed")), failed: true }),
                      },
                    )
                  }
                >
                  <LanguagesIcon aria-hidden />
                  {t("message.translate")}
                </DropdownMenuItem>
              ) : null}
              {canReport || canBlock || canModerate ? <DropdownMenuSeparator /> : null}
              {canReport ? (
                <DropdownMenuItem data-testid="message-report" onSelect={() => setDialog("report")}>
                  <FlagIcon aria-hidden />
                  {t("message.report")}
                </DropdownMenuItem>
              ) : null}
              {canBlock ? (
                <DropdownMenuItem data-testid="message-block" onSelect={() => setDialog("block")}>
                  <UserRoundXIcon aria-hidden />
                  {t("message.block")}
                </DropdownMenuItem>
              ) : null}
              {canModerate && message.status === "HIDDEN" ? (
                <DropdownMenuItem data-testid="message-unhide" onSelect={() => actions.unhide.mutate({ messageId: message.id })}>
                  <EyeOffIcon aria-hidden />
                  {t("message.unhide")}
                </DropdownMenuItem>
              ) : null}
              {canModerate && !tomb ? (
                <DropdownMenuItem data-testid="message-hide" variant="destructive" onSelect={() => setDialog("hide")}>
                  <BanIcon aria-hidden />
                  {t("message.hide")}
                </DropdownMenuItem>
              ) : null}
            </DropdownMenuContent>
          </DropdownMenu>
        ) : null}
      </div>

      {message.reactions.length > 0 && !tomb ? (
        <ul className={cn("flex flex-wrap gap-1", mine ? "justify-end" : "justify-start")} aria-label={t("message.reactions")}>
          {message.reactions.map((r) => (
            <li key={r.emoji}>
              <button
                type="button"
                disabled={!canReact || actions.react.isPending}
                aria-pressed={r.mine}
                aria-label={t("message.reactionCount", { emoji: r.emoji, count: r.count })}
                data-testid="reaction"
                onClick={() => actions.react.mutate({ message, emoji: r.emoji })}
                className={cn("inline-flex h-7 items-center gap-1 rounded-full border px-2 text-sm", r.mine ? "border-primary bg-primary/10" : "bg-background", canReact && "hover:bg-accent")}
              >
                <span aria-hidden>{r.emoji}</span>
                <span className="text-xs font-semibold">{r.count}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      {showSeen && message.seenAt ? (
        <p className="px-1 text-[0.7rem] text-muted-foreground" data-testid="seen">
          {t("message.seen")}
        </p>
      ) : null}

      {dialog === "report" ? <ReportDialog target={{ type: "MESSAGE", id: message.id }} onClose={() => setDialog(null)} /> : null}
      {dialog === "block" ? (
        <ConfirmDialog
          title={t("block.title")}
          description={t("block.description")}
          confirmLabel={t("block.confirm")}
          pending={actions.block.isPending}
          error={actions.block.error ? errorMessage(actions.block.error, t("block.failed")) : null}
          onClose={() => {
            actions.block.reset();
            setDialog(null);
          }}
          onConfirm={() => sender && actions.block.mutate({ memberId: sender.memberId }, { onSuccess: () => setDialog(null) })}
        />
      ) : null}
      {dialog === "delete" ? (
        <ConfirmDialog
          title={t("delete.title")}
          description={t("delete.description")}
          confirmLabel={t("delete.confirm")}
          pending={actions.remove.isPending}
          error={actions.remove.error ? errorMessage(actions.remove.error, t("delete.failed")) : null}
          onClose={() => {
            actions.remove.reset();
            setDialog(null);
          }}
          onConfirm={() => actions.remove.mutate({ message }, { onSuccess: () => setDialog(null) })}
        />
      ) : null}
      {dialog === "hide" ? (
        <ReasonDialog
          title={t("hide.title")}
          description={t("hide.description")}
          label={t("hide.reason")}
          submitLabel={t("hide.confirm")}
          maxLength={300}
          pending={actions.hide.isPending}
          error={actions.hide.error}
          onClose={() => {
            actions.hide.reset();
            setDialog(null);
          }}
          onSubmit={(reason) => actions.hide.mutate({ messageId: message.id, reason }, { onSuccess: () => setDialog(null) })}
        />
      ) : null}
    </div>
  );
}

/** "Replying to …": the text of the loaded original, one line, plain text; a notice for a deleted or unloaded one. */
function ReplyQuote({ target, mine }: { target: Message | null; mine: boolean }) {
  const t = useTranslations("school.messaging.message");
  const { participant } = usePersonText();
  const text = target && !isTombstone(target) ? previewLine(target.body, 80) : null;
  const who = target ? (target.mine ? t("you") : participant(target.sender)) : null;
  return (
    <blockquote className={cn("mb-1.5 border-s-2 ps-2 text-xs", mine ? "border-primary-foreground/60 text-primary-foreground/90" : "border-primary/60 text-muted-foreground")} data-testid="reply-quote">
      {who ? <span className="block font-semibold">{t("replyingTo", { name: who })}</span> : <span className="block font-semibold">{t("replyingToMessage")}</span>}
      <span dir="auto" className="line-clamp-2 [overflow-wrap:anywhere]">
        {text ?? (target ? t("deleted") : "")}
      </span>
    </blockquote>
  );
}

function EditForm({
  initial,
  maxLength,
  pending,
  error,
  minutes,
  onSave,
  onCancel,
}: {
  initial: string;
  maxLength: number;
  pending: boolean;
  error: unknown;
  minutes: number;
  onSave: (body: string) => void;
  onCancel: () => void;
}) {
  const t = useTranslations("school.messaging.message");
  const [text, setText] = useState(initial);
  const trimmed = text.trim();
  const invalid = trimmed.length === 0 || charCount(trimmed) > maxLength;
  return (
    <form
      className="flex min-w-48 flex-col gap-2 text-foreground"
      onSubmit={(event) => {
        event.preventDefault();
        if (!invalid && trimmed !== initial.trim()) onSave(trimmed);
        else if (trimmed === initial.trim()) onCancel();
      }}
    >
      <label className="sr-only" htmlFor="edit-message">
        {t("editLabel")}
      </label>
      <Textarea id="edit-message" autoFocus value={text} rows={2} maxLength={maxLength * 2} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => e.key === "Escape" && onCancel()} className="bg-background [field-sizing:fixed] [overflow-wrap:anywhere]" />
      <p className="text-xs text-muted-foreground">{t("editWindow", { minutes })}</p>
      {error ? (
        <p role="alert" className="text-xs font-medium text-destructive">
          {errorMessage(error, t("editFailed"))}
        </p>
      ) : null}
      <div className="flex justify-end gap-2">
        <Button type="button" size="sm" variant="outline" onClick={onCancel} disabled={pending}>
          {t("cancel")}
        </Button>
        <Button type="submit" size="sm" disabled={pending || invalid}>
          {t("save")}
        </Button>
      </div>
    </form>
  );
}

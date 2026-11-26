import { newIdempotencyKey } from "@/lib/console/idempotency";
import { charCount } from "./text";

/**
 * The composer's state and the request it sends. It lives in component state only: a draft is a child's words and is
 * never written to storage or a URL.
 *
 * `clientMessageId` makes a send idempotent: the API stores a message once per id and answers a repeat with the original.
 * So a retry of the SAME message must reuse the id (a request that did arrive must not be stored twice), while a message
 * whose content changed after a failed attempt must get a new one (the API would otherwise return the old text and drop the
 * change). `attempt` remembers what was last sent under the current id.
 */
export interface Draft {
  text: string;
  mediaIds: string[];
  replyToMessageId: string | null;
  clientMessageId: string;
  /** The content fingerprint of the last attempt under `clientMessageId`; null before any attempt. */
  attempt: string | null;
}

export type Id = () => string;

export function newDraft(id: Id = newIdempotencyKey): Draft {
  return { text: "", mediaIds: [], replyToMessageId: null, clientMessageId: id(), attempt: null };
}

export type SendRequest = {
  clientMessageId: string;
  messageType: "TEXT" | "MEDIA";
  body?: string;
  mediaIds?: string[];
  replyToMessageId?: string;
  sendAt?: string;
};

/** What makes two sends "the same message". */
export function fingerprint(draft: Pick<Draft, "text" | "mediaIds" | "replyToMessageId">, sendAt: string | null): string {
  return JSON.stringify([draft.text.trim(), draft.mediaIds, draft.replyToMessageId, sendAt]);
}

export type TextProblem = "empty" | "tooLong";

/** Empty (only blanks, no image) or too long (in characters, as the API counts them). */
export function textProblem(draft: Pick<Draft, "text" | "mediaIds">, maxLength: number): TextProblem | null {
  const text = draft.text.trim();
  if (text.length === 0 && draft.mediaIds.length === 0) return "empty";
  if (charCount(text) > maxLength) return "tooLong";
  return null;
}

export function hasContent(draft: Pick<Draft, "text" | "mediaIds">): boolean {
  return draft.text.trim().length > 0 || draft.mediaIds.length > 0;
}

/**
 * The request for a send, and the draft to keep while it is in flight: the id stays when this is the same message as the
 * last attempt, a new id is made when the content changed since.
 */
export function prepareSend(draft: Draft, sendAt: string | null, id: Id = newIdempotencyKey): { request: SendRequest; draft: Draft } {
  const print = fingerprint(draft, sendAt);
  const clientMessageId = draft.attempt !== null && draft.attempt !== print ? id() : draft.clientMessageId;
  const text = draft.text.trim();
  const request: SendRequest = {
    clientMessageId,
    messageType: draft.mediaIds.length > 0 ? "MEDIA" : "TEXT",
    ...(text ? { body: text } : {}),
    ...(draft.mediaIds.length > 0 ? { mediaIds: draft.mediaIds } : {}),
    ...(draft.replyToMessageId ? { replyToMessageId: draft.replyToMessageId } : {}),
    ...(sendAt ? { sendAt } : {}),
  };
  return { request, draft: { ...draft, clientMessageId, attempt: print } };
}

/** After the API stored the message (201 or 200): an empty draft with a fresh id. */
export function afterSent(id: Id = newIdempotencyKey): Draft {
  return newDraft(id);
}

/** Editing a draft keeps its id and attempt; `prepareSend` decides at the next send whether the id is still right. */
export function withText(draft: Draft, text: string): Draft {
  return { ...draft, text };
}

export function withReply(draft: Draft, messageId: string | null): Draft {
  return { ...draft, replyToMessageId: messageId };
}

export function addMedia(draft: Draft, mediaId: string, max = 4): Draft {
  if (draft.mediaIds.includes(mediaId) || draft.mediaIds.length >= max) return draft;
  return { ...draft, mediaIds: [...draft.mediaIds, mediaId] };
}

export function removeMedia(draft: Draft, mediaId: string): Draft {
  return { ...draft, mediaIds: draft.mediaIds.filter((m) => m !== mediaId) };
}

/** Enter sends, Shift+Enter writes a line break; never while an input method is composing (IME). */
export function shouldSendOnKey(event: { key: string; shiftKey: boolean; isComposing?: boolean; ctrlKey?: boolean; metaKey?: boolean }): boolean {
  if (event.isComposing) return false;
  return event.key === "Enter" && !event.shiftKey;
}

/** Hint under the composer when the text nears the limit. */
export function nearLimit(length: number, maxLength: number): boolean {
  return length >= Math.floor(maxLength * 0.9);
}

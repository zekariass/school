import { describe, expect, it } from "vitest";
import { addMedia, afterSent, hasContent, newDraft, prepareSend, removeMedia, shouldSendOnKey, textProblem, withReply, withText } from "./composer";

let n = 0;
const id = () => `id-${++n}`;

describe("send payloads and clientMessageId", () => {
  it("builds a TEXT request with a trimmed body", () => {
    const draft = withText(newDraft(id), "  hello  ");
    const { request } = prepareSend(draft, null, id);
    expect(request).toMatchObject({ messageType: "TEXT", body: "hello", clientMessageId: draft.clientMessageId });
    expect(request).not.toHaveProperty("mediaIds");
    expect(request).not.toHaveProperty("sendAt");
  });

  it("reuses the same clientMessageId when the same message is retried", () => {
    const draft = withText(newDraft(id), "hello");
    const first = prepareSend(draft, null, id);
    // the request failed (network): the draft stays, the user presses send again
    const second = prepareSend(first.draft, null, id);
    expect(second.request.clientMessageId).toBe(first.request.clientMessageId);
    const third = prepareSend(second.draft, null, id);
    expect(third.request.clientMessageId).toBe(first.request.clientMessageId);
  });

  it("makes a new id when the text changed after a failed attempt (the API would answer with the old text)", () => {
    const first = prepareSend(withText(newDraft(id), "hello"), null, id);
    const edited = prepareSend(withText(first.draft, "hello there"), null, id);
    expect(edited.request.clientMessageId).not.toBe(first.request.clientMessageId);
    // and a retry of the edited message keeps its own id
    expect(prepareSend(edited.draft, null, id).request.clientMessageId).toBe(edited.request.clientMessageId);
  });

  it("makes a new id when the schedule time, reply or images changed", () => {
    const a = prepareSend(withText(newDraft(id), "x"), null, id);
    expect(prepareSend(a.draft, "2026-12-01T05:00:00.000Z", id).request.clientMessageId).not.toBe(a.request.clientMessageId);
    const b = prepareSend(withText(newDraft(id), "x"), null, id);
    expect(prepareSend(withReply(b.draft, "m1"), null, id).request.clientMessageId).not.toBe(b.request.clientMessageId);
    const c = prepareSend(withText(newDraft(id), "x"), null, id);
    expect(prepareSend(addMedia(c.draft, "media1"), null, id).request.clientMessageId).not.toBe(c.request.clientMessageId);
  });

  it("does not need a new id for a first send, and a sent message starts a fresh draft with a fresh id", () => {
    const draft = withText(newDraft(id), "hi");
    expect(prepareSend(draft, null, id).request.clientMessageId).toBe(draft.clientMessageId);
    const sent = afterSent(id);
    expect(sent.text).toBe("");
    expect(sent.clientMessageId).not.toBe(draft.clientMessageId);
  });

  it("sends images as MEDIA with reply and schedule", () => {
    let draft = addMedia(addMedia(newDraft(id), "m1"), "m2");
    draft = withReply(draft, "reply-to");
    const { request } = prepareSend(draft, "2026-12-01T05:00:00.000Z", id);
    expect(request).toMatchObject({ messageType: "MEDIA", mediaIds: ["m1", "m2"], replyToMessageId: "reply-to", sendAt: "2026-12-01T05:00:00.000Z" });
    expect(request).not.toHaveProperty("body");
  });

  it("limits images to four and ignores a duplicate", () => {
    let draft = newDraft(id);
    for (const m of ["a", "b", "c", "d", "e", "a"]) draft = addMedia(draft, m);
    expect(draft.mediaIds).toEqual(["a", "b", "c", "d"]);
    expect(removeMedia(draft, "b").mediaIds).toEqual(["a", "c", "d"]);
  });
});

describe("what can be sent", () => {
  it("refuses empty and over-long text, in characters", () => {
    expect(textProblem(withText(newDraft(id), "   "), 2000)).toBe("empty");
    expect(textProblem(withText(newDraft(id), "x".repeat(2001)), 2000)).toBe("tooLong");
    expect(textProblem(withText(newDraft(id), "x".repeat(2000)), 2000)).toBeNull();
    expect(textProblem(withText(newDraft(id), "👍".repeat(2000)), 2000)).toBeNull();
    expect(textProblem(addMedia(newDraft(id), "m"), 2000)).toBeNull();
  });

  it("tells whether there is anything to lose when leaving", () => {
    expect(hasContent(newDraft(id))).toBe(false);
    expect(hasContent(withText(newDraft(id), " "))).toBe(false);
    expect(hasContent(withText(newDraft(id), "hi"))).toBe(true);
    expect(hasContent(addMedia(newDraft(id), "m"))).toBe(true);
  });

  it("sends on Enter but not Shift+Enter or while composing", () => {
    expect(shouldSendOnKey({ key: "Enter", shiftKey: false })).toBe(true);
    expect(shouldSendOnKey({ key: "Enter", shiftKey: true })).toBe(false);
    expect(shouldSendOnKey({ key: "Enter", shiftKey: false, isComposing: true })).toBe(false);
    expect(shouldSendOnKey({ key: "a", shiftKey: false })).toBe(false);
  });
});

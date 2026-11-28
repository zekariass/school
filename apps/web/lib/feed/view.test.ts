import { describe, expect, it } from "vitest";
import type { FeedAuthor, Post } from "./queries";
import { authorLabel, collagePhotos, collageShape, hasAnyAction, hasSeenCount, isWithheldPlaceholder, isWithheldWarning, postMenu } from "./view";

const rights = (patch: Partial<Post["rights"]> = {}): Post["rights"] => ({ canEdit: false, canDelete: false, canModerate: false, canPin: false, canComment: false, canPublish: false, ...patch });
const post = (patch: Partial<Pick<Post, "status" | "pinned" | "contribution">> & { rights?: Post["rights"] } = {}) => ({
  status: "PUBLISHED" as Post["status"],
  pinned: false,
  contribution: false,
  rights: rights(),
  ...patch,
});

describe("authorLabel", () => {
  it("shows the name when the API sent one and only the role otherwise", () => {
    const named: FeedAuthor = { role: "TEACHER", displayName: " Ms Almaz " };
    expect(authorLabel(named)).toEqual({ kind: "name", name: "Ms Almaz", role: "TEACHER" });
    expect(authorLabel({ role: "GUARDIAN" })).toEqual({ kind: "role", role: "GUARDIAN" });
    expect(authorLabel({ role: "STUDENT", displayName: "  " })).toEqual({ kind: "role", role: "STUDENT" });
  });
});

describe("withheld media", () => {
  it("is a placeholder when the API sent no photos, a warning when the author still sees them", () => {
    expect(isWithheldPlaceholder({ mediaWithheld: true, media: [] })).toBe(true);
    expect(isWithheldPlaceholder({ mediaWithheld: false, media: [] })).toBe(false);
    expect(isWithheldPlaceholder({ mediaWithheld: true, media: [{ mediaId: "m", kind: "IMAGE", variants: [] }] })).toBe(false);
    expect(isWithheldWarning({ mediaWithheld: true, media: [{ mediaId: "m", kind: "IMAGE", variants: [] }] })).toBe(true);
    expect(isWithheldWarning({ mediaWithheld: true, media: [] })).toBe(false);
  });
});

describe("seen count", () => {
  it("is shown only when the API sent a number (zero included)", () => {
    expect(hasSeenCount({ seenCount: 0 })).toBe(true);
    expect(hasSeenCount({ seenCount: 7 })).toBe(true);
    expect(hasSeenCount({})).toBe(false);
  });
});

describe("photo collage", () => {
  it("shapes by count and caps at three with a +n", () => {
    expect(collageShape(0)).toBe("one");
    expect(collageShape(1)).toBe("one");
    expect(collageShape(2)).toBe("two");
    expect(collageShape(3)).toBe("three");
    expect(collageShape(7)).toBe("three");
    expect(collagePhotos([1, 2, 3, 4, 5, 6])).toEqual({ shown: [1, 2, 3], extra: 3 });
    expect(collagePhotos([1, 2, 3])).toEqual({ shown: [1, 2, 3], extra: 0 });
    expect(collagePhotos([1])).toEqual({ shown: [1], extra: 0 });
  });
});

describe("postMenu follows the API's rights and the status", () => {
  it("gives a reader only the report action on a published post", () => {
    const menu = postMenu(post());
    expect(menu.report).toBe(true);
    expect(Object.entries(menu).filter(([, on]) => on).map(([name]) => name)).toEqual(["report"]);
  });

  it("gives the author of a published post edit, take down and delete (no report)", () => {
    const menu = postMenu(post({ rights: rights({ canEdit: true, canDelete: true, canPin: true }) }));
    expect(menu).toMatchObject({ edit: true, archive: true, remove: true, pin: true, unpin: false, report: false, publish: false });
  });

  it("offers unpin for a pinned post and hide to moderators", () => {
    const menu = postMenu(post({ pinned: true, rights: rights({ canPin: true, canModerate: true, canDelete: true }) }));
    expect(menu).toMatchObject({ unpin: true, pin: false, hide: true, remove: true, report: false });
  });

  it("offers publish and schedule for drafts, unschedule for scheduled posts", () => {
    const draft = postMenu(post({ status: "DRAFT", rights: rights({ canEdit: true, canDelete: true, canPublish: true }) }));
    expect(draft).toMatchObject({ publish: true, schedule: true, unschedule: false, edit: true, pin: false, report: false });
    const scheduled = postMenu(post({ status: "SCHEDULED", rights: rights({ canEdit: true, canPublish: true }) }));
    expect(scheduled).toMatchObject({ unschedule: true, schedule: true, publish: true });
  });

  it("offers unhide to moderators of a hidden post and nothing editable to its author", () => {
    expect(postMenu(post({ status: "HIDDEN", rights: rights({ canModerate: true }) }))).toMatchObject({ unhide: true, hide: false });
    expect(postMenu(post({ status: "HIDDEN", rights: rights({ canEdit: true }) })).edit).toBe(false);
    expect(postMenu(post({ status: "REJECTED", rights: rights({ canEdit: true, canDelete: true }) }))).toMatchObject({ edit: false, remove: true });
  });

  it("offers reporting the author as a person only when the API named them and the post is not theirs", () => {
    expect(postMenu({ ...post(), author: { userId: "u-1" } })).toMatchObject({ report: true, reportAuthor: true });
    expect(postMenu({ ...post(), author: {} }).reportAuthor).toBe(false);
    expect(postMenu({ ...post({ rights: rights({ canEdit: true }) }), author: { userId: "u-1" } }).reportAuthor).toBe(false);
    expect(postMenu({ ...post({ status: "HIDDEN" }), author: { userId: "u-1" } }).reportAuthor).toBe(false);
  });

  it("is empty when nothing is allowed", () => {
    expect(hasAnyAction(postMenu(post({ status: "ARCHIVED" })))).toBe(false);
  });
});

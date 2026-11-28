import type { FeedAuthor, Post } from "./queries";

/** How a post card looks, from the API's `postType`: nothing is derived from the text. */
export type PostVariant = "achievement" | "activity" | "standard";

export function postVariant(post: Pick<Post, "postType">): PostVariant {
  return post.postType === "ACHIEVEMENT" ? "achievement" : post.postType === "ACTIVITY" ? "activity" : "standard";
}

export type RoleChip = FeedAuthor["role"];

/** The roles that get a chip next to an author's name (translated labels, never the raw enum). */
export const ROLE_CHIPS: readonly RoleChip[] = ["TEACHER", "SCHOOL_ADMIN", "GUARDIAN", "STUDENT"];

/** Teachers and the school get the accent chip, families and students the quiet one. */
export function roleChipTone(role: RoleChip): "accent" | "quiet" {
  return role === "TEACHER" || role === "SCHOOL_ADMIN" ? "accent" : "quiet";
}

/** The longest first line that is shown in bold. */
export const FIRST_LINE_MAX = 100;

/**
 * Splits a plain-text body at its first line break so the first line can be bold by CSS. `first + rest` is always the
 * exact original text. A body without a line break, or whose first line is long, is not split (`first` is empty).
 */
export function splitFirstLine(body: string): { first: string; rest: string } {
  const cut = body.indexOf("\n");
  if (cut <= 0 || cut > FIRST_LINE_MAX) return { first: "", rest: body };
  return { first: body.slice(0, cut), rest: body.slice(cut) };
}

/** The first few words of a text on one line, for a label. Never longer than `maxChars` before the ellipsis. */
export function firstWords(text: string | undefined, words = 5, maxChars = 40): string {
  const all = (text ?? "").trim().split(/\s+/).filter(Boolean);
  if (all.length === 0) return "";
  let out = all.slice(0, words).join(" ");
  let cut = all.length > words;
  if (out.length > maxChars) {
    out = out.slice(0, maxChars).trimEnd();
    cut = true;
  }
  return cut ? `${out}…` : out;
}

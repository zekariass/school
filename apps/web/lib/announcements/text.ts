/**
 * Announcement titles and bodies are PLAIN TEXT written by people. They are rendered as text, never as HTML: React
 * escapes every string, and the only thing made interactive is an `http(s)` link. This is the single place that decides
 * what counts as a link, so it is small, strict and tested.
 */

export type TextSegment = { kind: "text"; text: string } | { kind: "link"; text: string; href: string };

/** A candidate: a scheme, `//`, then everything up to white space or a character that cannot be part of a link. */
const CANDIDATE = /https?:\/\/[^\s<>"'`\\^{}|]+/gi;
const MAX_LINK = 2048;

/** The address when `candidate` is a plain http or https link without credentials, else null. */
export function safeHref(candidate: string): string | null {
  if (candidate.length > MAX_LINK) return null;
  let url: URL;
  try {
    url = new URL(candidate);
  } catch {
    return null;
  }
  // `javascript:`, `data:`, `file:` and friends never pass; `https://school.example@evil.example` (a disguised host) neither.
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  if (url.username || url.password) return null;
  if (!url.hostname) return null;
  return url.href;
}

/** Splits text into plain runs and safe links. Concatenating the segments' `text` gives back the input exactly. */
export function linkify(input: string): TextSegment[] {
  const segments: TextSegment[] = [];
  let last = 0;
  for (const match of input.matchAll(CANDIDATE)) {
    const start = match.index ?? 0;
    let text = match[0];
    // Closing brackets belong to the link only when it opened one (a wiki-style address), otherwise they end the sentence.
    text = trimTrailing(text);
    const href = safeHref(text);
    if (!href) continue;
    if (start > last) segments.push({ kind: "text", text: input.slice(last, start) });
    segments.push({ kind: "link", text, href });
    last = start + text.length;
  }
  if (last < input.length) segments.push({ kind: "text", text: input.slice(last) });
  return segments;
}

function trimTrailing(text: string): string {
  let out = text;
  while (out.length > 0) {
    const last = out[out.length - 1]!;
    const sentence = ".,;:!?'\"]}>".includes(last);
    // A ")" ends the link unless it closes a "(" inside it (wiki-style addresses).
    const unbalanced = last === ")" && count(out, ")") > count(out, "(");
    if (!sentence && !unbalanced) break;
    out = out.slice(0, -1);
  }
  return out;
}

function count(text: string, ch: string): number {
  let n = 0;
  for (const c of text) if (c === ch) n++;
  return n;
}

/** A single line for lists: whitespace collapsed, cut at `max` characters (never in the middle of a surrogate pair). */
export function oneLine(text: string, max: number): string {
  const flat = text.replace(/\s+/g, " ").trim();
  const chars = Array.from(flat);
  return chars.length <= max ? flat : `${chars.slice(0, max - 1).join("")}…`;
}

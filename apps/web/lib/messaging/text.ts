import { linkify, oneLine, type TextSegment } from "@/lib/announcements/text";

/**
 * Message text is PLAIN TEXT written by people (some of them children). It is rendered as text, never as HTML: React
 * escapes every character, and the only thing made interactive is an http(s) link without credentials (the same
 * strict rules as announcements, see `lib/announcements/text`). Control, bidirectional and zero-width characters are
 * stripped by the API; the display strips them again so a text from any other source cannot reorder what is read.
 */
export type { TextSegment };

/** Control (except newline and tab), bidirectional-override and zero-width characters. */
const INVISIBLE = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u200B-\u200F\u202A-\u202E\u2060-\u2064\u2066-\u2069\uFEFF]/g;

/** The text as shown: invisible characters removed, line breaks normalised, runs of blank lines shortened. */
export function displayText(body: string): string {
  return body.replace(/\r\n?/g, "\n").replace(INVISIBLE, "").replace(/\n{3,}/g, "\n\n");
}

/** Plain runs and safe links of a message. */
export function messageSegments(body: string): TextSegment[] {
  return linkify(displayText(body));
}

/** A one-line preview for lists; the caller passes `undefined` for tombstones (the API sends no preview for them). */
export function previewLine(preview: string | undefined, max = 90): string | null {
  if (preview === undefined) return null;
  return oneLine(displayText(preview), max);
}

/** Characters as people count them (code points), not UTF-16 units. */
export function charCount(text: string): number {
  return Array.from(text).length;
}

/**
 * Announcement text is plain text. It is shown as text, never interpreted (no markup, no HTML); the only thing the app adds is
 * a tappable link for a web address, and only for `http` and `https`. A `javascript:`, `intent:`, `tel:`, `mailto:`,
 * `file:` or app-scheme address stays plain text, and so does anything the platform's URL parser would read differently.
 */

export type Segment = { type: 'text'; text: string } | { type: 'link'; text: string; url: string };

const MAX_LINKS = 20;
const MAX_URL_LENGTH = 2000;
/** A candidate: a scheme then `://` and everything up to the next white space or angle bracket. */
const CANDIDATE = /\b[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^\s<>"']+/g;
/** Sentence punctuation that belongs to the sentence, not to the address. */
const TRAILING = /[.,;:!?)\]}»”’]+$/;

/** Parses `https://host/path`; null unless it is a plain http(s) URL with a host and no credentials. */
export function safeWebUrl(candidate: string): string | null {
  if (candidate.length === 0 || candidate.length > MAX_URL_LENGTH) return null;
  if (!/^https?:\/\//i.test(candidate)) return null;
  // A control or invisible character never belongs in a link a person is asked to trust.
  if (/[\u0000-\u001f\u007f-\u009f​-‏‪-‮⁦-⁩﻿]/.test(candidate)) return null;
  const rest = candidate.replace(/^https?:\/\//i, '');
  const authority = rest.split(/[/?#]/)[0] ?? '';
  // `https://trusted.example@evil.example/` shows one host and opens another: no credentials.
  if (authority.length === 0 || authority.includes('@') || authority.includes('\\')) return null;
  const host = authority.replace(/:\d{1,5}$/, '');
  if (!/^[\p{L}\p{N}.-]+$/u.test(host) && !/^\[[0-9a-fA-F:.]+\]$/.test(host)) return null;
  if (!/[.:\[]/.test(host) && host !== 'localhost') return null;
  return candidate;
}

/** Splits `text` into plain pieces and web links, in order; every character of the input is in exactly one piece. */
export function linkify(text: string): Segment[] {
  const segments: Segment[] = [];
  let cursor = 0;
  let links = 0;
  const push = (piece: string) => {
    if (!piece) return;
    const last = segments[segments.length - 1];
    if (last && last.type === 'text') last.text += piece;
    else segments.push({ type: 'text', text: piece });
  };
  for (const match of text.matchAll(CANDIDATE)) {
    const start = match.index ?? 0;
    let found = match[0];
    const tail = TRAILING.exec(found)?.[0] ?? '';
    // Keep a closing bracket that opened inside the address (wikipedia-style paths).
    let cut = found.length - tail.length;
    for (const closer of tail) {
      const opener = closer === ')' ? '(' : closer === ']' ? '[' : closer === '}' ? '{' : null;
      if (opener && count(found.slice(0, cut), opener) > count(found.slice(0, cut), closer)) cut += 1;
      else break;
    }
    found = found.slice(0, cut);
    const url = links < MAX_LINKS ? safeWebUrl(found) : null;
    push(text.slice(cursor, start));
    if (url) {
      segments.push({ type: 'link', text: found, url });
      links += 1;
      cursor = start + found.length;
    } else {
      // not a link: the whole candidate stays text
      push(match[0]);
      cursor = start + match[0].length;
    }
  }
  push(text.slice(cursor));
  return segments;
}

function count(text: string, char: string): number {
  let n = 0;
  for (const c of text) if (c === char) n += 1;
  return n;
}

// Heuristic glossary extraction: for each recurring term, find the Amharic word that is
// most strongly associated with rows whose English contains the term.
import { parseIcu, stripIcuSyntax } from './lib.mjs';

export const TERMS = [
  ['school', /\bschools?\b/i],
  ['class', /\bclass(es)?\b/i],
  ['teacher', /\bteachers?\b/i],
  ['parent', /\bparents?\b/i],
  ['guardian', /\bguardians?\b/i],
  ['student', /\bstudents?\b/i],
  ['attendance', /\battendance\b/i],
  ['points', /\bpoints?\b/i],
  ['badge', /\bbadges?\b/i],
  ['portfolio', /\bportfolios?\b/i],
  ['announcement', /\bannouncements?\b/i],
  ['tutor', /\btutor(s|ing)?\b/i],
  ['notification', /\bnotifications?\b/i],
  ['calendar', /\bcalendars?\b/i],
  ['sign in', /\bsign(ed|ing)?[- ]in\b/i],
  ['sign out', /\bsign(ed|ing)?[- ]out\b/i],
];

const STOP = new Set(["እዚህ", "እባክዎ", "አለበት", "ይሞክሩ", "እንደገና", "ነው"]);
const PREFIXES = ['የ', 'በ', 'ለ', 'ከ', 'ወደ'];

/** Amharic text without ICU syntax and placeholders, split into word stems. */
export function amharicWords(text) {
  const plain = stripIcuSyntax(text);
  const words = plain.split(/[\s፡-፨.,;:!?()"“”'…/\\\-–—]+/u).filter((w) => /[ሀ-፿]/.test(w));
  const out = new Set();
  for (const w of words) {
    out.add(w);
    for (const p of PREFIXES) if (w.startsWith(p) && w.length - p.length >= 2) out.add(w.slice(p.length));
  }
  return [...out];
}

export function isIcuSimple(text) {
  return parseIcu(text).args.size === 0;
}

/** Candidate Amharic terms of a text: single word stems plus adjacent word pairs. */
function candidateTerms(text) {
  const words = stripIcuSyntax(text).split(/[\s\u1361-\u1368.,;:!?()"'…/\\\-]+/u).filter((w) => /[\u1200-\u137F]/.test(w));
  const out = new Set(amharicWords(text));
  for (let i = 0; i + 1 < words.length; i++) out.add(words[i] + ' ' + words[i + 1]);
  return out;
}

/**
 * rows: [{ english, amharic_current }]. Returns [{ term, exact: [{english, amharic}], candidates: [{word, count, total}] }].
 * `exact` are rows whose whole English text is the term (a label); `candidates` are associated Amharic words.
 */
export function glossaryCandidates(rows) {
  const usable = rows.filter((r) => r.english && r.amharic_current);
  const docs = usable.map((r) => ({ en: r.english, words: candidateTerms(r.amharic_current) }));
  return TERMS.map(([term, re]) => {
    const whole = new RegExp('^(my |the )?' + re.source.replace(/\\b/g, '') + '$', 'i');
    const withTerm = docs.filter((d) => re.test(d.en));
    const without = docs.filter((d) => !re.test(d.en));
    const counts = new Map();
    for (const d of withTerm) for (const w of d.words) counts.set(w, (counts.get(w) ?? 0) + 1);
    const outCounts = new Map();
    for (const d of without) for (const w of d.words) outCounts.set(w, (outCounts.get(w) ?? 0) + 1);
    const scored = [...counts]
      .filter(([w, c]) => c >= 2 && [...w].length >= 3 && !STOP.has(w))
      .map(([word, count]) => {
        const lift = count / withTerm.length - (outCounts.get(word) ?? 0) / Math.max(1, without.length);
        return { word, count, total: withTerm.length, lift };
      })
      .filter((c, _i, all) => !all.some((o) => o !== c && o.word.length > c.word.length && o.count >= c.count && o.word.includes(c.word) && o.lift >= c.lift * 0.8))
      .sort((a, b) => b.lift - a.lift || b.count - a.count);
    const exact = usable.filter((r) => whole.test(r.english.trim())).map((r) => ({ english: r.english, amharic: r.amharic_current }));
    return { term, exact, candidates: scored.slice(0, 3) };
  });
}

#!/usr/bin/env node
// Automatic consistency checks over the current Amharic text. Only reports; fixes nothing.
//   node scripts/i18n/consistency.mjs [--root <repo>] [--out <md file>]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { REPO_ROOT, stripIcuSyntax } from './lib.mjs';
import { buildRows } from './export-review.mjs';
import { glossaryCandidates } from './glossary.mjs';

// Known alternative spellings of the same word. [variant A, variant B, note]
const SPELLING_VARIANTS = [
  ['ዓይነት', 'አይነት', 'type/kind (ዓ vs አ)'],
  ['ዓመት', 'አመት', 'year (ዓ vs አ)'],
  ['ሰዓት', 'ሰአት', 'hour/clock (ዓ vs አ)'],
  ['ሠራተኛ', 'ሰራተኛ', 'worker/staff (ሠ vs ሰ)'],
  ['ሥራ', 'ስራ', 'work (ሥ vs ስ)'],
  ['ሕዝብ', 'ህዝብ', 'people (ሕ vs ህ)'],
  ['ስሕተት', 'ስህተት', 'error (ሕ vs ህ)'],
  ['ነፃ', 'ነጻ', 'free (ፃ vs ጻ)'],
  ['ኅዳር', 'ሕዳር', 'month Hidar'],
  ['ታኅሣሥ', 'ታህሳስ', 'month Tahsas'],
  ['ኃላፊ', 'ሃላፊ', 'responsible/head (ኃ vs ሃ)'],
  ['ኃላፊ', 'ኀላፊ', 'responsible/head (ኃ vs ኀ)'],
  ['ኢሜይል', 'ኢሜል', 'email'],
  ['ጥቅም', 'ጥቅም', ''],
  ['ሐምሌ', 'ሃምሌ', 'month Hamle'],
  ['ነሐሴ', 'ነሃሴ', 'month Nehase'],
  ['መሣሪያ', 'መሳሪያ', 'device (ሣ vs ሳ)'],
  ['ቀን', 'ቀን', ''],
].filter(([a, b]) => a !== b);

const countOf = (rows, needle) => rows.filter((r) => r.amharic_current.includes(needle));

export function findings(rows) {
  const am = rows.filter((r) => r.amharic_current.trim());
  const out = [];
  const section = (title, items, intro = '') => out.push({ title, items, intro });

  // 1. Same English text, different Amharic
  const byEn = new Map();
  for (const r of am) {
    const k = r.english.trim().toLowerCase().replace(/[.!?…]+$/, '');
    if (!/[a-z]/i.test(k) || r.source === 'calendar' || r.source === 'seed') continue;
    if (!byEn.has(k)) byEn.set(k, []);
    byEn.get(k).push(r);
  }
  const dup = [...byEn].map(([en, rs]) => ({ en, variants: new Map(rs.map((r) => [r.amharic_current.trim().replace(/[።.]+$/, ''), []])), rs }))
    .filter((d) => d.variants.size > 1);
  for (const d of dup) for (const r of d.rs) d.variants.get(r.amharic_current.trim().replace(/[።.]+$/, '')).push(r.id);
  section('Same English text translated differently', dup.map((d) =>
    `- "${d.en}": ${[...d.variants].map(([a, ids]) => `**${a}** (${ids.join(', ')})`).join(' vs ')}`),
  'Identical English wording that has more than one Amharic version (ignoring a final full stop).');

  // 2. Spelling variants
  const spell = [];
  for (const [a, b, note] of SPELLING_VARIANTS) {
    const ra = countOf(am, a);
    const rb = countOf(am, b);
    if (ra.length && rb.length) {
      spell.push(`- ${note}: **${a}** (${ra.length}: ${ra.slice(0, 3).map((r) => r.id).join(', ')}${ra.length > 3 ? ', ...' : ''}) vs **${b}** (${rb.length}: ${rb.slice(0, 3).map((r) => r.id).join(', ')}${rb.length > 3 ? ', ...' : ''})`);
    }
  }
  section('Alternative spellings of the same word used in different places', spell,
    'Both spellings appear in the app. Substring match, so inflected forms are included.');

  // 3. Glossary terms with several competing words
  const g = glossaryCandidates(rows).filter((t) => t.candidates.length >= 2 && t.candidates[1].count >= Math.max(2, t.candidates[0].count / 3));
  section('Recurring terms with competing translations (heuristic)', g.map((t) =>
    `- "${t.term}": ${t.candidates.map((c) => `**${c.word}** (${c.count} of ${c.total} texts)`).join(', ')}`),
  'Automatic, approximate: words that co-occur with the English term. Treat as pointers to check, not as proof.');

  // 4. Sentence-ending punctuation
  const endAscii = am.filter((r) => /\.$/.test(r.amharic_current.trim()) && !/\.\.\.$/.test(r.amharic_current) && /[ሀ-፿]\.$/.test(r.amharic_current.trim()));
  const endEth = am.filter((r) => /።$/.test(r.amharic_current.trim()));
  const hasEnDot = (r) => /\.$/.test(r.english.trim());
  const missingStop = am.filter((r) => hasEnDot(r) && !/[።.]$/.test(r.amharic_current.trim()) && !/[?፧!]$/.test(r.amharic_current.trim()));
  const extraStop = am.filter((r) => !hasEnDot(r) && /።$/.test(r.amharic_current.trim()) && !/[?!]$/.test(r.english.trim()));
  const midAscii = am.filter((r) => /[ሀ-፿]\.\s+[ሀ-፿]/.test(r.amharic_current));
  section('Sentence-ending punctuation', [
    `- Ends with Ethiopic full stop "።": ${endEth.length} rows. Ends with ASCII "." after Ethiopic text: ${endAscii.length} rows${endAscii.length ? ` (${endAscii.slice(0, 8).map((r) => r.id).join(', ')}${endAscii.length > 8 ? ', ...' : ''})` : ''}.`,
    `- English ends with a full stop but Amharic has none: ${missingStop.length} rows${missingStop.length ? ` (${missingStop.slice(0, 8).map((r) => r.id).join(', ')}${missingStop.length > 8 ? ', ...' : ''})` : ''}.`,
    `- Amharic ends with "።" although the English has no full stop (labels/titles): ${extraStop.length} rows${extraStop.length ? ` (${extraStop.slice(0, 8).map((r) => r.id).join(', ')}${extraStop.length > 8 ? ', ...' : ''})` : ''}.`,
    `- ASCII "." used between Ethiopic sentences: ${midAscii.length} rows${midAscii.length ? ` (${midAscii.slice(0, 8).map((r) => r.id).join(', ')})` : ''}.`,
  ]);

  // 5. Other punctuation
  const comma = { eth: am.filter((r) => /፣/.test(r.amharic_current)), ascii: am.filter((r) => /[ሀ-፿],/.test(r.amharic_current)) };
  const colon = { eth: am.filter((r) => /፦|፡/.test(r.amharic_current)), ascii: am.filter((r) => /[ሀ-፿]:/.test(r.amharic_current)) };
  const quest = { eth: am.filter((r) => /፧/.test(r.amharic_current)), ascii: am.filter((r) => /\?/.test(r.amharic_current)) };
  const fmt = (rs) => `${rs.length}${rs.length ? ` (${rs.slice(0, 5).map((r) => r.id).join(', ')}${rs.length > 5 ? ', ...' : ''})` : ''}`;
  section('Comma, colon and question mark styles', [
    `- Comma: Ethiopic "፣" in ${comma.eth.length} rows, ASCII "," after Ethiopic text in ${fmt(comma.ascii)}.`,
    `- Colon: Ethiopic "፦"/"፡" in ${colon.eth.length} rows, ASCII ":" after Ethiopic text in ${fmt(colon.ascii)}.`,
    `- Question mark: Ethiopic "፧" in ${quest.eth.length} rows, ASCII "?" in ${fmt(quest.ascii)}.`,
  ]);

  // 6. Digits
  const eth = /[፩-፼]/;
  const ethRows = am.filter((r) => eth.test(r.amharic_current));
  const asciiRows = am.filter((r) => /[0-9]/.test(stripIcuSyntax(r.amharic_current)));
  section('Digit systems', [
    `- Ethiopic numerals (፩ ፪ ...) in ${ethRows.length} rows${ethRows.length ? `: ${ethRows.slice(0, 8).map((r) => r.id).join(', ')}` : ''}.`,
    `- ASCII digits (0-9) in ${asciiRows.length} rows. ${ethRows.length && asciiRows.length ? 'Both systems are in use: decide on one (the app shows Western digits elsewhere).' : 'Only ASCII digits are used, which is consistent.'}`,
  ]);

  // 7. Honorific / tone mix
  const polite = am.filter((r) => /እባክዎ|ዎ(?=[\s።፣]|$)|ይበሉ|ያስገቡ|ይምረጡ|ይሞክሩ/.test(r.amharic_current));
  const plainImp = am.filter((r) => /እባክህ|እባክሽ/.test(r.amharic_current));
  const tuRows = am.filter((r) => /^[ሀ-፿]+$/.test(r.amharic_current.trim()) && r.source === 'web' && r.english.split(/\s+/).length <= 2);
  section('Tone: polite plural (እርስዎ) versus bare commands', [
    `- Polite/honorific forms (እባክዎ, ...ዎ, ይሞክሩ, ይምረጡ, ...): ${polite.length} rows. Informal address (እባክህ/እባክሽ): ${plainImp.length} rows.`,
    `- Very short web labels (1 to 2 English words) in the bare 2nd person singular command form (e.g. "አስቀምጥ", "ሰርዝ"): ${tuRows.length} rows. This is normal for buttons, but check that buttons and sentences read well together; the app addresses users as "እርስዎ" in sentences.`,
  ]);

  // 9. Latin leftovers
  const latin = rows.filter((r) => r._flags.some((f) => f.includes('English letters') || f.includes('IDENTICAL')));
  section('English words left inside Amharic text', latin.map((r) => `- \`${r.id}\`: ${r._flags[0]}`),
    'Technical tokens (QR, API, JSON, THUMB...) may be fine to keep; the brand name "School" is used untranslated in several places (see "Brand name" below).');

  // 10. Era notation
  const era = am.filter((r) => /ዓ\/ም|ዓ\.ም|ዓም/.test(r.amharic_current));
  section('Era notation (ዓ.ም.)', era.map((r) => `- \`${r.id}\`: ${r.amharic_current}`));

  // 11. Brand name
  const brand = am.filter((r) => /School/.test(r.amharic_current));
  const brandAm = am.filter((r) => /ስኩል/.test(r.amharic_current));
  section('Brand name', [`- "School" kept in Latin letters in ${brand.length} rows; transliterated "ስኩል" in ${brandAm.length} rows. Latin attached to Amharic prefixes is also written without a separator ("የSchool", "ወደ School", "በSchool"): spacing is inconsistent.`]);

  // 12. Different word for "or"/"and" etc. skipped. Plural forms of nouns
  const lengthy = am.filter((r) => r.source === 'web' && r.english.length > 0 && r.amharic_current.length > r.english.length * 1.8 && r.english.length < 40);
  section('Amharic much longer than English for short UI text (may not fit buttons)', lengthy.slice(0, 25).map((r) => `- \`${r.id}\`: ${r.english} (${r.english.length}) vs ${r.amharic_current} (${r.amharic_current.length}) characters`));

  return out;
}

function render(rows) {
  const parts = ['# Amharic consistency findings', '',
    'Generated by `node scripts/i18n/consistency.mjs`. These are automatic observations for the reviewer and developers; **nothing was changed**. Heuristics can be wrong; please judge each one.', ''];
  for (const s of findings(rows)) {
    parts.push(`## ${s.title}`, '');
    if (s.intro) parts.push(s.intro, '');
    parts.push(s.items.length ? s.items.join('\n') : '_No findings._', '');
  }
  return parts.join('\n');
}

function main() {
  const args = process.argv.slice(2);
  const opt = (n) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : undefined; };
  const root = path.resolve(opt('--root') ?? REPO_ROOT);
  const out = path.resolve(opt('--out') ?? path.join(root, 'docs/i18n/review/consistency-findings.md'));
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, render(buildRows(root)));
  console.log(`Wrote ${out}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();

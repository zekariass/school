#!/usr/bin/env node
// Exports every Amharic string of the platform into one review spreadsheet (CSV).
//   node scripts/i18n/export-review.mjs [--root <repo>] [--out <csv file>]
//                                       [--update-guide] [--update-readme]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  PATHS, REPO_ROOT, flatten, readJson, parseProperties, readCalendar, readSeeds,
  placeholderList, explainIcu, toCsv, stripIcuSyntax,
} from './lib.mjs';
import { glossaryCandidates } from './glossary.mjs';

const BUTTONISH = /^(save|cancel|continue|back|done|edit|delete|retry|next|submit|close|open|add|remove|send|confirm|apply|search|reset|upload|download|create|update|view|share|skip|finish|approve|reject|resend)/i;

function describeWeb(key, english) {
  const parts = key.split('.');
  const leaf = parts.at(-1);
  const area = parts[0];
  const where = `Web and mobile app, "${area}" section (key ${key}).`;
  let kind;
  if (/title$|heading$/i.test(leaf)) kind = 'A title or heading.';
  else if (/(error|failed|invalid|denied)/i.test(leaf) || /error/i.test(parts.at(-2) ?? '')) kind = 'An error or warning message.';
  else if (/hint|placeholder/i.test(leaf)) kind = 'A hint or placeholder shown inside or under an input field.';
  else if (/subtitle|body|description|message|intro|explain|help/i.test(leaf) || english.length > 90) kind = 'A longer explanatory text (full sentence(s)).';
  else if (BUTTONISH.test(english) && english.split(/\s+/).length <= 3) kind = 'A button or action label. Keep it very short; use the command form.';
  else if (english.split(/\s+/).length <= 3 && !/[.!?]$/.test(english)) kind = 'A short label (menu item, field name, tab or column heading).';
  else kind = 'A sentence or short phrase.';
  return `${where} ${kind}`;
}

function describeServer(key, english, comment) {
  let kind;
  if (/^error\.title\.\d+$/.test(key)) kind = `Server message: short headline for HTTP error ${key.split('.').at(-1)} (shown above the detailed error).`;
  else if (key.startsWith('error.')) kind = 'Server message: error shown to the user when an action fails. Written as a calm explanation of what went wrong.';
  else if (key.startsWith('validation.')) kind = 'Server message: form validation hint shown next to a field.';
  else if (/^notification\..*\.label$/.test(key)) kind = 'Server message: name of a notification type, shown in the notification settings list.';
  else if (/^notification\..*\.title$/.test(key)) kind = 'Server message: title of a push notification or in-app notification (short; shown on the phone lock screen).';
  else if (/^notification\..*\.body$/.test(key)) kind = 'Server message: body text of a push notification (one short sentence).';
  else if (/^notification\.category\./.test(key)) kind = 'Server message: group heading in notification settings.';
  else if (/^auth\.otp\.email\.subject/.test(key)) kind = 'Server message: subject line of the sign-in code email.';
  else if (/^auth\.otp\.email/.test(key)) kind = 'Server message: text of the sign-in code email. "\\n" is a line break and is kept by the program.';
  else if (/^auth\.otp\.sms/.test(key)) kind = 'Server message: SMS text with the sign-in code (keep it short; SMS is length-limited).';
  else kind = 'Server message (sent by the server, shown in the app or in an email/SMS).';
  let note = `${kind} (key ${key}).`;
  if (/\{\d+/.test(english)) {
    note += ' {0}, {1}, ... are filled in by the server in this order';
    const meanings = [...(comment ?? '').matchAll(/\{(\d)\}\s+([^,]+)/g)].map((m) => `{${m[1]}} = ${m[2].trim()}`);
    if (meanings.length) note += ` (${meanings.join('; ')})`;
    note += '. You may reorder them inside the sentence but keep each one exactly once.';
  }
  return note;
}

function leftoverLatin(amharic) {
  const stripped = stripIcuSyntax(amharic);
  return [...stripped.matchAll(/[A-Za-z]{2,}/g)].map((m) => m[0]);
}

export function buildRows(root = REPO_ROOT) {
  const rows = [];
  const push = (r) => {
    const flags = [];
    if (!r.amharic_current.trim()) flags.push('Current Amharic is EMPTY.');
    else if (r.amharic_current.trim() === r.english.trim() && /[A-Za-z]{2,}/.test(r.english)) flags.push('Current Amharic is IDENTICAL to English.');
    else {
      const latin = leftoverLatin(r.amharic_current);
      if (latin.length) flags.push(`Current Amharic still contains English letters: ${[...new Set(latin)].join(', ')}.`);
    }
    const icu = r.source === 'web' ? explainIcu(r.english) : [];
    rows.push({
      reviewed: '', reviewer_comment: '', status: '',
      ...r,
      placeholders: r.source === 'web' || r.source === 'server' ? placeholderList(r.english) : '',
      context_note: [...flags.map((f) => `CHECK: ${f}`), r.context_note, ...icu].join(' '),
      amharic_reviewed: '',
      _flags: flags,
    });
  };

  // --- web / mobile catalogs
  const en = readJson(path.join(root, PATHS.en)).data;
  const am = readJson(path.join(root, PATHS.am)).data;
  const enFlat = flatten(en);
  const amFlat = flatten(am);
  for (const [key, english] of enFlat) {
    if (key.split('.').some((p) => p.includes('.'))) throw new Error(`key part with dot: ${key}`);
    push({
      id: `web:${key}`, source: 'web', area: key.split('.')[0], english,
      amharic_current: amFlat.get(key) ?? '', context_note: describeWeb(key, english),
    });
  }

  // --- server messages
  const pEn = parseProperties(fs.readFileSync(path.join(root, PATHS.propsEn), 'utf8')).entries;
  const pAm = parseProperties(fs.readFileSync(path.join(root, PATHS.propsAm), 'utf8')).entries;
  const typeComments = new Map();
  for (const [key, e] of pEn) if (e.comment) typeComments.set(key.slice(0, key.lastIndexOf('.')), e.comment);
  for (const [key, e] of pEn) {
    const segs = key.split('.');
    const area = segs[0] === 'error' && segs.length > 2 ? `error.${segs[1]}` : segs[0];
    push({
      id: `server:${key}`, source: 'server', area, english: e.value,
      amharic_current: pAm.get(key)?.value ?? '',
      context_note: describeServer(key, e.value, e.comment || typeComments.get(key.slice(0, key.lastIndexOf('.')))),
    });
  }

  // --- calendar
  const cal = readCalendar(root);
  const calRow = (id, english, amharic, note) =>
    push({ id: `calendar:${id}`, source: 'calendar', area: 'calendar', english, amharic_current: amharic, context_note: note });
  cal.months.en.forEach((m, i) => calRow(`month.${i + 1}`, m, cal.months.am[i] ?? '', `Ethiopian calendar month ${i + 1} of 13 (the English is the common transliteration, not a translation). Spelling variants exist (e.g. ኅዳር/ሕዳር); please choose the one schools expect. Edit is manual: packages/calendar/src/format.ts.`));
  cal.weekdays.en.forEach((d, i) => calRow(`weekday.${i}`, d, cal.weekdays.am[i] ?? '', `Day of the week (${d}), used in dates and timetables. Edit is manual: packages/calendar/src/format.ts.`));
  if (cal.era) calRow('era', cal.era.en, cal.era.am, 'Era suffix written after the Ethiopian year (like "E.C." in English). Edit is manual.');
  if (cal.dayWord) calRow('long_date.day_word', 'day', cal.dayWord, 'Word placed after the day number in long dates, e.g. "መስከረም 1 ቀን 2018 ዓ.ም." (English has no such word). Edit is manual.');

  // --- seeds
  for (const s of readSeeds(root)) {
    push({
      id: `seed:${s.id}`, source: 'seed', area: s.area, english: s.english, amharic_current: s.amharic,
      context_note: `Name stored in the database (seed file ${s.file}, line ${s.line}); shown in lists and pickers. Edit is manual.`,
    });
  }

  rows.sort((a, b) => (a.area < b.area ? -1 : a.area > b.area ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return rows;
}

export function summarize(rows) {
  const byArea = new Map();
  for (const r of rows) byArea.set(r.area, (byArea.get(r.area) ?? 0) + 1);
  const bySource = new Map();
  for (const r of rows) bySource.set(r.source, (bySource.get(r.source) ?? 0) + 1);
  const empty = rows.filter((r) => r._flags.some((f) => f.includes('EMPTY')));
  const identical = rows.filter((r) => r._flags.some((f) => f.includes('IDENTICAL')));
  const latin = rows.filter((r) => r._flags.some((f) => f.includes('English letters')));
  return { total: rows.length, byArea, bySource, empty, identical, latin };
}

function readmeText(rows) {
  const s = summarize(rows);
  const list = (rs) => (rs.length ? rs.map((r) => `- \`${r.id}\`: ${r.english} → ${r.amharic_current || '(empty)'}`).join('\n') : '- none');
  const areaTable = [...s.byArea].sort(([a], [b]) => (a < b ? -1 : 1)).map(([a, n]) => `| ${a} | ${n} |`).join('\n');
  const srcTable = [...s.bySource].map(([a, n]) => `| ${a} | ${n} |`).join('\n');
  return `# Amharic review pack: summary

Generated by \`node scripts/i18n/export-review.mjs --update-readme\`. Do not edit by hand.

- Spreadsheet to review: [amharic-review.csv](amharic-review.csv) (UTF-8 with BOM; opens correctly in Excel and Google Sheets)
- Reviewer guide: [../REVIEWING.md](../REVIEWING.md)
- Automatic consistency findings (not fixed): [consistency-findings.md](consistency-findings.md)

**Total rows: ${s.total}**

## Rows per source

| Source | Rows |
| --- | ---: |
${srcTable}

## Rows per area

| Area | Rows |
| --- | ---: |
${areaTable}

## Rows needing special attention

- Current Amharic empty: **${s.empty.length}**
- Current Amharic identical to English: **${s.identical.length}**
- Likely English leftovers (Latin letters inside the Amharic text): **${s.latin.length}**

### Empty
${list(s.empty)}

### Identical to English
${list(s.identical)}

### Likely English leftovers
${list(s.latin)}

## Notes for the Excel / Google Sheets user

Open the CSV via *File > Import* (Sheets) or *Data > From Text/CSV* with encoding UTF-8 (Excel), then save as \`.xlsx\` while you work. When finished, export back to **CSV UTF-8** (Excel: "CSV UTF-8 (Comma delimited)"; Sheets: File > Download > CSV) and hand that file back; see [REVIEWING.md](../REVIEWING.md).
`;
}

function glossaryMarkdown(rows) {
  const g = glossaryCandidates(rows);
  const lines = ['| English term | Amharic used now (auto-detected, most frequent) | Other forms seen | Your preferred Amharic | Notes |', '| --- | --- | --- | --- | --- |'];
  for (const { term, exact, candidates } of g) {
    const main = exact.length ? [...new Map(exact.map((e) => [e.amharic, e])).values()].map((e) => e.amharic + ' ("' + e.english + '")').join('; ') : candidates[0] ? candidates[0].word + ' (' + candidates[0].count + '/' + candidates[0].total + ' texts, no stand-alone label)' : '-';
    const others = candidates.slice(exact.length ? 0 : 1).map((c) => c.word + ' (' + c.count + ')').join(', ') || '-';
    lines.push(`| ${term} | ${main} | ${others} |  |  |`);
  }
  return lines.join('\n');
}

export function updateGuide(root, rows) {
  const file = path.join(root, 'docs/i18n/REVIEWING.md');
  const text = fs.readFileSync(file, 'utf8');
  const re = /(<!-- glossary:start -->)[\s\S]*?(<!-- glossary:end -->)/;
  if (!re.test(text)) throw new Error('glossary markers missing in REVIEWING.md');
  fs.writeFileSync(file, text.replace(re, `$1\n${glossaryMarkdown(rows)}\n$2`));
}

function main() {
  const args = process.argv.slice(2);
  const opt = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
  const root = path.resolve(opt('--root') ?? REPO_ROOT);
  const out = path.resolve(opt('--out') ?? path.join(root, 'docs/i18n/review/amharic-review.csv'));
  const rows = buildRows(root);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, toCsv(rows), 'utf8');
  const s = summarize(rows);
  console.log(`Wrote ${rows.length} rows to ${path.relative(process.cwd(), out)}`);
  for (const [a, n] of [...s.byArea].sort()) console.log(`  ${a}: ${n}`);
  console.log(`empty: ${s.empty.length}, identical to English: ${s.identical.length}, English leftovers: ${s.latin.length}`);
  if (args.includes('--update-readme')) fs.writeFileSync(path.join(path.dirname(out), 'README.md'), readmeText(rows));
  if (args.includes('--update-guide')) updateGuide(root, rows);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();

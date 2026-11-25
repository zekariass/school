#!/usr/bin/env node
// Applies a reviewed CSV (see export-review.mjs) back into the source files.
//   node scripts/i18n/apply-review.mjs <reviewed.csv> [--dry-run | --write] [--root <repo>] [--report <file>]
// Default is --dry-run. Rows with status CHANGED and a non-empty amharic_reviewed are applied.
// Catalog (web:) and server messages (server:) are written. Calendar and seed rows are
// listed in a report because they are not simple key/value files.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  PATHS, REPO_ROOT, flatten, parseCsv, parseIcu, parseProperties,
  serializeJson, setByPath, setProperty, readCalendar, readSeeds,
} from './lib.mjs';

const STATUSES = new Set(['OK', 'CHANGED', 'UNSURE']);

function sameSet(a, b) {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

/** Returns a list of human-readable problems for a proposed Amharic text (empty when valid). */
export function validateTranslation(english, amharic) {
  const problems = [];
  if (!amharic.trim()) return ['the Amharic text is empty'];
  if (amharic.includes('�')) problems.push('contains a broken character (U+FFFD): the file was probably saved in the wrong encoding');
  if (/[\r\n]/.test(amharic) && !/[\r\n]/.test(english)) problems.push('contains a line break but the English does not');
  const e = parseIcu(english);
  const a = parseIcu(amharic);
  for (const err of a.errors) problems.push(`braces/format: ${err}`);
  const en = [...e.args.keys()].sort();
  const am = [...a.args.keys()].sort();
  if (!sameSet(en, am)) {
    const missing = en.filter((x) => !am.includes(x));
    const extra = am.filter((x) => !en.includes(x));
    if (missing.length) problems.push(`missing placeholder(s): ${missing.map((x) => `{${x}}`).join(', ')}`);
    if (extra.length) problems.push(`unknown placeholder(s): ${extra.map((x) => `{${x}}`).join(', ')}`);
  }
  for (const name of en) {
    if (!a.args.has(name)) continue;
    const te = e.args.get(name);
    const ta = a.args.get(name);
    if (te !== ta) problems.push(`{${name}} must stay "${te}" but is "${ta}"`);
    if (['plural', 'select', 'selectordinal'].includes(te) && ta === te) {
      const ke = [...(e.selectors.get(name) ?? [])].sort();
      const ka = [...(a.selectors.get(name) ?? [])].sort();
      // Amharic may omit optional exact (=N) branches only if English has none either
      if (!sameSet(ke, ka)) problems.push(`{${name}} branches must be ${ke.join(', ')} but are ${ka.join(', ')}`);
    }
  }
  return problems;
}

export function applyReview({ csvText, root = REPO_ROOT }) {
  const rows = parseCsv(csvText);
  const problems = [];
  const warnings = [];
  const manual = [];
  const planned = []; // {id, source, key, english, current, next}
  const seen = new Map();

  const enJson = JSON.parse(fs.readFileSync(path.join(root, PATHS.en), 'utf8'));
  const amText = fs.readFileSync(path.join(root, PATHS.am), 'utf8');
  const amJson = JSON.parse(amText);
  const enFlat = flatten(enJson);
  const amFlat = flatten(amJson);
  if (serializeJson(amJson) !== amText) problems.push(`${PATHS.am} is not in canonical 2-space JSON format; refusing to rewrite it`);
  const propsEnText = fs.readFileSync(path.join(root, PATHS.propsEn), 'utf8');
  const propsAmText = fs.readFileSync(path.join(root, PATHS.propsAm), 'utf8');
  const propsEn = parseProperties(propsEnText).entries;
  const propsAm = parseProperties(propsAmText).entries;
  const cal = readCalendar(root);
  const seeds = new Map(readSeeds(root).map((s) => [`seed:${s.id}`, s]));

  let considered = 0;
  rows.forEach((row, idx) => {
    const line = idx + 2;
    const id = (row.id ?? '').trim();
    const status = (row.status ?? '').trim().toUpperCase();
    const reviewed = (row.amharic_reviewed ?? '').trim();
    const label = `row ${line} (${id || 'no id'})`;
    if (!id) { if (status || reviewed) problems.push(`${label}: has review data but no id`); return; }
    if (status && !STATUSES.has(status)) problems.push(`${label}: status "${row.status}" must be OK, CHANGED or UNSURE`);
    if (status !== 'CHANGED') {
      if (reviewed && status !== 'UNSURE') warnings.push(`${label}: has amharic_reviewed but status is "${status || 'empty'}"; ignored (set status to CHANGED to apply it)`);
      return;
    }
    if (!reviewed) { problems.push(`${label}: status is CHANGED but amharic_reviewed is empty`); return; }
    considered++;
    if (seen.has(id)) { problems.push(`${label}: duplicate id (first seen at row ${seen.get(id)})`); return; }
    seen.set(id, line);

    const colon = id.indexOf(':');
    const source = id.slice(0, colon);
    const key = id.slice(colon + 1);
    let english;
    let current;
    if (source === 'web') {
      english = enFlat.get(key);
      current = amFlat.get(key);
    } else if (source === 'server') {
      english = propsEn.get(key)?.value;
      current = propsAm.get(key)?.value;
      if (english !== undefined && current === undefined) { problems.push(`${label}: key missing in ${PATHS.propsAm}`); return; }
    } else if (source === 'calendar') {
      english = row.english;
      current = row.amharic_current;
    } else if (source === 'seed') {
      const s = seeds.get(id);
      english = s?.english;
      current = s?.amharic;
    } else { problems.push(`${label}: unknown source "${source}"`); return; }

    if (english === undefined) { problems.push(`${label}: id not found in the source files (was the CSV exported from an older version?)`); return; }
    if ((row.english ?? '') !== english) warnings.push(`${label}: English text in the CSV differs from the current source; the CSV may be out of date`);
    if (current !== undefined && (row.amharic_current ?? '') !== current) warnings.push(`${label}: current Amharic in the CSV differs from the source (someone changed it since the export?)`);

    if (source === 'web' || source === 'server') {
      const bad = validateTranslation(english, reviewed);
      for (const b of bad) problems.push(`${label}: ${b}`);
      if (!bad.length) planned.push({ id, source, key, english, current, next: reviewed });
    } else {
      if (!reviewed) problems.push(`${label}: empty`);
      else manual.push({ id, source, english, current, next: reviewed });
    }
  });

  // Apply in memory.
  let amOut = amText;
  let propsOut = propsAmText;
  if (!problems.length) {
    for (const p of planned) {
      if (p.source === 'web') setByPath(amJson, p.key, p.next);
      else propsOut = setProperty(propsOut, p.key, p.next, p.english);
    }
    amOut = serializeJson(amJson);
    // Safety net: the key sets must still be identical to English.
    const ak = [...flatten(amJson).keys()].sort().join('\n');
    if (ak !== [...enFlat.keys()].sort().join('\n')) problems.push('internal error: catalog keys changed');
    const re = parseProperties(propsOut).entries;
    if ([...re.keys()].sort().join('\n') !== [...propsEn.keys()].sort().join('\n')) problems.push('internal error: server message keys changed');
    for (const p of planned.filter((x) => x.source === 'server')) {
      if (re.get(p.key)?.value !== p.next.replace(/\s+$/, '')) problems.push(`internal error: ${p.id} did not round-trip`);
    }
  }

  return { rows: rows.length, considered, planned, manual, problems, warnings, amOut, propsOut, cal };
}

function manualReport(manual) {
  const lines = ['# Manual edits needed after the Amharic review', '',
    'These rows are not simple key/value files, so they were NOT changed automatically. A developer must edit them by hand.', ''];
  if (!manual.length) lines.push('_No calendar or seed rows were marked CHANGED._', '');
  for (const m of manual) {
    let where;
    if (m.source === 'calendar') where = `\`${PATHS.calendar}\` (ETHIOPIAN_MONTHS / ETHIOPIAN_WEEKDAYS / ERA / long-date template). Item: \`${m.id}\``;
    else where = `\`${PATHS.migrations}/R__*.sql\`, the "am" value of \`${m.id}\``;
    lines.push(`- \`${m.id}\`: "${m.english}"`, `  - current: ${m.current}`, `  - new: **${m.next}**`, `  - edit: ${where}`);
  }
  lines.push('', 'Reminder for seeds: several seed inserts use `on conflict ... do nothing`, so changing the SQL does not update databases that already ran it; use an update statement in a new migration or a repeatable `do update`. Escape apostrophes as `\'\'` in SQL.', '');
  return lines.join('\n');
}

function main() {
  const args = process.argv.slice(2);
  const opt = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
  const optValues = new Set(['--root', '--report'].map((n) => opt(n)).filter(Boolean));
  const file = args.find((a) => !a.startsWith('--') && !optValues.has(a));
  if (!file) { console.error('Usage: node scripts/i18n/apply-review.mjs <reviewed.csv> [--dry-run|--write] [--root <repo>] [--report <file>]'); process.exit(2); }
  const write = args.includes('--write') && !args.includes('--dry-run');
  const root = path.resolve(opt('--root') ?? REPO_ROOT);
  const result = applyReview({ csvText: fs.readFileSync(file, 'utf8'), root });

  console.log(`${result.rows} rows read, ${result.considered} marked CHANGED.`);
  for (const w of result.warnings) console.log(`warning: ${w}`);
  if (result.problems.length) {
    console.error(`\nREFUSED: ${result.problems.length} problem(s). Nothing was written.`);
    for (const p of result.problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  for (const p of result.planned) console.log(`${write ? 'applied' : 'would apply'} ${p.id}: ${p.current} -> ${p.next}`);
  const report = manualReport(result.manual);
  if (result.manual.length) console.log(`\n${result.manual.length} calendar/seed item(s) need a manual edit:\n${report}`);
  if (!write) { console.log('\nDry run: nothing written. Re-run with --write to apply.'); return; }
  if (result.planned.some((p) => p.source === 'web')) fs.writeFileSync(path.join(root, PATHS.am), result.amOut);
  if (result.planned.some((p) => p.source === 'server')) fs.writeFileSync(path.join(root, PATHS.propsAm), result.propsOut);
  const reportFile = path.resolve(opt('--report') ?? path.join(root, 'docs/i18n/review/manual-edits.md'));
  if (result.manual.length) { fs.mkdirSync(path.dirname(reportFile), { recursive: true }); fs.writeFileSync(reportFile, report); console.log(`Manual-edit report: ${reportFile}`); }
  console.log(`Wrote ${result.planned.length} change(s). Now run: npm run test -w @school/i18n  (and the backend MessagesTest).`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();

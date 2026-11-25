import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
  PATHS, REPO_ROOT, parseIcu, placeholderNames, placeholderList, parseCsv, toCsv,
  parseProperties, propsStore, propsDisplay, flatten,
} from './lib.mjs';
import { buildRows, summarize } from './export-review.mjs';
import { applyReview, validateTranslation } from './apply-review.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** Copies just the source files the scripts touch into a temporary root. */
function tempRoot() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-review-'));
  for (const rel of [PATHS.en, PATHS.am, PATHS.propsEn, PATHS.propsAm, PATHS.calendar]) {
    fs.mkdirSync(path.dirname(path.join(root, rel)), { recursive: true });
    fs.copyFileSync(path.join(REPO_ROOT, rel), path.join(root, rel));
  }
  const mig = path.join(root, PATHS.migrations);
  fs.mkdirSync(mig, { recursive: true });
  for (const f of fs.readdirSync(path.join(REPO_ROOT, PATHS.migrations)).filter((n) => n.startsWith('R__'))) {
    fs.copyFileSync(path.join(REPO_ROOT, PATHS.migrations, f), path.join(mig, f));
  }
  return root;
}

function csvWith(root, edits) {
  const rows = buildRows(root).map((r) => ({ ...r, ...(edits[r.id] ?? {}) }));
  return toCsv(rows);
}

test('placeholder extraction: simple, plural, select, MessageFormat', () => {
  assert.deepEqual(placeholderNames('Hello {name}, you have {count} items'), ['count', 'name']);
  assert.deepEqual(placeholderNames('{count, plural, one {# point} other {# points}} for {skill}'), ['count', 'skill']);
  assert.deepEqual(placeholderNames('{g, select, f {She {name}} other {They}}'), ['g', 'name']);
  assert.deepEqual(placeholderNames('{0} received {1} for {2}.'), ['0', '1', '2']);
  assert.equal(placeholderList('{b} {a}'), '{a}, {b}');
  assert.deepEqual(placeholderNames('No placeholders.'), []);
});

test('ICU apostrophe quoting: quoted braces are literal', () => {
  assert.deepEqual(placeholderNames("It''s '{not}' a placeholder {real}"), ['real']);
  assert.equal(parseIcu("It''s fine").balanced, true);
});

test('balance and plural checks', () => {
  assert.equal(parseIcu('{a').balanced, false);
  assert.equal(parseIcu('a}').balanced, false);
  assert.equal(parseIcu('{n, plural, one {x}}').balanced, false); // no "other"
  assert.equal(parseIcu('{n, plural, one {x} other {y}}').balanced, true);
});

test('.properties escapes and the doubled-apostrophe rule', () => {
  const text = 'a.b=Line one\\nLine two {0}\nc=It\'\'s {0}\nd=\\u12A0\\u121B\nlong=first \\\n   second\n';
  const { entries } = parseProperties(text);
  assert.equal(entries.get('a.b').value, 'Line one\nLine two {0}');
  assert.equal(entries.get('c').value, "It's {0}");
  assert.equal(entries.get('d').value, 'አማ');
  assert.equal(entries.get('long').value, 'first second');
  assert.equal(propsStore("It's {0}", 'x {0}'), "It''s {0}");
  assert.equal(propsStore("It's fine", 'x'), "It's fine"); // no arguments: no doubling
  assert.equal(propsStore('a\nb\\c', 'x'), 'a\\nb\\\\c');
  assert.equal(propsDisplay('a\\nb'), 'a\nb');
});

test('CSV round trip with BOM, quotes, commas and newlines', () => {
  const rows = [{ id: 'x', english: 'He said "hi", ok', amharic_current: 'a\nb', status: '' }];
  const csv = toCsv(rows);
  assert.equal(csv.charCodeAt(0), 0xfeff);
  const back = parseCsv(csv);
  assert.equal(back[0].english, 'He said "hi", ok');
  assert.equal(back[0].amharic_current, 'a\nb');
});

test('export: covers all sources, stable ids, sorted, nothing pre-filled', () => {
  const rows = buildRows(REPO_ROOT);
  const sources = new Set(rows.map((r) => r.source));
  assert.deepEqual([...sources].sort(), ['calendar', 'seed', 'server', 'web']);
  assert.equal(new Set(rows.map((r) => r.id)).size, rows.length, 'ids are unique');
  assert.ok(rows.some((r) => r.id === 'web:auth.signIn'));
  assert.ok(rows.some((r) => r.id === 'server:error.auth.invalid_code' || r.id.startsWith('server:error.auth.')));
  assert.ok(rows.some((r) => r.id === 'calendar:month.1'));
  assert.ok(rows.some((r) => r.id === 'seed:roles.SCHOOL_ADMIN'));
  const keys = rows.map((r) => `${r.area}\u0000${r.id}`);
  assert.deepEqual(keys, [...keys].sort());
  assert.ok(rows.every((r) => r.amharic_reviewed === '' && r.status === ''));
  const plural = rows.find((r) => r.id === 'web:points.awarded');
  assert.match(plural.context_note, /PLURAL on \{count\}/);
  assert.equal(plural.placeholders, '{count}, {skill}');
  assert.equal(summarize(rows).total, rows.length);
});

test('export covers every catalog key and every server key', () => {
  const rows = buildRows(REPO_ROOT);
  const en = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, PATHS.en), 'utf8'));
  assert.equal(rows.filter((r) => r.source === 'web').length, flatten(en).size);
  const props = parseProperties(fs.readFileSync(path.join(REPO_ROOT, PATHS.propsEn), 'utf8')).entries;
  assert.equal(rows.filter((r) => r.source === 'server').length, props.size);
});

test('apply: round trip of a web row, a server row, and a calendar/seed report', () => {
  const root = tempRoot();
  const web = 'web:auth.signIn';
  const plural = 'web:points.awarded';
  const server = 'server:notification.point_awarded.body';
  const csv = csvWith(root, {
    [web]: { status: 'CHANGED', amharic_reviewed: 'ይግቡ' },
    [plural]: { status: 'changed', amharic_reviewed: 'ለ{skill} {count, plural, one {# ነጥብ} other {# ነጥቦች}} ተሰጥቷል' },
    [server]: { status: 'CHANGED', amharic_reviewed: "{0} ለ{2} {1} ነጥብ አግኝቷል። (it's)" },
    'calendar:month.3': { status: 'CHANGED', amharic_reviewed: 'ሕዳር' },
    'seed:roles.SCHOOL_ADMIN': { status: 'CHANGED', amharic_reviewed: 'የትምህርት ቤት ሥራ አስኪያጅ' },
    'web:common.save': { status: 'OK' },
  });
  const beforeAm = fs.readFileSync(path.join(root, PATHS.am), 'utf8');
  const result = applyReview({ csvText: csv, root });
  assert.deepEqual(result.problems, []);
  assert.equal(result.planned.length, 3);
  assert.equal(result.manual.length, 2);

  // dry-run path (applyReview itself never writes)
  assert.equal(fs.readFileSync(path.join(root, PATHS.am), 'utf8'), beforeAm);

  fs.writeFileSync(path.join(root, PATHS.am), result.amOut);
  fs.writeFileSync(path.join(root, PATHS.propsAm), result.propsOut);

  const am = JSON.parse(fs.readFileSync(path.join(root, PATHS.am), 'utf8'));
  assert.equal(am.auth.signIn, 'ይግቡ');
  const diff = result.amOut.split('\n').filter((l, i) => l !== beforeAm.split('\n')[i]);
  assert.equal(diff.length, 2, 'only the two changed lines differ (formatting and key order preserved)');
  assert.deepEqual(Object.keys(am), Object.keys(JSON.parse(beforeAm)));

  const en = flatten(JSON.parse(fs.readFileSync(path.join(root, PATHS.en), 'utf8')));
  assert.deepEqual([...flatten(am).keys()], [...en.keys()], 'catalog key parity');

  const propsEn = parseProperties(fs.readFileSync(path.join(root, PATHS.propsEn), 'utf8')).entries;
  const propsAmText = fs.readFileSync(path.join(root, PATHS.propsAm), 'utf8');
  const propsAm = parseProperties(propsAmText).entries;
  assert.deepEqual([...propsAm.keys()].sort(), [...propsEn.keys()].sort(), 'server key parity');
  assert.equal(propsAm.get('notification.point_awarded.body').value, "{0} ለ{2} {1} ነጥብ አግኝቷል። (it's)");
  assert.match(propsAmText, /\(it''s\)/, 'apostrophe is doubled because the message takes arguments');

  // Re-exporting shows the new text
  const again = buildRows(root).find((r) => r.id === web);
  assert.equal(again.amharic_current, 'ይግቡ');
});

test('apply: refuses bad rows and lists every problem', () => {
  const root = tempRoot();
  const csv = csvWith(root, {
    'web:points.awarded': { status: 'CHANGED', amharic_reviewed: 'ለ{skill} {count, plural, one {# ነጥብ} other {# ነጥቦች}' }, // unbalanced
    'web:auth.enterCode': { status: 'CHANGED', amharic_reviewed: 'ኮዱን ያስገቡ {destination}' }, // missing {length}
    'web:common.save': { status: 'CHANGED', amharic_reviewed: 'አስቀምጥ {oops}' }, // unknown placeholder
    'web:common.cancel': { status: 'CHANGED', amharic_reviewed: '   ' }, // empty
    'web:common.done': { status: 'MAYBE', amharic_reviewed: 'x' }, // bad status
    'server:notification.point_awarded.body': { status: 'CHANGED', amharic_reviewed: '{0} {1}' }, // missing {2}
  });
  const result = applyReview({ csvText: csv, root });
  const text = result.problems.join('\n');
  assert.match(text, /web:points\.awarded[\s\S]*braces\/format/);
  assert.match(text, /web:auth\.enterCode.*missing placeholder\(s\): \{length\}/);
  assert.match(text, /web:common\.save.*unknown placeholder\(s\): \{oops\}/);
  assert.match(text, /web:common\.cancel.*amharic_reviewed is empty/);
  assert.match(text, /status "MAYBE"/);
  assert.match(text, /server:notification\.point_awarded\.body.*missing placeholder\(s\): \{2\}/);
});

test('apply: refuses unknown ids; plural branches must match', () => {
  const root = tempRoot();
  const rows = buildRows(root);
  const bogus = toCsv([{ ...rows[0], id: 'web:does.not.exist', status: 'CHANGED', amharic_reviewed: 'x' }]);
  assert.match(applyReview({ csvText: bogus, root }).problems.join('\n'), /id not found/);
  const prob = validateTranslation('{n, plural, one {# a} other {# b}}', '{n, plural, other {# ለ}}');
  assert.match(prob.join('\n'), /branches must be one, other/);
  assert.deepEqual(validateTranslation('{n, plural, one {# a} other {# b}}', '{n, plural, one {# አ} other {# ለ}}'), []);
});

test('CLI: dry-run is the default and writes nothing; refusal exits non-zero', () => {
  const root = tempRoot();
  const good = path.join(root, 'good.csv');
  fs.writeFileSync(good, csvWith(root, { 'web:auth.signIn': { status: 'CHANGED', amharic_reviewed: 'ይግቡ' } }));
  const before = fs.readFileSync(path.join(root, PATHS.am), 'utf8');
  const dry = spawnSync(process.execPath, [path.join(HERE, 'apply-review.mjs'), good, '--root', root], { encoding: 'utf8' });
  assert.equal(dry.status, 0, dry.stderr);
  assert.match(dry.stdout, /would apply web:auth\.signIn/);
  assert.equal(fs.readFileSync(path.join(root, PATHS.am), 'utf8'), before);

  const wr = spawnSync(process.execPath, [path.join(HERE, 'apply-review.mjs'), good, '--write', '--root', root], { encoding: 'utf8' });
  assert.equal(wr.status, 0, wr.stderr);
  assert.notEqual(fs.readFileSync(path.join(root, PATHS.am), 'utf8'), before);

  const bad = path.join(root, 'bad.csv');
  fs.writeFileSync(bad, csvWith(root, { 'web:common.save': { status: 'CHANGED', amharic_reviewed: '' } }));
  const refused = spawnSync(process.execPath, [path.join(HERE, 'apply-review.mjs'), bad, '--write', '--root', root], { encoding: 'utf8' });
  assert.equal(refused.status, 1);
  assert.match(refused.stderr, /REFUSED/);
});

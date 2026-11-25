// Shared helpers for the Amharic review pack. Plain Node, no dependencies.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

export const PATHS = {
  en: 'packages/i18n/messages/en.json',
  am: 'packages/i18n/messages/am.json',
  propsEn: 'apps/backend/src/main/resources/messages.properties',
  propsAm: 'apps/backend/src/main/resources/messages_am.properties',
  calendar: 'packages/calendar/src/format.ts',
  migrations: 'apps/backend/src/main/resources/db/migration',
};

export const COLUMNS = [
  'id', 'source', 'area', 'english', 'amharic_current', 'placeholders',
  'context_note', 'amharic_reviewed', 'reviewer_comment', 'status',
];

// ---------------------------------------------------------------- ICU parsing

/**
 * Parses an ICU MessageFormat string (or a java.text.MessageFormat one: {0}, {1,number}).
 * Returns { args: Map<name, type>, selectors: Map<name, string[]>, errors, balanced }.
 * Apostrophe quoting follows ICU: `''` is a literal quote, `'{` starts a quoted run.
 */
export function parseIcu(message) {
  const args = new Map();
  const selectors = new Map();
  const errors = [];
  let i = 0;

  function skipQuoted() {
    if (message[i + 1] === "'") { i += 2; return; }
    if (message[i + 1] === '{' || message[i + 1] === '}') {
      i++;
      while (i < message.length) {
        if (message[i] === "'") {
          if (message[i + 1] === "'") { i += 2; continue; }
          i++;
          return;
        }
        i++;
      }
      return;
    }
    i++;
  }

  function parseBody(depth) {
    while (i < message.length) {
      const ch = message[i];
      if (ch === "'") { skipQuoted(); continue; }
      if (ch === '{') { i++; parseArg(depth + 1); continue; }
      if (ch === '}') {
        if (depth === 0) { errors.push(`unmatched "}" at position ${i}`); i++; continue; }
        return;
      }
      i++;
    }
    if (depth > 0) errors.push('missing closing "}"');
  }

  function merge(name, type) {
    if (!args.has(name) || args.get(name) === 'simple') args.set(name, type);
  }

  function parseArg(depth) {
    const m = /^\s*([^\s,{}']+)\s*/.exec(message.slice(i));
    if (!m) { errors.push(`empty or invalid argument at position ${i}`); return; }
    const name = m[1];
    i += m[0].length;
    let type = 'simple';
    if (message[i] === ',') {
      i++;
      const t = /^\s*([A-Za-z]+)\s*/.exec(message.slice(i));
      if (!t) { errors.push(`argument "${name}" has no type`); return; }
      type = t[1];
      i += t[0].length;
      if (['plural', 'select', 'selectordinal'].includes(type)) {
        if (message[i] === ',') i++;
        else errors.push(`argument "${name}" is missing a "," after ${type}`);
        const keys = [];
        const off = /^\s*offset:\s*\d+/.exec(message.slice(i));
        if (off) i += off[0].length;
        for (;;) {
          const k = /^\s*(=\d+|[A-Za-z_][\w-]*)\s*/.exec(message.slice(i));
          if (!k) break;
          i += k[0].length;
          if (message[i] !== '{') { errors.push(`branch "${k[1]}" of "${name}" has no { }`); break; }
          i++;
          parseBody(depth);
          if (message[i] === '}') i++;
          keys.push(k[1]);
        }
        if (!keys.includes('other')) errors.push(`"${name}" (${type}) needs an "other" branch`);
        selectors.set(name, keys);
        while (/\s/.test(message[i] ?? '')) i++;
        if (message[i] === '}') i++;
        else errors.push(`argument "${name}" is not closed with "}"`);
        merge(name, type);
        return;
      }
      while (i < message.length && message[i] !== '}') i++;
    }
    if (message[i] === '}') i++;
    else errors.push(`argument "${name}" is not closed with "}"`);
    merge(name, type);
  }

  parseBody(0);
  return { args, selectors, errors, balanced: errors.length === 0 };
}

/** Removes ICU syntax (arguments, plural/select keywords, braces) leaving only translatable text. */
export function stripIcuSyntax(text) {
  return text
    .replace(/(=\d+|\b(?:one|other|zero|few|many|two))\s*\{/g, ' ')
    .replace(/\{\s*\w+\s*(?:,\s*\w+\s*,?)?\s*\}?/g, ' ')
    .replace(/[{}#]/g, ' ');
}

/** Sorted placeholder names, e.g. ['count', 'name']. */
export function placeholderNames(message) {
  return [...parseIcu(message).args.keys()].sort();
}

/** Display form: `{count}, {name}`. */
export function placeholderList(message) {
  return placeholderNames(message).map((n) => `{${n}}`).join(', ');
}

/** Plain-language explanation of plural/select usage. */
export function explainIcu(message) {
  const { args, selectors } = parseIcu(message);
  const notes = [];
  for (const [name, keys] of selectors) {
    const type = args.get(name);
    if (type === 'plural' || type === 'selectordinal') {
      const bits = keys.map((k) => {
        if (k === 'one') return '"one" = used when the number is 1';
        if (k === 'other') return '"other" = used for every other number';
        if (k === 'zero') return '"zero" = used for 0';
        if (k === 'few' || k === 'many' || k === 'two') return `"${k}" = special form for some numbers`;
        if (k.startsWith('=')) return `"${k}" = used when the number is exactly ${k.slice(1)}`;
        return `"${k}"`;
      });
      notes.push(`PLURAL on {${name}}: the program picks one branch by the number. ${bits.join('; ')}. "#" stands for the number itself. Keep the branch words (one, other) and the braces exactly as in English; translate only the text inside each branch.`);
    } else {
      notes.push(`SELECT on {${name}}: the program picks one branch by the value of {${name}} (${keys.map((k) => `"${k}"`).join(', ')}). Keep the branch words exactly as in English; translate only the text inside each { }. "other" is the fallback.`);
    }
  }
  return notes;
}

// ---------------------------------------------------------------- JSON catalogs

export function flatten(tree, prefix = '', out = new Map()) {
  for (const [key, value] of Object.entries(tree)) {
    const p = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') out.set(p, value);
    else flatten(value, p, out);
  }
  return out;
}

export function readJson(file) {
  const text = fs.readFileSync(file, 'utf8');
  return { text, data: JSON.parse(text) };
}

export function serializeJson(data) {
  return JSON.stringify(data, null, 2) + '\n';
}

/** Sets a dotted path in a nested object; the leaf must already exist as a string. */
export function setByPath(tree, dotted, value) {
  const parts = dotted.split('.');
  let node = tree;
  for (const part of parts.slice(0, -1)) {
    if (typeof node[part] !== 'object' || node[part] === null) throw new Error(`no such key: ${dotted}`);
    node = node[part];
  }
  const leaf = parts.at(-1);
  if (typeof node[leaf] !== 'string') throw new Error(`no such key: ${dotted}`);
  node[leaf] = value;
}

// ---------------------------------------------------------------- .properties

const hasArgs = (s) => /\{\d+/.test(s);

function unescapeProps(raw) {
  let out = '';
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c !== '\\') { out += c; continue; }
    const n = raw[++i];
    if (n === 'n') out += '\n';
    else if (n === 't') out += '\t';
    else if (n === 'r') out += '\r';
    else if (n === 'f') out += '\f';
    else if (n === 'u') { out += String.fromCharCode(parseInt(raw.slice(i + 1, i + 5), 16)); i += 4; }
    else if (n === undefined) break;
    else out += n;
  }
  return out;
}

export function escapePropsValue(text) {
  let out = '';
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '\\') out += '\\\\';
    else if (c === '\n') out += '\\n';
    else if (c === '\r') out += '\\r';
    else if (c === '\t') out += '\\t';
    else if (c === ' ' && i === 0) out += '\\ ';
    else out += c;
  }
  return out;
}

/** Message as a human sees it (MessageFormat apostrophe doubling collapsed). */
export function propsDisplay(raw) {
  const v = unescapeProps(raw);
  return hasArgs(v) ? v.replace(/''/g, "'") : v;
}

/** Inverse of propsDisplay for writing. `english` decides whether the message takes arguments. */
export function propsStore(display, english) {
  const needsDouble = hasArgs(english) || hasArgs(display);
  return escapePropsValue(needsDouble ? display.replace(/'/g, "''") : display);
}

/** Parses .properties text into entries with line ranges. */
export function parseProperties(text) {
  const lines = text.split('\n');
  const entries = new Map();
  let comment = '';
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].replace(/\r$/, '');
    if (/^\s*[#!]/.test(line)) { comment = line.replace(/^\s*[#!]\s*/, ''); continue; }
    if (line.trim() === '') { comment = ''; continue; }
    const start = i;
    let logical = line;
    while (/(^|[^\\])(\\\\)*\\$/.test(logical) && i + 1 < lines.length) {
      logical = logical.slice(0, -1) + lines[++i].replace(/^\s+/, '').replace(/\r$/, '');
    }
    const m = /^\s*((?:[^=:\s\\]|\\.)+)\s*[=:]?\s*(.*)$/s.exec(logical);
    if (!m) continue;
    entries.set(m[1], { start, end: i, raw: m[2], value: propsDisplay(m[2]), comment });
    comment = '';
  }
  return { lines, entries };
}

export function setProperty(text, key, display, english) {
  const { lines, entries } = parseProperties(text);
  const e = entries.get(key);
  if (!e) throw new Error(`no such key: ${key}`);
  lines.splice(e.start, e.end - e.start + 1, `${key}=${propsStore(display, english)}`);
  return lines.join('\n');
}

// ---------------------------------------------------------------- calendar

export function readCalendar(root) {
  const text = fs.readFileSync(path.join(root, PATHS.calendar), 'utf8');
  const arr = (name, lang) => {
    const block = new RegExp(`export const ${name} = \\{([\\s\\S]*?)\\} as const;`).exec(text)?.[1] ?? '';
    const m = new RegExp(`\\b${lang}:\\s*\\[([^\\]]*)\\]`).exec(block);
    return m ? [...m[1].matchAll(/'([^']*)'/g)].map((x) => x[1]) : [];
  };
  const era = /const ERA = \{ am: '([^']*)', en: '([^']*)' \}/.exec(text);
  const dayWord = /\$\{month\} \$\{date\.day\} ([^\s$]+) \$\{date\.year\}/.exec(text);
  return {
    months: { am: arr('ETHIOPIAN_MONTHS', 'am'), en: arr('ETHIOPIAN_MONTHS', 'en') },
    weekdays: { am: arr('ETHIOPIAN_WEEKDAYS', 'am'), en: arr('ETHIOPIAN_WEEKDAYS', 'en') },
    era: era ? { am: era[1], en: era[2] } : null,
    dayWord: dayWord ? dayWord[1] : null,
  };
}

// ---------------------------------------------------------------- seeds

/** Finds {"en": "...", "am": "..."} pairs in R__*.sql. Returns [{ id, area, english, amharic, file, line }]. */
export function readSeeds(root) {
  const dir = path.join(root, PATHS.migrations);
  const rows = [];
  for (const file of fs.readdirSync(dir).filter((f) => /^R__.*\.sql$/.test(f)).sort()) {
    const lines = fs.readFileSync(path.join(dir, file), 'utf8').split('\n');
    let table = null;
    let country = null;
    lines.forEach((line, idx) => {
      const ins = /^\s*insert into (\w+)/i.exec(line);
      if (ins) table = ins[1];
      const code = /^\s*\(\s*'([A-Z_]+)'/.exec(line)?.[1];
      if (table === 'countries' && code) country = code;
      const pairRe = /"en":\s*"((?:[^"\\]|\\.)*)"\s*,\s*"am":\s*"((?:[^"\\]|\\.)*)"/g;
      for (const m of line.matchAll(pairRe)) {
        const unq = (s) => s.replace(/''/g, "'");
        const before = line.slice(0, m.index);
        const key = /"(\w+)":\s*\{\s*$/.exec(before)?.[1];
        const level = /"level":\s*(\d+)/.exec(before)?.[1];
        let area;
        let id;
        if (table === 'countries') {
          area = 'countries';
          if (level) id = `${country}.address_level.${level}`;
          else if (key) id = `${country}.name_format.${key}`;
          else id = `${country}.name`;
        } else if (table === 'administrative_areas') {
          const p = /\(\s*'(\w+)',\s*(\d+),\s*'(\w+)'/.exec(line);
          area = 'regions';
          id = p ? `${p[1]}.${p[2]}.${p[3]}` : `line${idx + 1}`;
        } else if (table === 'roles') { area = 'roles'; id = code; }
        else if (table === 'subscription_plans') { area = 'plans'; id = code; }
        else { area = table ?? 'other'; id = code ?? `line${idx + 1}`; }
        rows.push({ id: `${area}.${id}`, area: `seed-${area}`, english: unq(m[1]), amharic: unq(m[2]), file, line: idx + 1 });
      }
    });
  }
  return rows;
}

// ---------------------------------------------------------------- CSV

export function toCsv(rows, columns = COLUMNS) {
  const esc = (v) => {
    const s = String(v ?? '');
    return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  return '﻿' + [columns.join(','), ...rows.map((r) => columns.map((c) => esc(r[c])).join(','))].join('\r\n') + '\r\n';
}

/** RFC 4180 parser; tolerates BOM, CRLF/LF and ";"/tab delimiters chosen by the header row. */
export function parseCsv(text) {
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  const firstLine = text.split(/\r?\n/, 1)[0];
  const delim = firstLine.includes(',') ? ',' : firstLine.includes('\t') ? '\t' : firstLine.includes(';') ? ';' : ',';
  const records = [];
  let row = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false;
      } else field += c;
    } else if (c === '"') quoted = true;
    else if (c === delim) { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field); field = '';
      records.push(row); row = [];
    } else field += c;
  }
  if (field !== '' || row.length) { row.push(field); records.push(row); }
  const [header, ...body] = records.filter((r) => r.some((f) => f !== ''));
  const names = header.map((h) => h.trim().toLowerCase());
  return body.map((r) => Object.fromEntries(names.map((n, i) => [n, r[i] ?? ''])));
}

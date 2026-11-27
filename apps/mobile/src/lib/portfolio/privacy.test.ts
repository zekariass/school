import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

const SRC = join(__dirname, '..', '..');
const ROOTS = [join(SRC, 'lib', 'portfolio'), join(SRC, 'components', 'portfolio'), join(SRC, 'app', 'portfolio')];

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.(ts|tsx)$/.test(name) && !/\.test\.ts$/.test(name) ? [path] : [];
  });
}

const files = ROOTS.flatMap(sources);

/**
 * The portfolio holds children's own work, notes and comments. The rules (CLAUDE.md, "Portfolio privacy") are about what the
 * code must never do with them; these checks read the source and fail when somebody adds a way around them.
 */
describe('portfolio privacy', () => {
  it('finds the portfolio code', () => {
    expect(files.length).toBeGreaterThan(20);
  });

  it('never writes anything to the phone: no key-value store, SQLite, SecureStore, file or cache write', () => {
    const forbidden = [
      /@\/lib\/storage/,
      /async-storage/i,
      /expo-sqlite/,
      /expo-secure-store/,
      /@\/lib\/offline/,
      /@\/lib\/db/,
      /writeAsStringAsync|\.write\(|\.create\(|\.copy\(|\.move\(/,
    ];
    const offenders = files.filter((file) => forbidden.some((pattern) => pattern.test(readFileSync(file, 'utf8'))));
    expect(offenders.map((file) => file.slice(SRC.length))).toEqual([]);
  });

  it('never logs: no console call, no analytics', () => {
    const offenders = files.filter((file) => /\bconsole\.|analytics|Sentry|crashlytics/i.test(readFileSync(file, 'utf8')));
    expect(offenders.map((file) => file.slice(SRC.length))).toEqual([]);
  });

  it('never puts text in a route: only ids go into params', () => {
    // Every `params:` object of the portfolio code names ids (and nothing else).
    const allowed = new Set(['schoolId', 'itemId', 'classId', 'studentId']);
    const offenders: string[] = [];
    for (const file of files.filter((candidate) => candidate.endsWith('.tsx'))) {
      for (const match of readFileSync(file, 'utf8').matchAll(/params:\s*\{([^}]*)\}/g)) {
        for (const entry of match[1]!.split(',')) {
          const name = entry.split(':')[0]!.trim();
          if (name && !allowed.has(name)) offenders.push(`${file.slice(SRC.length)}: ${name}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('keeps every query under the private root', () => {
    const offenders: string[] = [];
    for (const file of files.filter((candidate) => candidate.endsWith('queries.ts'))) {
      const text = readFileSync(file, 'utf8');
      for (const match of text.matchAll(/queryKey:\s*(\[[^\]]*\]|[A-Za-z]+Key\()/g)) {
        const key = match[1]!;
        if (!/PORTFOLIO_ROOT|Key\(/.test(key)) offenders.push(key);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('keeps every portfolio query in memory for minutes, not for a day', () => {
    const queries = readFileSync(join(SRC, 'lib', 'portfolio', 'queries.ts'), 'utf8');
    expect(queries).toMatch(/gcTime: PORTFOLIO_GC_MS/);
    expect(queries).toMatch(/networkMode: 'online'/);
  });
});

/// <reference types="node" />
/**
 * End to end: the real outbox engine (SQLite storage, restart, backoff) and the real wire format against a running
 * API. It is skipped unless SYNC_E2E_FIXTURE points to a JSON file that names the API, a school with a class, six
 * students, a point category and two teachers' tokens (see the M7b notes in docs/Implementation_Plan.md):
 *
 *   SYNC_E2E_FIXTURE=/path/fixture.json npx vitest run src/lib/sync/e2e.test.ts
 *
 * "Airplane mode" is a switch in the transport (the device answers `offline` without sending, as the app does).
 */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

import { describe, expect, it } from 'vitest';

import { resolveConflict } from '@/lib/attendance/conflicts';
import { addDays, localDateIn } from '@/lib/attendance/dates';
import { editOperation, effectiveStudents, markAllPresentMarks, markFor, rollFromApi } from '@/lib/attendance/roll-model';
import { runMigrations, type MigrationDatabase } from '@/lib/db/migrations';

import { SyncEngine } from './engine';
import { sqlOutboxStorage, type OutboxDatabase } from './outbox-sql';
import { classifyRequestFailure, readBatchResponse, toRequestOperation } from './protocol';
import type { BatchOutcome, NewOperation, QueuedOperation, SyncLimits } from './types';
import { DEFAULT_LIMITS } from './types';

interface Fixture {
  api: string;
  schoolId: string;
  classId: string;
  categoryId: string;
  students: string[];
  teacher1: { userId: string; token: string };
  teacher2: { userId: string; token: string };
}

const fixturePath = process.env.SYNC_E2E_FIXTURE;
const fx: Fixture = fixturePath ? JSON.parse(readFileSync(fixturePath, 'utf8')) : (undefined as never);

async function http(token: string, method: string, path: string, body?: unknown): Promise<{ status: number; json: any }> {
  const res = await fetch(fx.api + path, {
    method,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${token}`, 'accept-language': 'en' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, json: text ? JSON.parse(text) : null };
}

function memoryDatabase() {
  const raw = new DatabaseSync(':memory:');
  const db: OutboxDatabase & MigrationDatabase = {
    async runAsync(sql, params) {
      raw.prepare(sql).run(...params);
    },
    async getAllAsync<T>(sql: string, params: (string | number | null)[]) {
      return raw.prepare(sql).all(...params) as T[];
    },
    async execAsync(sql) {
      raw.exec(sql);
    },
    async getFirstAsync<T>(sql: string) {
      return (raw.prepare(sql).get() as T | undefined) ?? null;
    },
    async withTransactionAsync(task) {
      raw.exec('BEGIN');
      await task();
      raw.exec('COMMIT');
    },
  };
  return db;
}

/** One phone: its own device id, SQLite database and airplane-mode switch. Restarting keeps the database. */
function phone(teacher: Fixture['teacher1'], limits: SyncLimits = DEFAULT_LIMITS) {
  const device = randomUUID();
  const db = memoryDatabase();
  const net = { online: true, sent: [] as string[][], requests: 0 };
  async function start() {
    await runMigrations(db);
    const engine = new SyncEngine({
      storage: sqlOutboxStorage(async () => db),
      now: () => Date.now(),
      getOwnerId: () => teacher.userId,
      limits,
      transport: async (batch: readonly QueuedOperation[]): Promise<BatchOutcome> => {
        if (!net.online) return { kind: 'retry', reason: 'offline' };
        net.requests++;
        net.sent.push(batch.map((op) => op.opId));
        const res = await http(teacher.token, 'POST', '/api/v1/sync', { operations: batch.map((op) => toRequestOperation(op, device)) });
        if (res.status === 200) return readBatchResponse(res.json);
        return classifyRequestFailure(res.status, res.json ?? undefined);
      },
    });
    await engine.init();
    return engine;
  }
  return { net, start, device };
}

async function roll(token: string, date?: string) {
  const res = await http(token, 'GET', `/api/v1/schools/${fx.schoolId}/classes/${fx.classId}/attendance${date ? `?date=${date}` : ''}`);
  expect(res.status).toBe(200);
  return { api: res.json, snapshot: rollFromApi(res.json, fx.schoolId) };
}

async function totals(token: string): Promise<Record<string, number>> {
  const res = await http(token, 'GET', `/api/v1/schools/${fx.schoolId}/classes/${fx.classId}/points`);
  expect(res.status).toBe(200);
  return Object.fromEntries(res.json.students.map((s: { studentId: string; total?: number }) => [s.studentId, s.total ?? 0]));
}

const award = (owner: string, opId: string, studentIds: string[], wholeClass = false): NewOperation => ({
  type: 'POINT_AWARD',
  opId,
  ownerId: owner,
  schoolId: fx.schoolId,
  classId: fx.classId,
  baseVersion: null,
  payload: { categoryId: fx.categoryId, studentIds: wholeClass ? [] : studentIds, wholeClass, points: null },
});

describe.skipIf(!fixturePath)('sync engine against a running API', () => {
  const [abel, bruk, chaltu, dagim, eden, fikir] = fx?.students ?? [];

  it('airplane mode: marks attendance and awards points, restarts offline, reconnects and everything lands once', async () => {
    const t1 = phone(fx.teacher1);
    let engine = await t1.start();
    const before = await roll(fx.teacher1.token);
    const pointsBefore = await totals(fx.teacher1.token);
    t1.net.online = false;

    // The teacher marks (several taps become one operation) and awards points, all offline.
    const owner = fx.teacher1.userId;
    const mark = (id: string, status: 'ABSENT' | 'LATE', minutes?: number) => {
      const student = before.snapshot.students.find((s) => s.studentId === id)!;
      return editOperation({ opId: randomUUID(), ownerId: owner, roll: before.snapshot, marks: [markFor(student, status, minutes ?? null)] });
    };
    expect(await engine.enqueue(mark(abel!, 'ABSENT'))).toBe('queued');
    expect(await engine.enqueue(mark(bruk!, 'LATE', 10), { volatile: { notes: { [bruk!]: 'bus was late' } } })).toBe('merged');
    const effective = effectiveStudents(before.snapshot, engine.pending());
    // Everybody else present, marked at once: a second tap on Abel does not duplicate anything.
    const rest = markAllPresentMarks(effective).filter((m) => [chaltu, dagim].includes(m.studentId));
    expect(await engine.enqueue(editOperation({ opId: randomUUID(), ownerId: owner, roll: before.snapshot, marks: rest }))).toBe('merged');
    const awardOne = randomUUID();
    const awardAll = randomUUID();
    await engine.enqueue(award(owner, awardOne, [abel!]));
    await engine.enqueue(award(owner, awardAll, [], true));
    await engine.flush(true);
    expect(engine.pending()).toHaveLength(3);
    expect(t1.net.sent).toEqual([]);
    const optimistic = effectiveStudents(before.snapshot, engine.pending());
    expect(optimistic.find((s) => s.studentId === abel)).toMatchObject({ status: 'ABSENT', queued: true });
    expect(optimistic.find((s) => s.studentId === bruk)).toMatchObject({ status: 'LATE', minutesLate: 10 });

    // The app is killed and opened again, still offline: the same operations are still waiting.
    engine = await t1.start();
    expect(engine.pending().map((op) => op.type)).toEqual(['ATTENDANCE_MARK', 'POINT_AWARD', 'POINT_AWARD']);
    await engine.flush(true);
    expect(t1.net.sent).toEqual([]);

    // The connection returns.
    t1.net.online = true;
    const summary = await engine.flush(true);
    expect(summary).toMatchObject({ applied: 3, conflicts: 0, rejected: 0, remaining: 0 });
    expect(t1.net.sent).toHaveLength(1);

    const after = await roll(fx.teacher1.token);
    const status = (id: string) => after.api.students.find((s: { studentId: string }) => s.studentId === id).record;
    expect(status(abel!)).toMatchObject({ status: 'ABSENT', version: 0 });
    expect(status(bruk!)).toMatchObject({ status: 'LATE', minutesLate: 10 });
    expect(status(chaltu!).status).toBe('PRESENT');
    expect(status(dagim!).status).toBe('PRESENT');
    expect(status(eden!)).toBeUndefined();
    const pointsAfter = await totals(fx.teacher1.token);
    expect(pointsAfter[abel!]! - (pointsBefore[abel!] ?? 0)).toBe(2 * (pointsAfter[bruk!]! - (pointsBefore[bruk!] ?? 0)));

    // Replaying the whole batch (the answer was lost) changes nothing: DUPLICATE everywhere.
    const replay = t1.net.sent[0]!;
    expect(replay).toHaveLength(3);
    const again = await http(fx.teacher1.token, 'POST', '/api/v1/sync', {
      operations: replay.map((id, index) => ({
        deviceId: t1.device,
        clientOperationId: id,
        operationType: index === 0 ? 'ATTENDANCE_MARK' : 'POINT_AWARD',
        entityType: index === 0 ? 'ATTENDANCE_SESSION' : 'POINT_TRANSACTION',
        payload: index === 0 ? { schoolId: fx.schoolId, classId: fx.classId, date: before.snapshot.date, records: [{ studentId: abel, status: 'PRESENT' }] } : {},
        clientCreatedAt: new Date().toISOString(),
      })),
    });
    expect(again.status).toBe(200);
    expect(again.json.results.map((r: { status: string }) => r.status)).toEqual(['DUPLICATE', 'DUPLICATE', 'DUPLICATE']);
    expect(await totals(fx.teacher1.token)).toEqual(pointsAfter);
  }, 60_000);

  it('a colleague marked the same students meanwhile: per-student conflicts, keep theirs / use mine', async () => {
    const t1 = phone(fx.teacher1);
    const engine = await t1.start();
    const base = await roll(fx.teacher1.token);
    t1.net.online = false;
    const owner = fx.teacher1.userId;
    // Eden and Fikir are unmarked; teacher 1 marks both absent offline.
    const edits = [eden!, fikir!].map((id) => base.snapshot.students.find((s) => s.studentId === id)!);
    await engine.enqueue(editOperation({ opId: randomUUID(), ownerId: owner, roll: base.snapshot, marks: edits.map((s) => markFor(s, 'ABSENT')) }));
    await engine.flush(true);

    // Teacher 2 marks the same two present, online, from the same roll.
    const theirs = await http(fx.teacher2.token, 'PUT', `/api/v1/schools/${fx.schoolId}/classes/${fx.classId}/attendance`, {
      date: base.snapshot.date,
      baseVersion: base.snapshot.sessionVersion,
      records: [eden, fikir].map((studentId) => ({ studentId, status: 'PRESENT' })),
    });
    expect(theirs.status).toBe(200);

    t1.net.online = true;
    const summary = await engine.flush(true);
    expect(summary).toMatchObject({ applied: 0, conflicts: 1, remaining: 0 });
    const conflicted = engine.attention()[0]!;
    expect(conflicted.status).toBe('CONFLICT');
    if (conflicted.type !== 'ATTENDANCE_MARK') throw new Error('expected an attendance operation');
    expect(conflicted.conflict!.entries.map((e) => e.studentId).sort()).toEqual([eden, fikir].sort());
    expect(conflicted.conflict!.entries[0]).toMatchObject({ stored: { status: 'PRESENT', recordedBy: fx.teacher2.userId }, submitted: { status: 'ABSENT' } });
    // Nothing was overwritten: the colleague's marks are still what the roll shows.
    const clashed = await roll(fx.teacher1.token);
    expect(clashed.snapshot.students.find((s) => s.studentId === eden)!.record!.status).toBe('PRESENT');

    // Eden: use mine (a NEW operation naming the record just seen). Fikir: keep theirs.
    const useMine = resolveConflict(conflicted, [eden!], 'use-mine', randomUUID());
    expect(await engine.enqueue(useMine.resend!)).toBe('queued');
    await engine.updateConflict(conflicted.opId, useMine.remaining);
    const keep = resolveConflict({ ...conflicted, conflict: useMine.remaining! }, [fikir!], 'keep-theirs', randomUUID());
    await engine.updateConflict(conflicted.opId, keep.remaining);
    expect(engine.attention()).toHaveLength(0);
    await engine.flush(true);

    const final = await roll(fx.teacher1.token);
    expect(final.snapshot.students.find((s) => s.studentId === eden)!.record!.status).toBe('ABSENT');
    expect(final.snapshot.students.find((s) => s.studentId === fikir)!.record!.status).toBe('PRESENT');
  }, 60_000);

  it('refusals are kept for the teacher with the API\'s own words and do not block the rest', async () => {
    const t1 = phone(fx.teacher1);
    const engine = await t1.start();
    const base = await roll(fx.teacher1.token);
    const owner = fx.teacher1.userId;
    const tomorrow = addDays(localDateIn(base.snapshot.timeZone, Date.now()), 3);
    t1.net.online = false;
    await engine.enqueue(editOperation({ opId: randomUUID(), ownerId: owner, roll: { ...base.snapshot, date: tomorrow }, marks: [markFor(base.snapshot.students[0]!, 'ABSENT')] }));
    await engine.enqueue(award(owner, randomUUID(), [randomUUID()]));
    await engine.enqueue(award(owner, randomUUID(), [chaltu!]));
    t1.net.online = true;
    const summary = await engine.flush(true);
    expect(summary).toMatchObject({ applied: 1, rejected: 2, remaining: 0 });
    const codes = engine.attention().map((op) => op.problem?.code).sort();
    expect(codes).toEqual(['attendance.date_in_future', 'points.student_not_in_class'].sort());
    for (const op of engine.attention()) expect(op.problem!.detail.length).toBeGreaterThan(5);
  }, 60_000);

  it('an award that already went through the direct endpoint is a DUPLICATE through sync: never twice', async () => {
    const t1 = phone(fx.teacher1);
    const engine = await t1.start();
    const opId = randomUUID();
    const direct = await http(fx.teacher1.token, 'POST', `/api/v1/schools/${fx.schoolId}/classes/${fx.classId}/points/awards`, { categoryId: fx.categoryId, clientOpId: opId, studentIds: [dagim] });
    expect(direct.status).toBe(201);
    const before = await totals(fx.teacher1.token);
    await engine.enqueue(award(fx.teacher1.userId, opId, [dagim!]));
    const summary = await engine.flush(true);
    expect(summary).toMatchObject({ applied: 1, remaining: 0 });
    expect(await totals(fx.teacher1.token)).toEqual(before);
  }, 60_000);

  it('a batch over the server\'s limit is refused, then sent one by one', async () => {
    const t1 = phone(fx.teacher1, { ...DEFAULT_LIMITS, maxBatch: 150, maxItems: 300 });
    const engine = await t1.start();
    const before = await totals(fx.teacher1.token);
    for (let i = 0; i < 101; i++) await engine.enqueue(award(fx.teacher1.userId, randomUUID(), [bruk!]));
    const summary = await engine.flush(true);
    expect(summary).toMatchObject({ applied: 101, remaining: 0 });
    // The first request (101 operations) was refused with 413; the rest went one by one.
    expect(t1.net.sent[0]).toHaveLength(101);
    expect(t1.net.sent.slice(1).every((batch) => batch.length === 1)).toBe(true);
    const after = await totals(fx.teacher1.token);
    expect(after[bruk!]! - (before[bruk!] ?? 0)).toBeGreaterThanOrEqual(101);
  }, 120_000);
});

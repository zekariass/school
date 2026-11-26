import { describe, expect, it } from 'vitest';

import {
  MessageOutbox,
  classifyFailure,
  retryDelayMs,
  type OutboxEntry,
  type OutboxScheduler,
  type OutboxTransport,
  type SendOutcome,
} from './outbox';
import type { MessageItem, SendResult } from './types';

/** A manual clock and timers: time only moves when the test says so. */
class Harness {
  time = 1_000_000;
  private timers: { at: number; fn: () => void; id: number; live: boolean }[] = [];
  private nextTimer = 1;
  readonly scheduler: OutboxScheduler = {
    set: (fn, ms) => {
      const timer = { at: this.time + ms, fn, id: this.nextTimer++, live: true };
      this.timers.push(timer);
      return timer.id;
    },
    clear: (handle) => {
      for (const timer of this.timers) if (timer.id === handle) timer.live = false;
    },
  };
  advance(ms: number) {
    const until = this.time + ms;
    for (;;) {
      const due = this.timers.filter((timer) => timer.live && timer.at <= until).sort((a, b) => a.at - b.at)[0];
      if (!due) break;
      due.live = false;
      this.time = due.at;
      due.fn();
    }
    this.time = until;
  }
  get pendingTimers() {
    return this.timers.filter((timer) => timer.live).length;
  }
}

function result(id: string, created = true): SendResult {
  return { created, afterHours: false, message: { id, conversationId: 'c1' } as MessageItem };
}

/** A transport whose answers the test scripts; every call is recorded (with the clientMessageId it carried). */
function scripted(answers: (SendOutcome | Error)[] | ((entry: OutboxEntry, call: number) => SendOutcome | Error)) {
  const calls: { id: string; body: string; conversationId: string; mediaIds: readonly string[] }[] = [];
  const gates: ((value: void) => void)[] = [];
  const transport: OutboxTransport & { calls: typeof calls; hold: boolean; release: () => void } = {
    calls,
    hold: false,
    release: () => gates.shift()?.(),
    async send(entry) {
      calls.push({ id: entry.clientMessageId, body: entry.body, conversationId: entry.conversationId, mediaIds: entry.mediaIds });
      if (transport.hold) await new Promise<void>((resolve) => gates.push(resolve));
      const call = calls.length - 1;
      const answer = typeof answers === 'function' ? answers(entry, call) : (answers[call] ?? { kind: 'sent', result: result(`m${call}`) });
      if (answer instanceof Error) throw answer;
      return answer;
    },
  };
  return transport;
}

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));
const noJitter = () => 1;

function setup(answers: Parameters<typeof scripted>[0], extra: { maxAutoRetryMs?: number } = {}) {
  const harness = new Harness();
  const transport = scripted(answers);
  const sent: { id: string; messageId: string }[] = [];
  const outbox = new MessageOutbox({
    transport,
    now: () => harness.time,
    scheduler: harness.scheduler,
    random: noJitter,
    onSent: (entry, outcome) => sent.push({ id: entry.clientMessageId, messageId: outcome.message.id }),
    ...extra,
  });
  return { harness, transport, outbox, sent };
}

const input = (id: string, body = 'hello', conversationId = 'c1') => ({ clientMessageId: id, conversationId, body });

describe('sending', () => {
  it('sends at once and removes the entry when the API accepts it', async () => {
    const { outbox, transport, sent } = setup([]);
    outbox.enqueue(input('a'));
    expect(outbox.entriesOf('c1')[0]?.status).toBe('sending');
    await flush();
    expect(transport.calls.map((call) => call.id)).toEqual(['a']);
    expect(outbox.hasUnsent()).toBe(false);
    expect(sent).toEqual([{ id: 'a', messageId: 'm0' }]);
  });

  it('keeps the accepted entry for one tick (status sent) so the stored message can appear first, then drops it', async () => {
    const { outbox, harness, sent } = setup([]);
    const order: string[] = [];
    outbox.subscribe(() => order.push(outbox.getSnapshot().map((entry) => entry.status).join()));
    outbox.enqueue(input('a'));
    await flush();
    // the cache callback ran while the entry was still there
    expect(sent).toHaveLength(1);
    expect(outbox.getSnapshot().map((entry) => entry.status)).toEqual(['sent']);
    expect(outbox.hasUnsent()).toBe(false); // a sent entry is not "unsent": no warning when leaving
    harness.advance(0);
    expect(outbox.getSnapshot()).toEqual([]);
    expect(order).toContain('sent');
  });

  it('does not add the same clientMessageId twice', async () => {
    const { outbox, transport } = setup([]);
    outbox.enqueue(input('a'));
    outbox.enqueue(input('a'));
    await flush();
    expect(transport.calls).toHaveLength(1);
  });
});

describe('retry with the same clientMessageId', () => {
  it('keeps a message that could not be sent and retries it with backoff, same id', async () => {
    const { outbox, transport, harness, sent } = setup([{ kind: 'retry' }, { kind: 'retry' }, { kind: 'sent', result: result('server-1') }]);
    outbox.enqueue(input('a', 'secret text'));
    await flush();
    let entry = outbox.entriesOf('c1')[0]!;
    expect(entry.status).toBe('waiting');
    expect(entry.attempts).toBe(1);
    expect(entry.nextAttemptAt).toBe(harness.time + 2_000);
    expect(outbox.hasUnsent('c1')).toBe(true);

    harness.advance(1_999);
    await flush();
    expect(transport.calls).toHaveLength(1);
    harness.advance(1);
    await flush();
    expect(transport.calls).toHaveLength(2);
    entry = outbox.entriesOf('c1')[0]!;
    expect(entry.status).toBe('waiting');
    expect(entry.nextAttemptAt).toBe(harness.time + 4_000); // doubled

    harness.advance(4_000);
    await flush();
    expect(transport.calls.map((call) => call.id)).toEqual(['a', 'a', 'a']);
    expect(transport.calls.every((call) => call.body === 'secret text')).toBe(true);
    expect(outbox.hasUnsent()).toBe(false);
    expect(sent).toEqual([{ id: 'a', messageId: 'server-1' }]);
  });

  it('a transport that throws counts as a retryable failure', async () => {
    const { outbox } = setup([new Error('boom')]);
    outbox.enqueue(input('a'));
    await flush();
    expect(outbox.entriesOf('c1')[0]?.status).toBe('waiting');
  });

  it('tapping "retry" sends now with the same id and cancels the timer', async () => {
    const { outbox, transport, harness } = setup([{ kind: 'retry' }]);
    outbox.enqueue(input('a'));
    await flush();
    expect(harness.pendingTimers).toBe(1);
    outbox.retryNow('a');
    await flush();
    harness.advance(0);
    expect(transport.calls.map((call) => call.id)).toEqual(['a', 'a']);
    expect(outbox.hasUnsent()).toBe(false);
    expect(harness.pendingTimers).toBe(0);
  });

  it('kick (network back, app in the foreground) retries what waits', async () => {
    const { outbox, transport } = setup([{ kind: 'retry' }]);
    outbox.enqueue(input('a'));
    await flush();
    outbox.kick();
    await flush();
    expect(transport.calls).toHaveLength(2);
    expect(outbox.hasUnsent()).toBe(false);
  });

  it('a duplicate (200 for a message that had arrived) completes the entry once', async () => {
    // The first answer was lost on the way, the retry finds the message already stored: created=false.
    const { outbox, sent } = setup([{ kind: 'retry' }, { kind: 'sent', result: result('stored', false) }]);
    outbox.enqueue(input('a'));
    await flush();
    outbox.retryNow('a');
    await flush();
    expect(sent).toEqual([{ id: 'a', messageId: 'stored' }]);
    expect(outbox.hasUnsent()).toBe(false);
  });
});

describe('429', () => {
  it('waits at least the Retry-After the API sent', async () => {
    const { outbox, harness } = setup([classifyFailure({ status: 429, retryAfterSeconds: 30, problem: { code: 'messaging.rate_limited' } })]);
    outbox.enqueue(input('a'));
    await flush();
    const entry = outbox.entriesOf('c1')[0]!;
    expect(entry.status).toBe('waiting');
    expect(entry.nextAttemptAt).toBe(harness.time + 30_000);
    expect(entry.problem?.code).toBe('messaging.rate_limited');
  });

  it('caps an absurd Retry-After at five minutes', () => {
    expect(retryDelayMs(1, 3_600_000, noJitter)).toBe(300_000);
  });
});

describe('final 4xx', () => {
  it('stops after a refusal, keeps the API problem and does not retry by itself', async () => {
    const problem = { code: 'messaging.not_allowed', detail: 'You cannot do this here.' };
    const { outbox, transport, harness } = setup([classifyFailure({ status: 403, problem })]);
    outbox.enqueue(input('a'));
    await flush();
    const entry = outbox.entriesOf('c1')[0]!;
    expect(entry.status).toBe('failed');
    expect(entry.gaveUp).toBe(false);
    expect(entry.problem).toMatchObject({ status: 403, code: 'messaging.not_allowed', detail: 'You cannot do this here.' });
    harness.advance(10 * 60_000);
    await flush();
    expect(transport.calls).toHaveLength(1);
    expect(harness.pendingTimers).toBe(0);
  });

  it('a failed message does not hold back the ones written after it', async () => {
    const { outbox, transport, sent, harness } = setup((entry) =>
      entry.clientMessageId === 'a' ? classifyFailure({ status: 400, problem: { code: 'messaging.message_empty' } }) : { kind: 'sent', result: result(`m-${entry.clientMessageId}`) },
    );
    outbox.enqueue(input('a'));
    outbox.enqueue(input('b'));
    await flush();
    await flush();
    harness.advance(0);
    expect(transport.calls.map((call) => call.id)).toEqual(['a', 'b']);
    expect(sent).toEqual([{ id: 'b', messageId: 'm-b' }]);
    expect(outbox.entriesOf('c1').map((entry) => [entry.clientMessageId, entry.status])).toEqual([['a', 'failed']]);
  });

  it('after the person taps retry on a failed message it goes again with the same id', async () => {
    const { outbox, transport } = setup([classifyFailure({ status: 403 }), { kind: 'sent', result: result('ok') }]);
    outbox.enqueue(input('a'));
    await flush();
    outbox.retryNow('a');
    await flush();
    expect(transport.calls.map((call) => call.id)).toEqual(['a', 'a']);
    expect(outbox.hasUnsent()).toBe(false);
  });

  it('classifies the statuses', () => {
    expect(classifyFailure({}).kind).toBe('retry');
    for (const status of [401, 408, 425, 429, 500, 502, 503]) expect(classifyFailure({ status }).kind).toBe('retry');
    for (const status of [400, 403, 404, 409, 422]) expect(classifyFailure({ status }).kind).toBe('final');
  });
});

describe('order', () => {
  it('sends the messages of a conversation one after the other, in the order written', async () => {
    const { outbox, transport } = setup([]);
    transport.hold = true;
    outbox.enqueue(input('a', 'one'));
    outbox.enqueue(input('b', 'two'));
    outbox.enqueue(input('c', 'three'));
    await flush();
    expect(transport.calls.map((call) => call.id)).toEqual(['a']); // b and c wait for a
    expect(outbox.entriesOf('c1').map((entry) => entry.status)).toEqual(['sending', 'queued', 'queued']);
    transport.release();
    await flush();
    expect(transport.calls.map((call) => call.id)).toEqual(['a', 'b']);
    transport.release();
    await flush();
    transport.release();
    await flush();
    expect(transport.calls.map((call) => call.id)).toEqual(['a', 'b', 'c']);
    expect(outbox.hasUnsent()).toBe(false);
  });

  it('while the first waits for a retry, later messages of the same conversation wait behind it', async () => {
    const { outbox, transport, harness } = setup([{ kind: 'retry' }]);
    outbox.enqueue(input('a'));
    outbox.enqueue(input('b'));
    await flush();
    expect(transport.calls.map((call) => call.id)).toEqual(['a']);
    harness.advance(2_000);
    await flush();
    await flush();
    expect(transport.calls.map((call) => call.id)).toEqual(['a', 'a', 'b']);
  });

  it('another conversation is not held up', async () => {
    const { outbox, transport } = setup((entry) => (entry.conversationId === 'c1' ? { kind: 'retry' } : { kind: 'sent', result: result('x') }));
    outbox.enqueue(input('a', 'one', 'c1'));
    outbox.enqueue(input('b', 'two', 'c2'));
    await flush();
    expect(transport.calls.map((call) => call.id).sort()).toEqual(['a', 'b']);
    expect(outbox.hasUnsent('c1')).toBe(true);
    expect(outbox.hasUnsent('c2')).toBe(false);
  });

  it('keeps the writing order in the snapshot', async () => {
    const { outbox } = setup([{ kind: 'retry' }]);
    outbox.enqueue(input('a'));
    outbox.enqueue(input('b'));
    await flush();
    expect(outbox.getSnapshot().map((entry) => entry.clientMessageId)).toEqual(['a', 'b']);
    expect(outbox.getSnapshot().map((entry) => entry.seq)).toEqual([1, 2]);
  });
});

describe('giving up', () => {
  it('stops the automatic retries after the maximum age and asks the person to tap', async () => {
    const { outbox, transport, harness } = setup(() => ({ kind: 'retry' }), { maxAutoRetryMs: 60_000 });
    outbox.enqueue(input('a'));
    await flush();
    for (let i = 0; i < 20 && outbox.entriesOf('c1')[0]?.status !== 'failed'; i++) {
      harness.advance(30_000);
      await flush();
    }
    const entry = outbox.entriesOf('c1')[0]!;
    expect(entry.status).toBe('failed');
    expect(entry.gaveUp).toBe(true);
    const calls = transport.calls.length;
    harness.advance(10 * 60_000);
    await flush();
    expect(transport.calls).toHaveLength(calls);
  });
});

describe('sign-out and discarding', () => {
  it('clear() drops every unsent message and cancels the timers', async () => {
    const { outbox, transport, harness } = setup(() => ({ kind: 'retry' }));
    outbox.enqueue(input('a', 'private words'));
    outbox.enqueue(input('b', 'more private words', 'c2'));
    await flush();
    expect(outbox.hasUnsent()).toBe(true);
    outbox.clear();
    expect(outbox.hasUnsent()).toBe(false);
    expect(outbox.getSnapshot()).toEqual([]);
    expect(harness.pendingTimers).toBe(0);
    const calls = transport.calls.length;
    harness.advance(60 * 60_000);
    await flush();
    expect(transport.calls).toHaveLength(calls);
  });

  it('an answer that arrives after clear() is ignored (no callback, no entry)', async () => {
    const { outbox, transport, sent } = setup([]);
    transport.hold = true;
    outbox.enqueue(input('a'));
    await flush();
    outbox.clear();
    transport.release();
    await flush();
    expect(sent).toEqual([]);
    expect(outbox.hasUnsent()).toBe(false);
  });

  it('a new user after sign-out starts with an empty outbox', async () => {
    const { outbox } = setup(() => ({ kind: 'retry' }));
    outbox.enqueue(input('a'));
    await flush();
    outbox.clear();
    outbox.enqueue(input('b'));
    await flush();
    expect(outbox.getSnapshot().map((entry) => entry.clientMessageId)).toEqual(['b']);
    expect(outbox.getSnapshot()[0]?.seq).toBe(1);
  });

  it('discard removes one message and lets the next of the conversation go', async () => {
    const { outbox, transport, harness } = setup([{ kind: 'retry' }]);
    outbox.enqueue(input('a'));
    outbox.enqueue(input('b'));
    await flush();
    outbox.discard('a');
    await flush();
    harness.advance(0);
    expect(transport.calls.map((call) => call.id)).toEqual(['a', 'b']);
    expect(harness.pendingTimers).toBe(0);
    expect(outbox.hasUnsent()).toBe(false);
  });

  it('notifies subscribers on every change and hands out a stable snapshot in between', async () => {
    const { outbox } = setup([{ kind: 'retry' }]);
    let notified = 0;
    const off = outbox.subscribe(() => (notified += 1));
    outbox.enqueue(input('a'));
    await flush();
    const first = outbox.getSnapshot();
    expect(outbox.getSnapshot()).toBe(first);
    expect(notified).toBeGreaterThan(1);
    off();
    const before = notified;
    outbox.discard('a');
    expect(notified).toBe(before);
  });
});

describe('persistence', () => {
  it('never touches storage: the outbox module imports nothing that could write', async () => {
    const { readFileSync } = await import('node:fs');
    const source = readFileSync(new URL('./outbox.ts', import.meta.url), 'utf8');
    const imports = source.match(/^import .*$/gm) ?? [];
    expect(imports).toEqual(["import type { SendResult } from './types';"]);
    const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    expect(code).not.toMatch(/AsyncStorage|sqlite|file-system|\bkv\b|localStorage|SecureStore/i);
  });
});

import { describe, expect, it } from 'vitest';

import { parseDoorbell, pollIntervalMs } from './doorbell';

const A = '0197c0de-0000-7000-8000-000000000001';
const B = '0197c0de-0000-7000-8000-000000000002';

describe('parseDoorbell', () => {
  it('reads ids and the type', () => {
    expect(parseDoorbell(JSON.stringify({ type: 'message.created', conversationId: A, messageId: B, at: '2026-11-26T10:00:00Z' }))).toEqual({
      type: 'message.created',
      conversationId: A,
      messageId: B,
      at: '2026-11-26T10:00:00Z',
    });
    expect(parseDoorbell(JSON.stringify({ type: 'message.updated', conversationId: A, messageId: B }))?.type).toBe('message.updated');
  });

  it('ignores anything else', () => {
    for (const body of [
      '',
      'nope',
      '[]',
      '{}',
      JSON.stringify({ type: 'notification.created' }),
      JSON.stringify({ type: 'message.created', conversationId: 'x', messageId: B }),
      JSON.stringify({ type: 'message.created', conversationId: A }),
    ]) {
      expect(parseDoorbell(body)).toBeNull();
    }
  });

  it('never carries content through: an injected body is dropped', () => {
    const parsed = parseDoorbell(JSON.stringify({ type: 'message.created', conversationId: A, messageId: B, body: 'secret' }));
    expect(parsed).not.toBeNull();
    expect(JSON.stringify(parsed)).not.toContain('secret');
  });
});

describe('pollIntervalMs', () => {
  it('does not poll while the socket is up', () => {
    expect(pollIntervalMs(true, 'conversation')).toBe(false);
    expect(pollIntervalMs(true, 'inbox')).toBe(false);
  });
  it('polls an open conversation faster than the inbox when the socket is down', () => {
    const conversation = pollIntervalMs(false, 'conversation') as number;
    const inbox = pollIntervalMs(false, 'inbox') as number;
    const badge = pollIntervalMs(false, 'badge') as number;
    expect(conversation).toBeLessThan(inbox);
    expect(inbox).toBeLessThan(badge);
  });
});

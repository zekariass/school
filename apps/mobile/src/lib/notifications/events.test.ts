import { describe, expect, it } from 'vitest';

import { parseRealtimeEvent } from './events';

const notification = { id: 'n1', title: 'Hi', createdAt: '2026-11-24T10:00:00Z', typeCode: 'X', data: {}, priority: 'NORMAL' };

describe('parseRealtimeEvent', () => {
  it('reads the three event kinds', () => {
    expect(parseRealtimeEvent(JSON.stringify({ type: 'notification.created', notification, unreadCount: 2 }))).toMatchObject({
      type: 'notification.created',
      unreadCount: 2,
    });
    expect(parseRealtimeEvent('{"type":"notification.read","ids":["a"],"all":false,"unreadCount":1}')).toEqual({
      type: 'notification.read',
      ids: ['a'],
      all: false,
      unreadCount: 1,
    });
    expect(parseRealtimeEvent('{"type":"notification.archived","id":"a","unreadCount":0}')).toEqual({
      type: 'notification.archived',
      id: 'a',
      unreadCount: 0,
    });
  });
  it('ignores anything else', () => {
    for (const body of ['', 'nope', '[]', '{"type":"other","unreadCount":1}', '{"type":"notification.read"}', '{"type":"notification.created","unreadCount":1}']) {
      expect(parseRealtimeEvent(body)).toBeNull();
    }
  });
});

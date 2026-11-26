import { describe, expect, it } from 'vitest';

import { conversationIdFromPath, isForOpenConversation } from './notifications';

const ID = '6d7d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
const OTHER = '7e8d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';

describe('conversationIdFromPath', () => {
  it('reads the id of /messages/{id}', () => {
    expect(conversationIdFromPath(`/messages/${ID}`)).toBe(ID);
  });
  it('rejects everything else', () => {
    for (const path of [`/messages/${ID}/x`, `/messages/${ID}?a=b`, '/messages/groups', '/messages', `/feed/posts/${ID}`, '', null, 3]) {
      expect(conversationIdFromPath(path)).toBeNull();
    }
  });
});

describe('isForOpenConversation (the foreground banner)', () => {
  it('is true only for a message push of the conversation that is on screen', () => {
    expect(isForOpenConversation({ path: `/messages/${ID}` }, ID)).toBe(true);
    expect(isForOpenConversation({ body: { path: `/messages/${ID}` } }, ID.toUpperCase())).toBe(true);
    expect(isForOpenConversation({ path: `/messages/${OTHER}` }, ID)).toBe(false);
    expect(isForOpenConversation({ path: `/messages/${ID}` }, null)).toBe(false);
    expect(isForOpenConversation({ path: '/announcements' }, ID)).toBe(false);
    expect(isForOpenConversation(null, ID)).toBe(false);
  });
});

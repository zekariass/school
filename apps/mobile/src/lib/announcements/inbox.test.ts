import { describe, expect, it } from 'vitest';

import { view } from './fixtures';
import { AUTHOR_STATUS_FILTERS, badgeCount, inboxQueryParams, isInboxEmpty, isUnreadCard, needsAcknowledgementCard, showNewBadge } from './inbox';

describe('inboxQueryParams', () => {
  it('leaves the defaults out', () => {
    expect(inboxQueryParams({ schoolId: null, filter: 'ALL', sort: 'NEWEST' })).toEqual({});
  });
  it('sends only what differs', () => {
    expect(inboxQueryParams({ schoolId: 's1', filter: 'UNREAD', sort: 'UNREAD_FIRST' })).toEqual({ schoolId: 's1', filter: 'UNREAD', sort: 'UNREAD_FIRST' });
    expect(inboxQueryParams({ schoolId: null, filter: 'NEEDS_ACKNOWLEDGEMENT', sort: 'NEWEST' })).toEqual({ filter: 'NEEDS_ACKNOWLEDGEMENT' });
  });
});

describe('cards', () => {
  it('a published card without a read time is unread; withdrawn ones are not', () => {
    expect(isUnreadCard({ status: 'PUBLISHED' })).toBe(true);
    expect(isUnreadCard({ status: 'PUBLISHED', readAt: '2026-11-26T10:00:00Z' })).toBe(false);
    expect(isUnreadCard({ status: 'WITHDRAWN' })).toBe(false);
  });
  it('needs a confirmation while required, published and not acknowledged', () => {
    expect(needsAcknowledgementCard({ status: 'PUBLISHED', requiresAcknowledgement: true })).toBe(true);
    expect(needsAcknowledgementCard({ status: 'PUBLISHED', requiresAcknowledgement: true, acknowledgedAt: 'x' })).toBe(false);
    expect(needsAcknowledgementCard({ status: 'PUBLISHED', requiresAcknowledgement: false })).toBe(false);
    expect(needsAcknowledgementCard({ status: 'WITHDRAWN', requiresAcknowledgement: true })).toBe(false);
  });
});

describe('showNewBadge', () => {
  it('shows for an addressed reader who has not read it', () => {
    expect(showNewBadge(view())).toBe(true);
  });
  it('never for someone who found it in the school list (not in the recipient snapshot)', () => {
    expect(showNewBadge(view({ mine: { acknowledged: false, read: false, recipient: false, reacknowledgementNeeded: false } }))).toBe(false);
  });
  it('not once read, not when withdrawn', () => {
    expect(showNewBadge(view({ mine: { acknowledged: false, read: true, recipient: true, reacknowledgementNeeded: false } }))).toBe(false);
    expect(showNewBadge(view({ status: 'WITHDRAWN' }))).toBe(false);
  });
});

describe('badgeCount', () => {
  it('is the larger of unread and waiting for confirmation (they overlap)', () => {
    expect(badgeCount({ unread: 2, needingAcknowledgement: 5 })).toBe(5);
    expect(badgeCount({ unread: 3, needingAcknowledgement: 0 })).toBe(3);
    expect(badgeCount(undefined)).toBe(0);
  });
});

describe('lists', () => {
  it('tells empty from loading', () => {
    expect(isInboxEmpty(undefined)).toBe(true);
    expect(isInboxEmpty([{ items: [] }])).toBe(true);
    expect(isInboxEmpty([{ items: [1] }])).toBe(false);
  });
  it('lets an author filter by every status', () => {
    expect(AUTHOR_STATUS_FILTERS).toEqual(['ALL', 'DRAFT', 'SCHEDULED', 'PUBLISHED', 'WITHDRAWN']);
  });
});

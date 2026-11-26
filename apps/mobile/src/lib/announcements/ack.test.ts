import { describe, expect, it } from 'vitest';

import { ackState, canAcknowledge, withAcknowledged, withRead } from './ack';
import { view } from './fixtures';

describe('ackState', () => {
  it('asks nothing when no acknowledgement is required', () => {
    expect(ackState(view({ requiresAcknowledgement: false }))).toBe('none');
  });
  it('asks a recipient who has not acknowledged', () => {
    const v = view();
    expect(ackState(v)).toBe('needed');
    expect(canAcknowledge(v)).toBe(true);
  });
  it('is done once acknowledged for the current revision', () => {
    const v = view({ revision: 2, mine: { acknowledged: true, acknowledgedRevision: 2, read: true, recipient: true, reacknowledgementNeeded: false } });
    expect(ackState(v)).toBe('done');
    expect(canAcknowledge(v)).toBe(false);
  });
  it('asks again after a material edit (the API raises reacknowledgementNeeded)', () => {
    const v = view({ revision: 3, mine: { acknowledged: true, acknowledgedRevision: 2, read: true, recipient: true, reacknowledgementNeeded: true } });
    expect(ackState(v)).toBe('renew');
    expect(canAcknowledge(v)).toBe(true);
  });
  it('does not ask again after a silent correction, although the revision went up', () => {
    // every edit after publishing raises `revision`: acknowledgedRevision < revision is no sign of a material edit
    const v = view({ revision: 3, mine: { acknowledged: true, acknowledgedRevision: 1, read: true, recipient: true, reacknowledgementNeeded: false } });
    expect(ackState(v)).toBe('done');
    expect(canAcknowledge(v)).toBe(false);
  });
  it('asks nobody who was not addressed (a late reader of the school list)', () => {
    const v = view({ mine: { acknowledged: false, read: false, recipient: false, reacknowledgementNeeded: false } });
    expect(ackState(v)).toBe('none');
    expect(canAcknowledge(v)).toBe(false);
  });
  it('a withdrawn announcement is a tombstone, nothing to confirm', () => {
    const v = view({ status: 'WITHDRAWN' });
    expect(ackState(v)).toBe('withdrawn');
    expect(canAcknowledge(v)).toBe(false);
  });
  it('drafts have no acknowledgement', () => {
    expect(ackState(view({ status: 'DRAFT' }))).toBe('none');
  });
});

describe('optimistic copies', () => {
  const now = '2026-11-26T10:00:00Z';
  it('acknowledging reads, records the current revision and clears the "needs" state', () => {
    const next = withAcknowledged(view({ revision: 2, mine: { acknowledged: true, acknowledgedRevision: 1, read: true, readAt: '2026-11-26T09:00:00Z', recipient: true, reacknowledgementNeeded: true } }), now);
    expect(ackState(next)).toBe('done');
    expect(next.mine.readAt).toBe('2026-11-26T09:00:00Z');
    expect(next.mine.acknowledgedRevision).toBe(2);
    expect(next.mine.reacknowledgementNeeded).toBe(false);
  });
  it('reading keeps the first time', () => {
    expect(withRead(view(), now).mine).toMatchObject({ read: true, readAt: now });
    const already = view({ mine: { acknowledged: false, read: true, readAt: 'first', recipient: true, reacknowledgementNeeded: false } });
    expect(withRead(already, now)).toBe(already);
  });
});

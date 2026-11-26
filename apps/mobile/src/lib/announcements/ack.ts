import type { AnnouncementView } from './types';

/**
 * Where an announcement stands for its reader:
 * - `none`: no acknowledgement is asked (or this reader cannot give one);
 * - `needed`: asked and not given yet;
 * - `renew`: given for an earlier revision, and a material edit since then asks again (`mine.reacknowledgementNeeded`);
 * - `done`: given for the current revision;
 * - `withdrawn`: nothing to acknowledge any more.
 */
export type AckState = 'none' | 'needed' | 'renew' | 'done' | 'withdrawn';

export function ackState(view: Pick<AnnouncementView, 'status' | 'requiresAcknowledgement' | 'mine'>): AckState {
  if (view.status === 'WITHDRAWN') return 'withdrawn';
  if (view.status !== 'PUBLISHED' || !view.requiresAcknowledgement) return 'none';
  // Only people who were addressed give an acknowledgement (the API answers 409 to anybody else).
  if (!view.mine.recipient) return 'none';
  if (!view.mine.acknowledged) return 'needed';
  // Every edit after publishing raises `revision`, silent corrections too, so `acknowledgedRevision < revision` alone would ask
  // everybody again after a typo fix: the API says whether a material edit came after the acknowledgement.
  return view.mine.reacknowledgementNeeded ? 'renew' : 'done';
}

/** The Acknowledge button shows for `needed` and `renew`. */
export function canAcknowledge(view: Pick<AnnouncementView, 'status' | 'requiresAcknowledgement' | 'mine'>): boolean {
  const state = ackState(view);
  return state === 'needed' || state === 'renew';
}

/** The detail after an acknowledgement call: the API's answer replaces it, or an optimistic copy until then. */
export function withAcknowledged(view: AnnouncementView, nowIso: string): AnnouncementView {
  return {
    ...view,
    mine: { ...view.mine, read: true, readAt: view.mine.readAt ?? nowIso, acknowledged: true, acknowledgedAt: nowIso, acknowledgedRevision: view.revision, reacknowledgementNeeded: false },
  };
}

/** The detail after "mark read". */
export function withRead(view: AnnouncementView, nowIso: string): AnnouncementView {
  return view.mine.read ? view : { ...view, mine: { ...view.mine, read: true, readAt: nowIso } };
}

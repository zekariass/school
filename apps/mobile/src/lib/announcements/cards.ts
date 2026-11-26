import type { AnnouncementCard } from './types';

/** The card after the caller opened the announcement (the first read time stays). */
export function readWithCard(card: AnnouncementCard, nowIso: string): AnnouncementCard {
  return card.readAt ? card : { ...card, readAt: nowIso };
}

/** The card after the caller acknowledged it (acknowledging also reads). */
export function ackWithCard(card: AnnouncementCard, nowIso: string): AnnouncementCard {
  return { ...card, readAt: card.readAt ?? nowIso, acknowledgedAt: nowIso };
}

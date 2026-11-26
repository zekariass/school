import type { RecipientFilter } from './types';

/** `value` as a whole percentage of `of` (0 when nobody is expected), never above 100. */
export function percent(value: number, of: number): number {
  if (!(of > 0)) return 0;
  return Math.min(100, Math.max(0, Math.round((value / of) * 100)));
}

/**
 * The recipient filters offered in the report, the most useful first: for an announcement that asks for a confirmation the
 * people who have not confirmed, otherwise the people who have not read it. "Not reached" is the delivery failures.
 */
export function recipientFilters(requiresAcknowledgement: boolean): RecipientFilter[] {
  return requiresAcknowledgement ? ['UNACKNOWLEDGED', 'UNREAD', 'ACKNOWLEDGED', 'FAILED'] : ['UNREAD', 'FAILED'];
}

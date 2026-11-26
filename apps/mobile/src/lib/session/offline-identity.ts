import type { MeSummary } from './me-roles';

/**
 * Opening the app with no connection. A teacher whose refresh token is still on the phone (secure store) may
 * open the app offline and work from the offline cache and the outbox; for that the shell needs to know who they
 * are and which role to show, so the last profile the server confirmed is remembered here: display name and role
 * codes only (no tokens, no children). It expires after a week without contact with the server, is replaced at
 * every successful sign-in or restore, and is removed at sign-out and when the session ends.
 *
 * Nothing about authorization is decided here: the server still checks every call, and the first reconnect
 * renews the session (a revoked session ends then, and the offline data is wiped).
 */

export const OFFLINE_IDENTITY_KEY = 'school.offlineIdentity';
export const OFFLINE_IDENTITY_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

export interface OfflineIdentity {
  readonly v: 1;
  readonly userId: string;
  /** Epoch ms of the last time the server confirmed this profile. */
  readonly savedAt: number;
  readonly me: Required<Pick<MeSummary, 'displayName' | 'roleAssignments' | 'memberships'>>;
}

/** What to remember after the server confirmed `me`: only teachers work offline, so only they are remembered. */
export function toOfflineIdentity(me: MeSummary & { id?: string | undefined }, now: number): OfflineIdentity | null {
  const memberships = me.memberships ?? [];
  if (!me.id || !memberships.some((membership) => membership.type === 'TEACHER')) return null;
  return {
    v: 1,
    userId: me.id,
    savedAt: now,
    me: {
      displayName: me.displayName,
      roleAssignments: me.roleAssignments.map((assignment) => ({ roleCode: assignment.roleCode })),
      memberships: memberships.map((membership) => ({ type: membership.type })),
    },
  };
}

/** The remembered profile, or null when there is none, it is damaged, or it is too old. */
export function parseOfflineIdentity(text: string | null, now: number): OfflineIdentity | null {
  if (!text) return null;
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return null;
  }
  if (typeof raw !== 'object' || raw === null) return null;
  const value = raw as Record<string, unknown>;
  const me = value.me as Record<string, unknown> | undefined;
  if (value.v !== 1 || typeof value.userId !== 'string' || typeof value.savedAt !== 'number' || !me) return null;
  if (now - value.savedAt > OFFLINE_IDENTITY_MAX_AGE_MS || value.savedAt > now + 60_000) return null;
  if (typeof me.displayName !== 'string' || !Array.isArray(me.roleAssignments) || !Array.isArray(me.memberships)) return null;
  const roleAssignments = me.roleAssignments.flatMap((entry) => {
    const code = (entry as { roleCode?: unknown } | null)?.roleCode;
    return typeof code === 'string' ? [{ roleCode: code }] : [];
  });
  const memberships = me.memberships.flatMap((entry) => {
    const type = (entry as { type?: unknown } | null)?.type;
    return typeof type === 'string' ? [{ type }] : [];
  });
  if (!memberships.some((membership) => membership.type === 'TEACHER')) return null;
  return { v: 1, userId: value.userId, savedAt: value.savedAt, me: { displayName: me.displayName, roleAssignments, memberships } };
}

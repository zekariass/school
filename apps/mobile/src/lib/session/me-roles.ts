import { isRole, resolveActiveRole, type Role } from './roles';

/**
 * Backend role codes that map to a mobile role shell. The backend has no such roles yet:
 * teacher, guardian and student come from class and guardian memberships in M3/M5, and
 * `resolveSessionFromMe` is where those memberships get mapped then. Until then this is
 * empty and every signed-in account lands on the "no app role" screen.
 */
export const MOBILE_ROLE_BY_ROLE_CODE: Readonly<Record<string, Role>> = {};

/** The part of `GET /api/v1/me` that decides what the mobile app shows. */
export interface MeSummary {
  displayName: string;
  roleAssignments: readonly { roleCode: string }[];
}

/**
 * Why the account has no mobile shell:
 * - `app`: it has at least one mobile role;
 * - `admin`: it holds roles, but only ones managed on the web;
 * - `none`: it holds no role at all.
 */
export type AccessKind = 'app' | 'admin' | 'none';

export interface SessionIdentity {
  roles: Role[];
  activeRole: Role | null;
  accessKind: AccessKind;
}

/** Pure mapping from the signed-in user's profile to mobile roles. */
export function resolveSessionFromMe(
  me: MeSummary,
  preferredRole?: Role | null,
  mapping: Readonly<Record<string, Role>> = MOBILE_ROLE_BY_ROLE_CODE,
): SessionIdentity {
  const roles: Role[] = [];
  for (const assignment of me.roleAssignments) {
    const role = mapping[assignment.roleCode];
    if (role && isRole(role) && !roles.includes(role)) roles.push(role);
  }
  const activeRole = resolveActiveRole(roles, preferredRole);
  const accessKind: AccessKind = activeRole ? 'app' : me.roleAssignments.length > 0 ? 'admin' : 'none';
  return { roles, activeRole, accessKind };
}

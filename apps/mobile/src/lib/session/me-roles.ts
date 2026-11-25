import { isRole, resolveActiveRole, type Role } from './roles';

/**
 * Backend role codes (administrative `roleAssignments`) that map to a mobile role shell.
 * Administrators work on the web, so this stays empty; guardians get a mapping in M5.
 */
export const MOBILE_ROLE_BY_ROLE_CODE: Readonly<Record<string, Role>> = {};

/**
 * School membership types of `GET /api/v1/me` that map to a mobile role. Teachers and
 * students are memberships, not role assignments (R-02). `STAFF` (other school staff) is
 * web-only and therefore absent; the guardian role is added in M5.
 */
export const MOBILE_ROLE_BY_MEMBERSHIP: Readonly<Record<string, Role>> = {
  TEACHER: 'teacher',
  STUDENT: 'student',
};

/** The part of `GET /api/v1/me` that decides what the mobile app shows. */
export interface MeSummary {
  displayName: string;
  roleAssignments: readonly { roleCode: string }[];
  memberships?: readonly { type: string }[];
}

/**
 * Why the account has no mobile shell:
 * - `app`: it has at least one mobile role;
 * - `admin`: it holds roles or staff memberships, but only ones managed on the web;
 * - `none`: it holds no role at all.
 */
export type AccessKind = 'app' | 'admin' | 'none';

export interface SessionIdentity {
  roles: Role[];
  activeRole: Role | null;
  accessKind: AccessKind;
}

/**
 * Pure mapping from the signed-in user's profile to mobile roles: any TEACHER membership gives
 * `teacher`, any STUDENT membership `student` (an account with both gets the role switcher);
 * administrators and STAFF alone have no mobile shell. `preferredRole` (the last one used) wins
 * when the account still has it.
 */
export function resolveSessionFromMe(
  me: MeSummary,
  preferredRole?: Role | null,
  roleCodeMapping: Readonly<Record<string, Role>> = MOBILE_ROLE_BY_ROLE_CODE,
): SessionIdentity {
  const roles: Role[] = [];
  const add = (role: Role | undefined) => {
    if (role && isRole(role) && !roles.includes(role)) roles.push(role);
  };
  const memberships = me.memberships ?? [];
  for (const membership of memberships) add(MOBILE_ROLE_BY_MEMBERSHIP[membership.type]);
  for (const assignment of me.roleAssignments) add(roleCodeMapping[assignment.roleCode]);
  // Stable order whatever the order of the memberships: teacher, guardian, student.
  roles.sort((a, b) => ROLE_ORDER.indexOf(a) - ROLE_ORDER.indexOf(b));

  const activeRole = resolveActiveRole(roles, preferredRole);
  const webOnly = me.roleAssignments.length > 0 || memberships.length > 0;
  const accessKind: AccessKind = activeRole ? 'app' : webOnly ? 'admin' : 'none';
  return { roles, activeRole, accessKind };
}

const ROLE_ORDER: readonly Role[] = ['teacher', 'guardian', 'student'];

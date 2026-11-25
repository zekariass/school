/** Roles that have a role shell (tab group) in the mobile app. */
export const ROLES = ['teacher', 'guardian', 'student'] as const;
export type Role = (typeof ROLES)[number];

export function isRole(value: unknown): value is Role {
  return typeof value === 'string' && (ROLES as readonly string[]).includes(value);
}

/** First tab of each role's tab group (Expo Router group names; no `index` files, which would collide with the root `/`). */
export const ROLE_HOME_ROUTE = {
  teacher: '/(teacher)/classes',
  guardian: '/(guardian)/home',
  student: '/(student)/home',
} as const satisfies Record<Role, string>;

export const SIGN_IN_ROUTE = '/(auth)/sign-in';
/** Signed in, but the account has no role with a mobile shell (e.g. a web-only administrator). */
export const NO_APP_ROLE_ROUTE = '/(account)/no-app-role';

/** i18n key of each role's display name (existing `roles.*` catalog entries). */
export const ROLE_LABEL_KEY = {
  teacher: 'roles.teacher',
  guardian: 'roles.guardian',
  student: 'roles.student',
} as const satisfies Record<Role, `roles.${string}`>;

export function homeRouteFor(role: Role): (typeof ROLE_HOME_ROUTE)[Role] {
  return ROLE_HOME_ROUTE[role];
}

/** Keeps only known mobile roles, de-duplicated, in their original order. */
export function normalizeRoles(input: readonly unknown[]): Role[] {
  const out: Role[] = [];
  for (const value of input) {
    if (isRole(value) && !out.includes(value)) out.push(value);
  }
  return out;
}

/**
 * The role to show: the preferred one if the user has it, otherwise the first role,
 * otherwise null (no role shell to show).
 */
export function resolveActiveRole(roles: readonly Role[], preferred?: Role | null): Role | null {
  if (preferred && roles.includes(preferred)) return preferred;
  return roles[0] ?? null;
}

/**
 * `unreachable`: a refresh token is stored but the API cannot be reached, so we do not
 * know yet who is signed in. The root layout shows a retry screen instead of the routes.
 */
export type SessionStatus = 'loading' | 'signedOut' | 'signedIn' | 'unreachable';

/**
 * Where the root `index` route sends the user. `hasProfile` is true for a real signed-in
 * account, which may have no role shell to show. `signedOutTo` is where a signed-out user lands
 * (see `signedOutRoute`).
 */
export function entryRoute(
  status: SessionStatus,
  activeRole: Role | null,
  hasProfile = false,
  signedOutTo: string = SIGN_IN_ROUTE,
): string | null {
  if (status === 'loading' || status === 'unreachable') return null;
  if (status === 'signedOut') return signedOutTo;
  if (activeRole) return ROLE_HOME_ROUTE[activeRole];
  return hasProfile ? NO_APP_ROLE_ROUTE : SIGN_IN_ROUTE;
}

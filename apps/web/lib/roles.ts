/** A role assignment as `/me` reports it (the fields used here). */
export interface RoleLike {
  roleName: string;
  scope: { type: string };
  schoolSlug?: string | null;
}

/**
 * The role names that apply to a school, for the workspace header. Roles held at
 * that school (or one of its campuses) come first; a user who only reaches the
 * school through a broader grant (platform, country, organization) gets those.
 */
export function roleNamesForSchool(
  assignments: readonly RoleLike[],
  slug: string,
): string[] {
  const direct = assignments.filter((a) => a.schoolSlug === slug).map((a) => a.roleName);
  const names = direct.length
    ? direct
    : assignments
        .filter((a) => a.scope.type !== "SCHOOL" && a.scope.type !== "CAMPUS")
        .map((a) => a.roleName);
  return [...new Set(names)];
}

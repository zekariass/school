/** The schools a signed-in student belongs to (from `/me` memberships): a student normally has one, a transfer may leave two. */

export interface MembershipLike {
  type: string;
  schoolId: string;
  schoolName: string;
}

export interface SchoolChoice {
  id: string;
  name: string;
}

/** Each school once, in the order `/me` lists them, for memberships of the given type. */
export function schoolsOf(memberships: readonly MembershipLike[] | undefined, type: 'STUDENT' | 'TEACHER'): SchoolChoice[] {
  const found = new Map<string, string>();
  for (const membership of memberships ?? []) if (membership.type === type && !found.has(membership.schoolId)) found.set(membership.schoolId, membership.schoolName);
  return [...found.entries()].map(([id, name]) => ({ id, name }));
}

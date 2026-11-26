/**
 * Which messaging screens a signed-in person gets, from `GET /me` (memberships and grants). This only decides which links and
 * buttons exist; the API authorises every call. A student has no "new conversation" and no groups page: students have no
 * directory and cannot name anybody the API has not already shown them.
 */
export interface ViewerMembership {
  type: string;
  schoolId: string;
  schoolName: string;
}

export interface ViewerScope {
  scope: { type: string; schoolId?: string | null };
  schoolName?: string | null;
  permissions: readonly string[];
}

export interface ViewerMe {
  memberships: readonly ViewerMembership[];
  scopes: readonly ViewerScope[];
}

export interface MessagingViewer {
  isStudent: boolean;
  /** Offers "New conversation". */
  canStart: boolean;
  /** The schools where the caller works as a teacher or staff member or administers: for the groups page. */
  schools: { id: string; name: string }[];
  showGroups: boolean;
}

export function messagingViewer(me: ViewerMe): MessagingViewer {
  const types = new Set(me.memberships.map((m) => m.type));
  const isStudent = types.has("STUDENT") && !types.has("TEACHER") && !types.has("STAFF") && !types.has("GUARDIAN") && me.scopes.length === 0;
  const schools = new Map<string, string>();
  for (const m of me.memberships) if (m.type === "TEACHER" || m.type === "STAFF") schools.set(m.schoolId, m.schoolName);
  for (const s of me.scopes) {
    const id = s.scope.type === "SCHOOL" ? s.scope.schoolId : null;
    if (id && s.permissions.includes("school.messaging.moderate") && !schools.has(id)) schools.set(id, s.schoolName ?? id);
  }
  return {
    isStudent,
    canStart: !isStudent,
    schools: [...schools].map(([id, name]) => ({ id, name })),
    showGroups: !isStudent && schools.size > 0,
  };
}

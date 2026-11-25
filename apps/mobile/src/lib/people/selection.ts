/** Selecting students to add to a class. */

/** The API accepts at most this many student ids in one request. */
export const MAX_ADD_BATCH = 200;

export interface Candidate {
  id: string;
}

/** Students that can still be added: everyone found who is not already on the roster. */
export function addCandidates<T extends Candidate>(found: readonly T[], rosterIds: ReadonlySet<string>): T[] {
  return found.filter((student) => !rosterIds.has(student.id));
}

export function toggleSelection(selected: ReadonlySet<string>, id: string, max: number = MAX_ADD_BATCH): Set<string> {
  const next = new Set(selected);
  if (next.has(id)) next.delete(id);
  else if (next.size < max) next.add(id);
  return next;
}

/** Selects every given id (up to the limit), keeping what was selected before. */
export function selectAll(selected: ReadonlySet<string>, ids: readonly string[], max: number = MAX_ADD_BATCH): Set<string> {
  const next = new Set(selected);
  for (const id of ids) {
    if (next.size >= max) break;
    next.add(id);
  }
  return next;
}

/** Ids that may still be sent: whoever joined the class meanwhile (another device, a refresh) is dropped. */
export function withoutMembers(selected: ReadonlySet<string>, rosterIds: ReadonlySet<string>): string[] {
  return [...selected].filter((id) => !rosterIds.has(id));
}

import type { ContactLabel, Participant } from "./types";

type Guardian = Participant["guardian"];

/**
 * How a person of a conversation is called. Teachers, staff and students come with a name; a guardian only with a
 * label the API computed for this viewer: "Abebe's mother" for the child's teachers, "Parent" for other guardians,
 * "your guardian" for a student. The API never sends a guardian's own name to anybody else, so there is nothing to
 * hide here: this only maps the label to words (the UI translates the result).
 */
export type PersonName =
  | { kind: "name"; name: string }
  | { kind: "guardianOf"; relationship: string; student: string }
  | { kind: "guardianRelation"; relationship: string }
  | { kind: "guardian" }
  | { kind: "unknown" };

function nameOf(displayName: string | undefined | null, guardian: Guardian | undefined, actingAs: string): PersonName {
  if (displayName && displayName.trim()) return { kind: "name", name: displayName };
  if (actingAs === "GUARDIAN" || guardian) {
    if (guardian?.relationshipType && guardian.studentGivenName) {
      return { kind: "guardianOf", relationship: guardian.relationshipType, student: guardian.studentGivenName };
    }
    if (guardian?.relationshipType) return { kind: "guardianRelation", relationship: guardian.relationshipType };
    return { kind: "guardian" };
  }
  return { kind: "unknown" };
}

export function personName(person: Pick<Participant, "displayName" | "guardian" | "actingAs"> | undefined): PersonName {
  if (!person) return { kind: "unknown" };
  return nameOf(person.displayName, person.guardian, person.actingAs);
}

export function contactName(label: ContactLabel | undefined): PersonName {
  if (!label) return { kind: "unknown" };
  return nameOf(label.displayName, label.guardian, label.actingAs);
}

/** The first letter(s) of a name for an avatar circle: "Abebe Kebede" gives "AK". Guardian labels have no initials. */
export function initialsOf(name: PersonName): string {
  if (name.kind !== "name") return "";
  const parts = name.name.trim().split(/\s+/).filter(Boolean);
  const first = parts[0];
  const last = parts.length > 1 ? parts[parts.length - 1] : undefined;
  return [first, last]
    .filter((p): p is string => !!p)
    .map((p) => Array.from(p)[0] ?? "")
    .join("")
    .toUpperCase();
}

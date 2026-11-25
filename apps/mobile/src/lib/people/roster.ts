/** Roster view-model: what the class screens need from a roster row, and the avatar-grid layout. */

export type AccessLevel = 'BASIC' | 'FULL';

/** The API's roster row (only the fields we read). */
export interface RosterRow {
  studentId: string;
  displayName: string;
  accessLevel: AccessLevel;
  avatar?: { id: string } | null | undefined;
  avatarConfig?: Record<string, unknown> | null | undefined;
}

/**
 * A roster member as the app keeps it: name and avatar only. Date of birth, gender and username
 * are never copied here (this model is persisted in the offline cache); the student screen loads
 * them on demand when `accessLevel` is FULL.
 */
export interface RosterEntry {
  id: string;
  name: string;
  firstName: string;
  avatarMediaId: string | null;
  avatarConfig: Record<string, unknown> | null;
  accessLevel: AccessLevel;
}

/** "Abebe Kebede Tadesse" -> "Abebe" (the grid shows first names, like a classroom wall). */
export function firstNameOf(displayName: string): string {
  return displayName.trim().split(/\s+/)[0] ?? '';
}

export function toRosterEntry(row: RosterRow): RosterEntry {
  return {
    id: row.studentId,
    name: row.displayName,
    firstName: firstNameOf(row.displayName),
    avatarMediaId: row.avatar?.id ?? null,
    avatarConfig: row.avatarConfig ?? null,
    accessLevel: row.accessLevel,
  };
}

export function toRoster(rows: readonly RosterRow[]): RosterEntry[] {
  return rows.map(toRosterEntry);
}

/** Only FULL access shows date of birth, gender, username and the like. */
export function canSeePrivateFields(accessLevel: AccessLevel | undefined): boolean {
  return accessLevel === 'FULL';
}

export interface GridOptions {
  gap: number;
  /** Smallest comfortable cell width; decides how many columns fit. */
  minCell?: number;
  minColumns?: number;
  maxColumns?: number;
}

export interface GridLayout {
  columns: number;
  cellWidth: number;
  /** Diameter of the avatar in a cell. */
  avatarSize: number;
}

/** 3 to 4 columns that fill the width: phones get 3, wide screens 4. */
export function gridLayout(containerWidth: number, { gap, minCell = 96, minColumns = 3, maxColumns = 4 }: GridOptions): GridLayout {
  const width = Math.max(0, containerWidth);
  const fit = Math.floor((width + gap) / (minCell + gap));
  const columns = Math.min(maxColumns, Math.max(minColumns, fit));
  const cellWidth = Math.floor((width - gap * (columns - 1)) / columns);
  return { columns, cellWidth, avatarSize: Math.max(48, Math.min(cellWidth - 8, 120)) };
}

/** Rows of `columns` items for a plain (non-virtualised) grid. */
export function chunk<T>(items: readonly T[], size: number): T[][] {
  const rows: T[][] = [];
  for (let i = 0; i < items.length; i += size) rows.push(items.slice(i, i + size));
  return rows;
}

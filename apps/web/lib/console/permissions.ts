/** Permission codes (kept in step with the API's `Permissions`), and which console section each opens. */
export const P = {
  organizationsManage: "platform.organizations.manage",
  schoolsManage: "platform.schools.manage",
  subscriptionsManage: "platform.subscriptions.manage",
  featuresManage: "platform.features.manage",
  settingsManage: "platform.settings.manage",
  rolesManage: "platform.roles.manage",
  schoolRead: "school.read",
  schoolManage: "school.manage",
  schoolSettingsManage: "school.settings.manage",
  auditRead: "audit.read",
} as const;

export type SectionId = "schools" | "organizations" | "featureFlags" | "settings" | "audit";

export interface Section {
  id: SectionId;
  href: string;
  /** Any one of these opens the section. */
  anyOf: readonly string[];
}

export const SECTIONS: readonly Section[] = [
  { id: "schools", href: "/platform/schools", anyOf: [P.schoolRead] },
  { id: "organizations", href: "/platform/organizations", anyOf: [P.organizationsManage] },
  { id: "featureFlags", href: "/platform/feature-flags", anyOf: [P.featuresManage] },
  { id: "settings", href: "/platform/settings", anyOf: [P.settingsManage, P.schoolSettingsManage] },
  { id: "audit", href: "/platform/audit-log", anyOf: [P.auditRead] },
];

/** The sections a user with these permissions (held at any scope) sees. The API still checks every call. */
export function visibleSections(permissions: ReadonlySet<string>): Section[] {
  return SECTIONS.filter((s) => s.anyOf.some((p) => permissions.has(p)));
}

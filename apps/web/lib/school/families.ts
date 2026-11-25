/**
 * Pure rules of the guardian and consent screens in the school workspace (M5b). Visibility only:
 * the API checks every call, answers 404 to people who may not see a link and 403 to those who
 * may not change it.
 */
import { emailSchema, phoneSchema, type CountryCode } from "@school/validation";
import { SP, type SchoolAccess } from "./access";

export interface FamilyAbilities {
  /** Guardians panel on a student and the verification queue (teachers see a reduced view). */
  canSeeGuardians: boolean;
  /** Invite by SMS or email and print parent codes (teachers: when the school setting allows). */
  canInvite: boolean;
  /** Approve or reject links (teachers: lead teachers, when the school setting allows). */
  canVerify: boolean;
  /** Add a guardian directly, change flags, primary and custody restriction, revoke. Administrators only. */
  canManageLinks: boolean;
  /** Per-student consent records and recording paper consent. */
  canManageConsent: boolean;
  /** The school's policy texts. */
  canManagePolicies: boolean;
}

export function familyAbilities(access: SchoolAccess): FamilyAbilities {
  const manage = access.permissions.has(SP.guardians);
  const teacher = access.isTeacher;
  return {
    canSeeGuardians: manage || teacher,
    canInvite: manage || teacher,
    canVerify: manage || teacher,
    canManageLinks: manage,
    canManageConsent: manage,
    canManagePolicies: access.permissions.has(SP.policies),
  };
}

export type LinkStatus = "PENDING" | "ACTIVE" | "REJECTED" | "REVOKED";

/** The statuses the queue can show: administrators see all, verifying teachers only what waits. */
export function queueStatuses(canManageLinks: boolean): LinkStatus[] {
  return canManageLinks ? ["PENDING", "ACTIVE", "REJECTED", "REVOKED"] : ["PENDING"];
}

export type ContactComparison = "matches" | "differs" | "unknown";

/**
 * Invited versus actual contact. Only invitations sent to a phone or email can be compared; the
 * API reports whether the adult proved that very destination (`contactMatchesInvitation`).
 */
export function contactComparison(link: {
  requestedVia: string;
  contactMatchesInvitation?: boolean | null;
}): ContactComparison {
  if (link.requestedVia !== "INVITE_LINK") return "unknown";
  if (link.contactMatchesInvitation === true) return "matches";
  if (link.contactMatchesInvitation === false) return "differs";
  return "unknown";
}

export const PERMISSION_FLAGS = [
  "canViewAcademics",
  "canReceiveNotifications",
  "canMessageStaff",
  "canAwardPoints",
  "canGiveConsent",
  "canBookAndPay",
] as const;
export type PermissionFlag = (typeof PERMISSION_FLAGS)[number];
export type Permissions = Record<PermissionFlag, boolean>;

export interface LinkDraft {
  relationshipType: string;
  primary: boolean;
  permissions: Permissions;
  custodyRestricted: boolean;
  restrictionNote: string;
}

export interface LinkLike {
  relationshipType: string;
  primary: boolean;
  permissions: Permissions;
  custodyRestricted: boolean;
  restrictionNote?: string | null;
}

export function draftOf(link: LinkLike): LinkDraft {
  return {
    relationshipType: link.relationshipType,
    primary: link.primary,
    permissions: { ...link.permissions },
    custodyRestricted: link.custodyRestricted,
    restrictionNote: link.restrictionNote ?? "",
  };
}

export interface LinkPatch {
  relationshipType?: string;
  primary?: boolean;
  permissions?: Partial<Permissions>;
  custodyRestricted?: boolean;
  restrictionNote?: string;
}

/** Only what changed, so a patch never re-sends (or silently resets) fields the admin did not touch. */
export function linkPatch(original: LinkLike, draft: LinkDraft): LinkPatch {
  const patch: LinkPatch = {};
  if (draft.relationshipType !== original.relationshipType) patch.relationshipType = draft.relationshipType;
  if (draft.primary !== original.primary) patch.primary = draft.primary;
  const permissions: Partial<Permissions> = {};
  for (const flag of PERMISSION_FLAGS) {
    if (draft.permissions[flag] !== original.permissions[flag]) permissions[flag] = draft.permissions[flag];
  }
  if (Object.keys(permissions).length > 0) patch.permissions = permissions;
  if (draft.custodyRestricted !== original.custodyRestricted) patch.custodyRestricted = draft.custodyRestricted;
  const note = draft.restrictionNote.trim();
  if (note !== (original.restrictionNote ?? "").trim()) patch.restrictionNote = note;
  return patch;
}

export function isEmptyPatch(patch: LinkPatch): boolean {
  return Object.keys(patch).length === 0;
}

/** A restricted guardian grants nothing, so they cannot be the main contact (the API answers 409 otherwise). */
export function primaryConflict(draft: Pick<LinkDraft, "primary" | "custodyRestricted">): boolean {
  return draft.primary && draft.custodyRestricted;
}

export interface ContactFormValues {
  via: "SMS" | "EMAIL";
  phone: string;
  email: string;
}

export type ContactFormResult =
  | { ok: true; contact: { phone: string } | { email: string } }
  | { ok: false; field: "phone" | "email"; key: "validation.phone" | "validation.email" | "validation.required" };

/** The one phone (E.164) or email of an invitation or a new guardian, as the API wants it. */
export function readContact(values: ContactFormValues, country: CountryCode = "ET"): ContactFormResult {
  if (values.via === "SMS") {
    if (!values.phone.trim()) return { ok: false, field: "phone", key: "validation.required" };
    const parsed = phoneSchema(country).safeParse(values.phone);
    return parsed.success ? { ok: true, contact: { phone: parsed.data } } : { ok: false, field: "phone", key: "validation.phone" };
  }
  if (!values.email.trim()) return { ok: false, field: "email", key: "validation.required" };
  const parsed = emailSchema.safeParse(values.email);
  return parsed.success ? { ok: true, contact: { email: parsed.data } } : { ok: false, field: "email", key: "validation.email" };
}

export interface ConsentLike {
  status: "GRANTED" | "DENIED" | "UNKNOWN";
}

/** A consent counts as allowed only when granted: unknown means "not allowed" for media. */
export function isAllowed(status: ConsentLike | undefined | null): boolean {
  return status?.status === "GRANTED";
}

export interface GuardianLinkLike {
  id: string;
  status: LinkStatus;
  custodyRestricted: boolean;
  guardianName: string;
  permissions: { canGiveConsent: boolean };
}

/** Links a school may record consent for through an account: connected, not restricted and allowed to consent. */
export function consentGuardians<T extends GuardianLinkLike>(links: readonly T[]): T[] {
  return links.filter((l) => l.status === "ACTIVE" && !l.custodyRestricted && l.permissions.canGiveConsent);
}

export interface VersionDraft {
  label: string;
  language: string;
  content: string;
}

export type VersionErrors = Partial<Record<keyof VersionDraft, "validation.required" | "validation.tooLong">>;

export const POLICY_CONTENT_MAX = 20000;
export const POLICY_LABEL_MAX = 40;

export function validateVersion(draft: VersionDraft): VersionErrors {
  const errors: VersionErrors = {};
  if (!draft.label.trim()) errors.label = "validation.required";
  else if (draft.label.trim().length > POLICY_LABEL_MAX) errors.label = "validation.tooLong";
  if (!draft.language.trim()) errors.language = "validation.required";
  if (!draft.content.trim()) errors.content = "validation.required";
  else if (draft.content.length > POLICY_CONTENT_MAX) errors.content = "validation.tooLong";
  return errors;
}

export interface SchoolPolicyLike {
  document: { id: string; policyType: string };
  versions: readonly { id: string; publishedAt?: string | null }[];
}

/** The school's own document for a policy type, if it started one. */
export function documentFor<T extends SchoolPolicyLike>(policies: readonly T[], type: string): T | undefined {
  return policies.find((p) => p.document.policyType === type);
}

export const SCHOOL_POLICY_TYPES = ["ACCOUNT_PARTICIPATION", "MEDIA_PUBLICATION"] as const;
export type SchoolPolicyType = (typeof SCHOOL_POLICY_TYPES)[number];

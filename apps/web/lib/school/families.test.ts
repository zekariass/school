import { describe, expect, it } from "vitest";
import { schoolAccess, type MeLike } from "./access";
import {
  consentGuardians,
  contactComparison,
  documentFor,
  draftOf,
  familyAbilities,
  isAllowed,
  isEmptyPatch,
  linkPatch,
  primaryConflict,
  queueStatuses,
  readContact,
  validateVersion,
  type LinkLike,
} from "./families";

const admin: MeLike = {
  scopes: [
    {
      scope: { type: "SCHOOL" },
      schoolSlug: "sunrise",
      permissions: ["school.read", "school.guardians.manage", "school.policies.manage"],
    },
  ],
};
const teacher: MeLike = { scopes: [], memberships: [{ type: "TEACHER", schoolSlug: "sunrise" }] };
const student: MeLike = { scopes: [], memberships: [{ type: "STUDENT", schoolSlug: "sunrise" }] };

describe("familyAbilities", () => {
  it("lets administrators do everything their permissions say", () => {
    const a = familyAbilities(schoolAccess(admin, "sunrise"));
    expect(a).toEqual({
      canSeeGuardians: true,
      canInvite: true,
      canVerify: true,
      canManageLinks: true,
      canManageConsent: true,
      canManagePolicies: true,
    });
  });

  it("gives teachers the reduced view: no link management, consent records or policies", () => {
    const a = familyAbilities(schoolAccess(teacher, "sunrise"));
    expect(a.canSeeGuardians && a.canInvite && a.canVerify).toBe(true);
    expect(a.canManageLinks).toBe(false);
    expect(a.canManageConsent).toBe(false);
    expect(a.canManagePolicies).toBe(false);
  });

  it("gives students and strangers nothing", () => {
    for (const me of [student, admin]) {
      const a = familyAbilities(schoolAccess(me, "moon"));
      expect(Object.values(a).every((v) => v === false)).toBe(true);
    }
    const s = familyAbilities(schoolAccess(student, "sunrise"));
    expect(Object.values(s).every((v) => v === false)).toBe(true);
  });

  it("does not grant policy management through guardian management", () => {
    const only: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.guardians.manage"] }],
    };
    const a = familyAbilities(schoolAccess(only, "sunrise"));
    expect(a.canManageLinks).toBe(true);
    expect(a.canManagePolicies).toBe(false);
  });
});

describe("queueStatuses", () => {
  it("offers every status to administrators and only waiting requests to teachers", () => {
    expect(queueStatuses(true)).toEqual(["PENDING", "ACTIVE", "REJECTED", "REVOKED"]);
    expect(queueStatuses(false)).toEqual(["PENDING"]);
  });
});

describe("contactComparison", () => {
  it("compares only invitations sent to a phone or email", () => {
    expect(contactComparison({ requestedVia: "INVITE_LINK", contactMatchesInvitation: true })).toBe("matches");
    expect(contactComparison({ requestedVia: "INVITE_LINK", contactMatchesInvitation: false })).toBe("differs");
    expect(contactComparison({ requestedVia: "INVITE_LINK" })).toBe("unknown");
    expect(contactComparison({ requestedVia: "PARENT_CODE", contactMatchesInvitation: true })).toBe("unknown");
    expect(contactComparison({ requestedVia: "ADMIN", contactMatchesInvitation: null })).toBe("unknown");
  });
});

const link: LinkLike = {
  relationshipType: "MOTHER",
  primary: false,
  custodyRestricted: false,
  restrictionNote: null,
  permissions: {
    canViewAcademics: true,
    canReceiveNotifications: true,
    canMessageStaff: true,
    canAwardPoints: false,
    canGiveConsent: true,
    canBookAndPay: false,
  },
};

describe("linkPatch", () => {
  it("is empty when nothing changed", () => {
    expect(isEmptyPatch(linkPatch(link, draftOf(link)))).toBe(true);
  });

  it("sends only the changed fields", () => {
    const draft = draftOf(link);
    draft.primary = true;
    draft.permissions.canAwardPoints = true;
    expect(linkPatch(link, draft)).toEqual({ primary: true, permissions: { canAwardPoints: true } });
  });

  it("sends the restriction and its trimmed note", () => {
    const draft = draftOf(link);
    draft.custodyRestricted = true;
    draft.restrictionNote = "  court order 2026  ";
    expect(linkPatch(link, draft)).toEqual({ custodyRestricted: true, restrictionNote: "court order 2026" });
  });

  it("clears the note with an empty string", () => {
    const withNote = { ...link, custodyRestricted: true, restrictionNote: "order" };
    const draft = draftOf(withNote);
    draft.restrictionNote = "";
    expect(linkPatch(withNote, draft)).toEqual({ restrictionNote: "" });
  });

  it("does not mutate the original permissions", () => {
    const draft = draftOf(link);
    draft.permissions.canBookAndPay = true;
    expect(link.permissions.canBookAndPay).toBe(false);
  });
});

describe("primaryConflict", () => {
  it("flags a restricted main contact", () => {
    expect(primaryConflict({ primary: true, custodyRestricted: true })).toBe(true);
    expect(primaryConflict({ primary: true, custodyRestricted: false })).toBe(false);
    expect(primaryConflict({ primary: false, custodyRestricted: true })).toBe(false);
  });
});

describe("readContact", () => {
  it("reads a phone number in the default country", () => {
    expect(readContact({ via: "SMS", phone: "0911 23 45 67", email: "ignored@x.org" })).toEqual({
      ok: true,
      contact: { phone: "+251911234567" },
    });
  });

  it("reads an email and ignores a stale phone", () => {
    expect(readContact({ via: "EMAIL", phone: "123", email: " Mother@Example.org " })).toEqual({
      ok: true,
      contact: { email: "mother@example.org" },
    });
  });

  it("names the field and the problem", () => {
    expect(readContact({ via: "SMS", phone: "", email: "" })).toEqual({
      ok: false,
      field: "phone",
      key: "validation.required",
    });
    expect(readContact({ via: "SMS", phone: "12", email: "" })).toMatchObject({ key: "validation.phone" });
    expect(readContact({ via: "EMAIL", phone: "", email: "nope" })).toEqual({
      ok: false,
      field: "email",
      key: "validation.email",
    });
  });
});

describe("consent helpers", () => {
  it("treats only GRANTED as allowed", () => {
    expect(isAllowed({ status: "GRANTED" })).toBe(true);
    expect(isAllowed({ status: "UNKNOWN" })).toBe(false);
    expect(isAllowed({ status: "DENIED" })).toBe(false);
    expect(isAllowed(undefined)).toBe(false);
  });

  it("keeps the connected guardians who may give consent", () => {
    const base = { guardianName: "A", permissions: { canGiveConsent: true } };
    const links = [
      { id: "1", status: "ACTIVE" as const, custodyRestricted: false, ...base },
      { id: "2", status: "ACTIVE" as const, custodyRestricted: true, ...base },
      { id: "3", status: "PENDING" as const, custodyRestricted: false, ...base },
      { id: "4", status: "ACTIVE" as const, custodyRestricted: false, guardianName: "B", permissions: { canGiveConsent: false } },
    ];
    expect(consentGuardians(links).map((l) => l.id)).toEqual(["1"]);
  });
});

describe("validateVersion", () => {
  it("requires label, language and text", () => {
    expect(validateVersion({ label: " ", language: "", content: "" })).toEqual({
      label: "validation.required",
      language: "validation.required",
      content: "validation.required",
    });
  });

  it("limits the lengths", () => {
    expect(validateVersion({ label: "x".repeat(41), language: "en", content: "y".repeat(20001) })).toEqual({
      label: "validation.tooLong",
      content: "validation.tooLong",
    });
    expect(validateVersion({ label: "2026-12", language: "am", content: "text" })).toEqual({});
  });
});

describe("documentFor", () => {
  it("finds the school's own document of a type", () => {
    const docs = [
      { document: { id: "a", policyType: "MEDIA_PUBLICATION" }, versions: [] },
      { document: { id: "b", policyType: "ACCOUNT_PARTICIPATION" }, versions: [] },
    ];
    expect(documentFor(docs, "ACCOUNT_PARTICIPATION")?.document.id).toBe("b");
    expect(documentFor(docs, "TERMS")).toBeUndefined();
  });
});

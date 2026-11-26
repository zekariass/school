import { describe, expect, it } from "vitest";
import { messagingViewer } from "./viewer";

const school = { schoolId: "s1", schoolName: "Sunrise" };

describe("messagingViewer", () => {
  it("gives a student no 'new conversation' and no groups page", () => {
    const v = messagingViewer({ memberships: [{ type: "STUDENT", ...school }], scopes: [] });
    expect(v).toMatchObject({ isStudent: true, canStart: false, showGroups: false, schools: [] });
  });

  it("gives teachers a groups page for their schools", () => {
    const v = messagingViewer({ memberships: [{ type: "TEACHER", ...school }, { type: "TEACHER", ...school }], scopes: [] });
    expect(v).toMatchObject({ isStudent: false, canStart: true, showGroups: true, schools: [{ id: "s1", name: "Sunrise" }] });
  });

  it("treats a guardian (no school work) as someone who can start but has no groups", () => {
    expect(messagingViewer({ memberships: [], scopes: [] })).toMatchObject({ canStart: true, showGroups: false });
    expect(messagingViewer({ memberships: [{ type: "GUARDIAN", ...school }], scopes: [] })).toMatchObject({ isStudent: false, showGroups: false });
  });

  it("is not a student view when the same person also teaches or administers", () => {
    expect(messagingViewer({ memberships: [{ type: "STUDENT", ...school }, { type: "TEACHER", ...school }], scopes: [] }).isStudent).toBe(false);
    expect(messagingViewer({ memberships: [{ type: "STUDENT", ...school }], scopes: [{ scope: { type: "SCHOOL", schoolId: "s2" }, permissions: ["school.messaging.moderate"] }] }).isStudent).toBe(false);
  });

  it("lists schools a moderator administers", () => {
    const v = messagingViewer({ memberships: [], scopes: [{ scope: { type: "SCHOOL", schoolId: "s2" }, schoolName: "Other", permissions: ["school.messaging.moderate"] }] });
    expect(v.schools).toEqual([{ id: "s2", name: "Other" }]);
    expect(v.showGroups).toBe(true);
  });
});

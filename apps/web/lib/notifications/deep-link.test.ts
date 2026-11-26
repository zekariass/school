import { describe, expect, it } from "vitest";
import { deepLinkNeedsSchool, resolveDeepLink, schoolSlugOf } from "./deep-link";

describe("resolveDeepLink", () => {
  it("accepts relative app paths", () => {
    expect(resolveDeepLink({ path: "/" })).toBe("/");
    expect(resolveDeepLink({ path: "/s/hope/posts/12?tab=1" })).toBe("/s/hope/posts/12?tab=1");
  });

  it("opens the guardian screens for the paths the API sends", () => {
    expect(resolveDeepLink({ path: "/children" })).toBe("/family");
    expect(resolveDeepLink({ path: "/consents" })).toBe("/family/consent");
    expect(resolveDeepLink({ path: "/consents?x=1" })).toBe("/family/consent");
    expect(resolveDeepLink({ path: "/children/other" })).toBe("/children/other");
  });

  it("opens the child's own page when a points notification names the child", () => {
    const studentId = "0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f70";
    expect(resolveDeepLink({ path: "/children", studentId })).toBe(`/family/children/${studentId}`);
    expect(resolveDeepLink({ path: "/children", studentId: "../../x" })).toBe("/family");
    expect(resolveDeepLink({ path: "/points", studentId })).toBe("/points");
  });

  it("opens the attendance section of the child's page when the notification names the day", () => {
    const studentId = "0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f70";
    expect(resolveDeepLink({ path: "/children", studentId, date: "2026-11-25" })).toBe(`/family/children/${studentId}#attendance`);
    expect(resolveDeepLink({ path: "/children", studentId, date: "tomorrow" })).toBe(`/family/children/${studentId}`);
  });

  it("opens the class's absence inbox for an absence report, once the school's slug is known", () => {
    const classId = "0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f71";
    const data = { path: `/classes/${classId}/attendance`, classId };
    expect(deepLinkNeedsSchool(data)).toBe(true);
    expect(resolveDeepLink(data, { schoolSlug: "sunrise" })).toBe(`/s/sunrise/classes/${classId}?tab=absences`);
    expect(resolveDeepLink(data)).toBeNull();
    expect(resolveDeepLink(data, { schoolSlug: "../evil" })).toBeNull();
    expect(deepLinkNeedsSchool({ path: "/children" })).toBe(false);
    expect(deepLinkNeedsSchool(null)).toBe(false);
  });

  it("finds a school's slug from memberships first, then grants", () => {
    const me = {
      memberships: [{ schoolId: "s1", schoolSlug: "sunrise" }],
      scopes: [{ schoolSlug: "moon", scope: { schoolId: "s2" } }],
    };
    expect(schoolSlugOf(me, "s1")).toBe("sunrise");
    expect(schoolSlugOf(me, "s2")).toBe("moon");
    expect(schoolSlugOf(me, "s3")).toBeNull();
    expect(schoolSlugOf({}, "s1")).toBeNull();
  });

  it("rejects anything that could leave the origin", () => {
    for (const path of ["//evil.example", "https://evil.example", "javascript:alert(1)", "/\\evil.example", "/a\tb", "relative", ""]) {
      expect(resolveDeepLink({ path })).toBeNull();
    }
  });

  it("is null without a usable path", () => {
    expect(resolveDeepLink(undefined)).toBeNull();
    expect(resolveDeepLink(null)).toBeNull();
    expect(resolveDeepLink({})).toBeNull();
    expect(resolveDeepLink({ path: 5 })).toBeNull();
  });
});

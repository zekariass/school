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

  describe("feed links", () => {
    const schoolId = "0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f01";
    const postId = "0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f02";

    it("opens a post or a story under the notification's school", () => {
      expect(resolveDeepLink({ path: `/feed/posts/${postId}`, postId }, { schoolId })).toBe(`/feed/posts/${schoolId}/${postId}`);
      expect(resolveDeepLink({ path: `/feed/stories/${postId}` }, { schoolId })).toBe(`/feed/stories/${schoolId}/${postId}`);
    });

    it("needs the school and strict ids, otherwise just marks the item read", () => {
      expect(resolveDeepLink({ path: `/feed/posts/${postId}` })).toBeNull();
      expect(resolveDeepLink({ path: `/feed/posts/${postId}` }, { schoolId: "../x" })).toBeNull();
      expect(resolveDeepLink({ path: "/feed/posts/not-an-id" }, { schoolId })).toBe("/feed/posts/not-an-id");
      expect(resolveDeepLink({ path: `/feed/posts/${postId}/extra` }, { schoolId })).toBe(`/feed/posts/${postId}/extra`);
    });

    it("opens the approvals queue once the school's slug is known", () => {
      const data = { path: "/feed/approvals", classId: postId };
      expect(deepLinkNeedsSchool(data)).toBe(true);
      expect(resolveDeepLink(data, { schoolSlug: "sunrise" })).toBe("/s/sunrise/feed/approvals");
      expect(resolveDeepLink(data)).toBeNull();
      expect(resolveDeepLink(data, { schoolSlug: "../evil" })).toBeNull();
      expect(deepLinkNeedsSchool({ path: `/feed/posts/${postId}` })).toBe(false);
    });
  });

  describe("announcement links", () => {
    const schoolId = "0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f01";
    const id = "0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f09";

    it("opens the announcement under the notification's school", () => {
      expect(resolveDeepLink({ path: `/announcements/${id}`, announcementId: id, schoolId }, { schoolId })).toBe(`/announcements/${schoolId}/${id}`);
    });

    it("falls back to the school named in the data, not to anything else", () => {
      expect(resolveDeepLink({ path: `/announcements/${id}`, schoolId })).toBe(`/announcements/${schoolId}/${id}`);
      expect(resolveDeepLink({ path: `/announcements/${id}`, schoolId: 42 })).toBe("/announcements");
      expect(resolveDeepLink({ path: `/announcements/${id}`, schoolId: "../x" })).toBe("/announcements");
      expect(resolveDeepLink({ path: `/announcements/${id}` }, { schoolId: "https://evil.example" })).toBe("/announcements");
    });

    it("prefers the notification's own school over the data's", () => {
      const other = "0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f77";
      expect(resolveDeepLink({ path: `/announcements/${id}`, schoolId: other }, { schoolId })).toBe(`/announcements/${schoolId}/${id}`);
    });

    it("only opens well-formed ids, never a path that merely starts like one", () => {
      for (const bad of ["not-an-id", `${id}/extra`, `${id}/../x`, `${id}x`, "../../x", ""]) {
        expect(resolveDeepLink({ path: `/announcements/${bad}`, schoolId }, { schoolId })).toBeNull();
      }
      expect(resolveDeepLink({ path: "/announcements" })).toBe("/announcements");
      expect(resolveDeepLink({ path: "/announcements?x=1" })).toBe("/announcements");
      expect(resolveDeepLink({ path: `/announcements/${id}?next=https://evil.example#x`, schoolId }, { schoolId })).toBe(`/announcements/${schoolId}/${id}`);
    });

    it("needs no school slug", () => {
      expect(deepLinkNeedsSchool({ path: `/announcements/${id}` })).toBe(false);
    });
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

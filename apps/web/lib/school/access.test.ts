import { describe, expect, it } from "vitest";
import { canModerateMessages, canOpenMessages, canReviewMessages, canManageAnnouncements, canOpenAnnouncements, canOpenApprovals, canOpenFeed, canPostSchoolWide, canSendUrgentAnnouncements, isStudentOnly, isTeacherOnly, navItems, schoolAccess, type MeLike, canManageCalendar, canOpenCalendar } from "./access";

const ADMIN = [
  "school.read",
  "school.manage",
  "school.settings.manage",
  "school.academic.manage",
  "school.staff.manage",
  "school.students.manage",
  "school.import.manage",
  "class.manage",
  "school.guardians.manage",
  "school.policies.manage",
];

const schoolAdmin: MeLike = { scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ADMIN }] };
const teacher: MeLike = { scopes: [], memberships: [{ type: "TEACHER", schoolSlug: "sunrise" }] };
const student: MeLike = { scopes: [], memberships: [{ type: "STUDENT", schoolSlug: "sunrise" }] };

describe("navItems", () => {
  it("gives administrators every section", () => {
    expect(navItems(schoolAccess(schoolAdmin, "sunrise"))).toEqual([
      "dashboard",
      "classes",
      "students",
      "teachers",
      "import",
      "academic",
      "families",
      "policies",
      "settings",
      "features",
    ]);
  });

  it("shows the import section only with school.import.manage", () => {
    const withImport: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.import.manage"] }],
    };
    expect(navItems(schoolAccess(withImport, "sunrise"))).toEqual(["dashboard", "import"]);
    const campusAdmin: MeLike = {
      scopes: [{ scope: { type: "CAMPUS" }, schoolSlug: "sunrise", permissions: ["school.read", "class.manage"] }],
    };
    expect(navItems(schoolAccess(campusAdmin, "sunrise"))).not.toContain("import");
    expect(navItems(schoolAccess(teacher, "sunrise"))).not.toContain("import");
    expect(navItems(schoolAccess(withImport, "moon"))).not.toContain("import");
  });

  it("gives teachers the dashboard, their classes and the families queue", () => {
    expect(navItems(schoolAccess(teacher, "sunrise"))).toEqual(["dashboard", "myClasses", "families", "attendance", "points", "feed", "announcements", "messages", "calendar"]);
  });

  it("shows Policies only with the policies permission", () => {
    const guardianAdmin: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.read", "school.guardians.manage"] }],
    };
    expect(navItems(schoolAccess(guardianAdmin, "sunrise"))).toEqual(["dashboard", "families", "features"]);
  });

  it("shows a campus administrator the classes and the read-only features", () => {
    const campus: MeLike = {
      scopes: [{ scope: { type: "CAMPUS" }, schoolSlug: "sunrise", permissions: ["school.read", "class.manage"] }],
    };
    expect(navItems(schoolAccess(campus, "sunrise"))).toEqual(["dashboard", "classes", "features"]);
  });

  it("shows only the sections whose permission is held", () => {
    const registrar: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.read", "school.students.manage"] }],
    };
    expect(navItems(schoolAccess(registrar, "sunrise"))).toEqual(["dashboard", "students", "features"]);
  });

  it("shows Attendance to attendance readers and managers, and teachers", () => {
    const reader: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.attendance.read"] }],
    };
    expect(navItems(schoolAccess(reader, "sunrise"))).toEqual(["dashboard", "attendance"]);
    const manager: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.attendance.manage"] }],
    };
    expect(navItems(schoolAccess(manager, "sunrise"))).toContain("attendance");
    expect(navItems(schoolAccess(reader, "moon"))).not.toContain("attendance");
    expect(navItems(schoolAccess(schoolAdmin, "sunrise"))).not.toContain("attendance");
  });

  it("keeps a teacher who also administers classes on the full Classes section", () => {
    const both: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["class.manage"] }],
      memberships: [{ type: "TEACHER", schoolSlug: "sunrise" }],
    };
    expect(navItems(schoolAccess(both, "sunrise"))).toEqual(["dashboard", "classes", "families", "attendance", "points", "feed", "announcements", "messages", "calendar"]);
  });
});

describe("the school feed", () => {
  const manager: MeLike = { scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.feed.manage"] }] };
  const staff: MeLike = { scopes: [], memberships: [{ type: "STAFF", schoolSlug: "sunrise" }] };

  it("is in the menu for teachers, other staff and feed managers, not for students or strangers", () => {
    expect(navItems(schoolAccess(teacher, "sunrise"))).toContain("feed");
    expect(navItems(schoolAccess(staff, "sunrise"))).toContain("feed");
    expect(navItems(schoolAccess(manager, "sunrise"))).toContain("feed");
    expect(navItems(schoolAccess(student, "sunrise"))).not.toContain("feed");
    expect(navItems(schoolAccess(manager, "moon"))).not.toContain("feed");
    expect(canOpenFeed(schoolAccess(student, "sunrise"))).toBe(false);
  });

  it("lets only feed managers post school-wide", () => {
    expect(canPostSchoolWide(schoolAccess(manager, "sunrise"))).toBe(true);
    expect(canPostSchoolWide(schoolAccess(teacher, "sunrise"))).toBe(false);
    expect(canPostSchoolWide(schoolAccess(manager, "moon"))).toBe(false);
  });

  it("shows the approvals queue to teachers, feed managers and class managers (the API narrows it)", () => {
    const classAdmin: MeLike = { scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["class.manage"] }] };
    expect(canOpenApprovals(schoolAccess(teacher, "sunrise"))).toBe(true);
    expect(canOpenApprovals(schoolAccess(manager, "sunrise"))).toBe(true);
    expect(canOpenApprovals(schoolAccess(classAdmin, "sunrise"))).toBe(true);
    expect(canOpenApprovals(schoolAccess(staff, "sunrise"))).toBe(false);
    expect(canOpenApprovals(schoolAccess(student, "sunrise"))).toBe(false);
  });
});

describe("announcements", () => {
  const manager: MeLike = { scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.announcements.manage", "school.announcements.urgent"] }] };
  const otherStaff: MeLike = { scopes: [], memberships: [{ type: "STAFF", schoolSlug: "sunrise" }] };

  it("is in the menu for teachers, other staff and announcement managers, not for students or strangers", () => {
    expect(navItems(schoolAccess(teacher, "sunrise"))).toContain("announcements");
    expect(navItems(schoolAccess(otherStaff, "sunrise"))).toContain("announcements");
    expect(navItems(schoolAccess(manager, "sunrise"))).toContain("announcements");
    expect(navItems(schoolAccess(student, "sunrise"))).not.toContain("announcements");
    expect(navItems(schoolAccess(manager, "moon"))).not.toContain("announcements");
    // a school administrator without the announcement permissions has no section of their own
    expect(navItems(schoolAccess(schoolAdmin, "sunrise"))).not.toContain("announcements");
    expect(canOpenAnnouncements(schoolAccess(student, "sunrise"))).toBe(false);
  });

  it("separates managing from urgent sending, and neither comes with teaching", () => {
    expect(canManageAnnouncements(schoolAccess(manager, "sunrise"))).toBe(true);
    expect(canManageAnnouncements(schoolAccess(teacher, "sunrise"))).toBe(false);
    expect(canManageAnnouncements(schoolAccess(manager, "moon"))).toBe(false);
    expect(canSendUrgentAnnouncements(schoolAccess(manager, "sunrise"))).toBe(true);
    expect(canSendUrgentAnnouncements(schoolAccess(teacher, "sunrise"))).toBe(false);
  });

  it("applies a grant above school level to every school", () => {
    const platform: MeLike = { scopes: [{ scope: { type: "PLATFORM" }, permissions: ["school.announcements.manage"] }] };
    expect(canManageAnnouncements(schoolAccess(platform, "any-school"))).toBe(true);
  });
});

describe("calendar", () => {
  const manager: MeLike = { scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.calendar.manage"] }] };
  const otherStaff: MeLike = { scopes: [], memberships: [{ type: "STAFF", schoolSlug: "sunrise" }] };

  it("is in the menu for teachers, other staff and calendar managers, not for students or strangers", () => {
    expect(navItems(schoolAccess(teacher, "sunrise"))).toContain("calendar");
    expect(navItems(schoolAccess(otherStaff, "sunrise"))).toContain("calendar");
    expect(navItems(schoolAccess(manager, "sunrise"))).toContain("calendar");
    expect(navItems(schoolAccess(student, "sunrise"))).not.toContain("calendar");
    expect(navItems(schoolAccess(manager, "moon"))).not.toContain("calendar");
    // administrators without the calendar permission or a membership have no section of their own
    expect(navItems(schoolAccess(schoolAdmin, "sunrise"))).not.toContain("calendar");
    expect(canOpenCalendar(schoolAccess(student, "sunrise"))).toBe(false);
  });

  it("separates managing the calendar from teaching", () => {
    expect(canManageCalendar(schoolAccess(manager, "sunrise"))).toBe(true);
    expect(canManageCalendar(schoolAccess(teacher, "sunrise"))).toBe(false);
    expect(canManageCalendar(schoolAccess(manager, "moon"))).toBe(false);
    const platform: MeLike = { scopes: [{ scope: { type: "PLATFORM" }, permissions: ["school.calendar.manage"] }] };
    expect(canManageCalendar(schoolAccess(platform, "any-school"))).toBe(true);
  });
});

describe("schoolAccess", () => {
  it("ignores grants and memberships of other schools", () => {
    const access = schoolAccess(schoolAdmin, "moon");
    expect(access.permissions.size).toBe(0);
    expect(schoolAccess(teacher, "moon").isTeacher).toBe(false);
  });

  it("applies grants above school level to every school", () => {
    const platform: MeLike = { scopes: [{ scope: { type: "PLATFORM" }, permissions: ADMIN }] };
    expect(navItems(schoolAccess(platform, "any-school"))).toContain("academic");
  });
});

describe("student-only and teacher-only", () => {
  it("recognizes a student without anything else", () => {
    expect(isStudentOnly(schoolAccess(student, "sunrise"))).toBe(true);
    expect(isStudentOnly(schoolAccess(teacher, "sunrise"))).toBe(false);
    expect(isStudentOnly(schoolAccess(schoolAdmin, "sunrise"))).toBe(false);
  });

  it("recognizes a plain teacher", () => {
    expect(isTeacherOnly(schoolAccess(teacher, "sunrise"))).toBe(true);
    expect(isTeacherOnly(schoolAccess(schoolAdmin, "sunrise"))).toBe(false);
  });
});

describe("messaging menu", () => {
  const reviewer: MeLike = { scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.messaging.review", "school.messaging.moderate"] }] };
  const staff: MeLike = { scopes: [], memberships: [{ type: "STAFF", schoolSlug: "sunrise" }] };

  it("shows Messages to teachers, staff and moderators, never to students or other schools", () => {
    expect(navItems(schoolAccess(teacher, "sunrise"))).toContain("messages");
    expect(navItems(schoolAccess(staff, "sunrise"))).toContain("messages");
    expect(navItems(schoolAccess(reviewer, "sunrise"))).toContain("messages");
    expect(navItems(schoolAccess(student, "sunrise"))).not.toContain("messages");
    expect(navItems(schoolAccess(reviewer, "moon"))).not.toContain("messages");
    expect(canOpenMessages(schoolAccess(student, "sunrise"))).toBe(false);
  });

  it("shows the safeguarding review only with exactly the review permission (an administrator without it gets nothing)", () => {
    expect(navItems(schoolAccess(reviewer, "sunrise"))).toContain("messageReview");
    expect(navItems(schoolAccess(schoolAdmin, "sunrise"))).not.toContain("messageReview");
    expect(navItems(schoolAccess(teacher, "sunrise"))).not.toContain("messageReview");
    expect(canReviewMessages(schoolAccess(reviewer, "sunrise"))).toBe(true);
    expect(canReviewMessages(schoolAccess(reviewer, "moon"))).toBe(false);
    const moderatorOnly: MeLike = { scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.messaging.moderate"] }] };
    expect(canReviewMessages(schoolAccess(moderatorOnly, "sunrise"))).toBe(false);
    expect(canModerateMessages(schoolAccess(moderatorOnly, "sunrise"))).toBe(true);
  });
});

import { describe, expect, it } from "vitest";
import { P, visibleSections } from "@/lib/console/permissions";
import { navItems, schoolAccess, type MeLike } from "@/lib/school/access";

const scope = (permissions: string[]): MeLike => ({ scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions }] });
const teacher: MeLike = { scopes: [], memberships: [{ type: "TEACHER", schoolSlug: "sunrise" }] };
const staff: MeLike = { scopes: [], memberships: [{ type: "STAFF", schoolSlug: "sunrise" }] };
const student: MeLike = { scopes: [], memberships: [{ type: "STUDENT", schoolSlug: "sunrise" }] };

describe("the school menu", () => {
  it("shows the moderation queue to holders of school.moderation.manage only", () => {
    expect(navItems(schoolAccess(scope(["school.moderation.manage"]), "sunrise"))).toContain("moderation");
    expect(navItems(schoolAccess(scope(["school.manage", "school.read"]), "sunrise"))).not.toContain("moderation");
    expect(navItems(schoolAccess(scope(["school.moderation.manage"]), "moon"))).not.toContain("moderation");
  });

  it("shows safeguarding incidents to leads only: an administrator is not told the page exists", () => {
    expect(navItems(schoolAccess(scope(["school.safeguarding.read", "school.safeguarding.manage"]), "sunrise"))).toContain("safeguarding");
    const administrator = scope(["school.manage", "school.read", "school.moderation.manage", "school.staff.manage", "school.settings.manage"]);
    expect(navItems(schoolAccess(administrator, "sunrise"))).not.toContain("safeguarding");
    expect(navItems(schoolAccess(student, "sunrise"))).not.toContain("safeguarding");
    expect(navItems(schoolAccess(teacher, "sunrise"))).not.toContain("safeguarding");
  });

  it("offers raising a concern to teachers and other staff, never to students", () => {
    expect(navItems(schoolAccess(teacher, "sunrise"))).toContain("concern");
    expect(navItems(schoolAccess(staff, "sunrise"))).toContain("concern");
    expect(navItems(schoolAccess(student, "sunrise"))).not.toContain("concern");
  });
});

describe("the platform console", () => {
  const ids = (permissions: string[]) => visibleSections(new Set(permissions)).map((s) => s.id);

  it("opens the queue for platform moderators and the incidents for platform safeguarding leads only", () => {
    expect(ids([P.moderationManage])).toEqual(["moderation"]);
    expect(ids([P.safeguardingRead])).toEqual(["safeguarding"]);
    // An administrator holds everything except the safeguarding permissions and is not shown the incidents.
    expect(ids([P.schoolRead, P.auditRead, P.moderationManage])).toEqual(["schools", "audit", "moderation"]);
  });
});

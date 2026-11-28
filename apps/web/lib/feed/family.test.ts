import { describe, expect, it } from "vitest";
import { bannerOf, classCards, classTargets, filterableChildren, taggableChildren, type ChildLike } from "./family";
import { feedSettingId, FEED_SETTING_IDS_LIST } from "./settings";
import { isUuid } from "./ids";

const child = (id: string, given: string, classes: string[], academics = true): ChildLike => ({
  studentId: id,
  displayName: `${given} Test`,
  givenName: given,
  permissions: { canViewAcademics: academics },
  schools: [{ schoolId: "sch1", schoolName: "Sunrise", classes: classes.map((c) => ({ classId: c, name: `Class ${c}`, icon: null })) }],
});

describe("classTargets", () => {
  it("lists a class once even for siblings, with each child", () => {
    const targets = classTargets([child("a", "Abebe", ["c1"]), child("b", "Bekele", ["c1", "c2"])]);
    expect(targets.map((t) => t.classId)).toEqual(["c1", "c2"]);
    expect(targets[0]?.children).toEqual([{ studentId: "a", name: "Abebe" }, { studentId: "b", name: "Bekele" }]);
    expect(targets[1]?.children).toEqual([{ studentId: "b", name: "Bekele" }]);
  });

  it("ignores children whose link does not allow viewing classes", () => {
    expect(classTargets([child("a", "Abebe", ["c1"], false)])).toEqual([]);
  });
});

describe("filterableChildren", () => {
  it("keeps the children whose classes the guardian may see", () => {
    const kids = [child("a", "Abebe", ["c1"]), child("b", "Bekele", []), child("c", "Chaltu", ["c3"], false)];
    expect(filterableChildren(kids).map((k) => k.studentId)).toEqual(["a"]);
  });
});

describe("taggableChildren", () => {
  it("offers only the children the API allows (contributionStudentIds)", () => {
    const [target] = classTargets([child("a", "Abebe", ["c1"]), child("b", "Bekele", ["c1"])]);
    expect(taggableChildren(target!, ["b"])).toEqual([{ studentId: "b", name: "Bekele" }]);
    expect(taggableChildren(target!, [])).toEqual([]);
  });
});

describe("feed settings", () => {
  it("maps every feed setting to a localized id and nothing else", () => {
    expect(feedSettingId("feed.pin_limit")).toBe("pinLimit");
    expect(feedSettingId("feed.translation.enabled")).toBe("translation");
    expect(feedSettingId("attendance.slots")).toBeNull();
    expect(feedSettingId("toString")).toBeNull();
    expect(FEED_SETTING_IDS_LIST).toHaveLength(11);
  });
});

describe("isUuid", () => {
  it("accepts well-formed ids only", () => {
    expect(isUuid("0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f70")).toBe(true);
    expect(isUuid("../../etc/passwd")).toBe(false);
    expect(isUuid("0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f70/extra")).toBe(false);
    expect(isUuid(undefined)).toBe(false);
  });
});

describe("classCards", () => {
  it("lists each class once with the teachers the API gave, skipping hidden links", () => {
    const withTeacher: ChildLike = {
      ...child("a", "Abebe", ["c1"]),
      schools: [{ schoolId: "sch1", schoolName: "Sunrise", classes: [{ classId: "c1", name: "Class c1", icon: "A", teachers: [{ teacherId: "t1", displayName: "Mr. Bekele", role: "LEAD" }] }] }],
    };
    const cards = classCards([withTeacher, child("b", "Bekele", ["c1", "c2"]), child("c", "Chaltu", ["c9"], false)]);
    expect(cards.map((c) => c.classId)).toEqual(["c1", "c2"]);
    expect(cards[0]?.teachers).toEqual([{ teacherId: "t1", displayName: "Mr. Bekele", role: "LEAD" }]);
    expect(cards[0]?.icon).toBe("A");
    expect(cards[1]?.teachers).toEqual([]);
  });
});

describe("bannerOf", () => {
  const card = (classId: string, name: string, school = "Sunrise") => ({ schoolId: "s", schoolName: school, classId, name, icon: null, teachers: [] });
  it("names a single class and its school", () => {
    expect(bannerOf([card("c1", "Grade 5A")], false)).toEqual({ title: "Grade 5A", subtitle: "Sunrise", icon: null });
  });
  it("joins the classes of one chosen child, leaves the title to the caller for all children", () => {
    const cards = [card("c1", "Grade 5A"), card("c2", "Choir")];
    expect(bannerOf(cards, true).title).toBe("Grade 5A · Choir");
    expect(bannerOf(cards, false).title).toBeNull();
  });
  it("lists the schools once and survives no classes", () => {
    expect(bannerOf([card("c1", "A", "North"), card("c2", "B", "North")], false).subtitle).toBe("North");
    expect(bannerOf([], false)).toEqual({ title: null, subtitle: null, icon: null });
  });
});

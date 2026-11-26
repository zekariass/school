import { describe, expect, it } from "vitest";
import { classTargets, filterableChildren, taggableChildren, type ChildLike } from "./family";
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

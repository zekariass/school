import { describe, expect, it } from "vitest";
import { roleNamesForSchool } from "./roles";

const school = (roleName: string, slug: string, type = "SCHOOL") => ({ roleName, scope: { type }, schoolSlug: slug });
const broad = (roleName: string, type: string) => ({ roleName, scope: { type } });

describe("roleNamesForSchool", () => {
  it("lists roles held at that school or its campuses", () => {
    expect(
      roleNamesForSchool(
        [school("School administrator", "a"), school("Campus administrator", "a", "CAMPUS"), school("School administrator", "b")],
        "a",
      ),
    ).toEqual(["School administrator", "Campus administrator"]);
  });

  it("falls back to broader grants for people who reach the school from above", () => {
    expect(roleNamesForSchool([broad("Platform administrator", "PLATFORM"), school("Teacher", "other")], "a")).toEqual([
      "Platform administrator",
    ]);
  });

  it("is empty when nothing applies, and has no duplicates", () => {
    expect(roleNamesForSchool([school("Teacher", "other")], "a")).toEqual([]);
    expect(roleNamesForSchool([broad("Country administrator", "COUNTRY"), broad("Country administrator", "COUNTRY")], "a")).toEqual([
      "Country administrator",
    ]);
  });
});

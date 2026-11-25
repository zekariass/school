import { describe, expect, it } from "vitest";
import { groupSettings } from "./settings";

const def = (key: string, module: string, allowedScopes: string[], defaultValue: unknown = false) => ({
  key,
  module,
  description: null,
  valueSchema: { type: "boolean" },
  defaultValue,
  allowedScopes,
});

describe("groupSettings", () => {
  const definitions = [
    def("students.password_login.enabled", "students", ["PLATFORM", "COUNTRY", "ORGANIZATION", "SCHOOL"]),
    def("classes.teachers_can_create", "classes", ["SCHOOL"], true),
    def("platform.only", "platform", ["PLATFORM"]),
    def("students.login_code.ttl_days", "students", ["SCHOOL"], 365),
  ];

  it("keeps only settings a school may change", () => {
    const keys = groupSettings(definitions, []).flatMap((g) => g.settings.map((s) => s.key));
    expect(keys).not.toContain("platform.only");
    expect(keys).toHaveLength(3);
  });

  it("groups by module, sorted", () => {
    const groups = groupSettings(definitions, []);
    expect(groups.map((g) => g.module)).toEqual(["classes", "students"]);
    expect(groups[1]?.settings.map((s) => s.key)).toEqual(["students.login_code.ttl_days", "students.password_login.enabled"]);
  });

  it("shows the effective value and its source, else the default", () => {
    const groups = groupSettings(definitions, [
      { key: "classes.teachers_can_create", value: false, source: "SCHOOL" },
      { key: "students.login_code.ttl_days", value: 90, source: "ORGANIZATION" },
    ]);
    const all = groups.flatMap((g) => g.settings);
    expect(all.find((s) => s.key === "classes.teachers_can_create")).toMatchObject({ value: false, source: "SCHOOL" });
    expect(all.find((s) => s.key === "students.login_code.ttl_days")).toMatchObject({ value: 90, source: "ORGANIZATION" });
    expect(all.find((s) => s.key === "students.password_login.enabled")).toMatchObject({ value: false, source: "DEFAULT" });
  });
});

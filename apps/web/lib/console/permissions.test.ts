import { describe, expect, it } from "vitest";
import { P, visibleSections } from "./permissions";

const ids = (permissions: string[]) => visibleSections(new Set(permissions)).map((s) => s.id);

describe("visibleSections", () => {
  it("shows nothing without permissions", () => {
    expect(ids([])).toEqual([]);
  });

  it("maps permissions to sections", () => {
    expect(ids([P.schoolRead])).toEqual(["schools"]);
    expect(ids([P.schoolRead, P.organizationsManage, P.auditRead])).toEqual(["schools", "organizations", "audit"]);
    expect(ids([P.featuresManage])).toEqual(["featureFlags"]);
  });

  it("opens settings for platform or school settings management", () => {
    expect(ids([P.settingsManage])).toEqual(["settings"]);
    expect(ids([P.schoolSettingsManage])).toEqual(["settings"]);
  });

  it("keeps the section order", () => {
    expect(ids(Object.values(P))).toEqual(["schools", "organizations", "featureFlags", "settings", "audit", "moderation", "safeguarding"]);
  });
});

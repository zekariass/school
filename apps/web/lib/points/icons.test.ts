import { describe, expect, it } from "vitest";
import { BADGE_ICON_CHOICES, ICON_NAMES, resolveIcon, SKILL_ICON_CHOICES } from "./icons";

describe("resolveIcon", () => {
  it("knows the shared names", () => {
    expect(resolveIcon("heart", "star")).toEqual({ kind: "name", name: "heart" });
    expect(resolveIcon("  volume-high ", "star")).toEqual({ kind: "name", name: "volume-high" });
  });

  it("shows an emoji as it is", () => {
    expect(resolveIcon("🎯", "star")).toEqual({ kind: "emoji", text: "🎯" });
  });

  it("falls back for nothing and for unknown names", () => {
    expect(resolveIcon(null, "award")).toEqual({ kind: "name", name: "award" });
    expect(resolveIcon("  ", "award")).toEqual({ kind: "name", name: "award" });
    expect(resolveIcon("some-future-icon", "star")).toEqual({ kind: "name", name: "star" });
  });

  it("offers only names it can draw", () => {
    for (const choice of [...SKILL_ICON_CHOICES, ...BADGE_ICON_CHOICES]) expect(ICON_NAMES).toContain(choice);
  });
});

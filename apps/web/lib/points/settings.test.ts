import { describe, expect, it } from "vitest";
import { engagementSettingId } from "./settings";

describe("engagementSettingId", () => {
  it("knows the four settings a school can change", () => {
    expect(engagementSettingId("engagement.needs_work.enabled")).toBe("needsWork");
    expect(engagementSettingId("engagement.badges.enabled")).toBe("badges");
    expect(engagementSettingId("engagement.leaderboard.enabled")).toBe("leaderboard");
    expect(engagementSettingId("engagement.points.undo_window_seconds")).toBe("undoWindow");
  });

  it("leaves every other key to the generic row", () => {
    expect(engagementSettingId("engagement.public_rankings.enabled")).toBeNull();
    expect(engagementSettingId("students.password_login.enabled")).toBeNull();
    expect(engagementSettingId("toString")).toBeNull();
  });
});

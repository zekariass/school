import { describe, expect, it } from "vitest";
import { canSendTestNotification } from "./permissions";

const scope = (type: string, permissions: string[]) => ({ scope: { type }, permissions }) as never;

describe("canSendTestNotification", () => {
  it("needs the permission at platform scope", () => {
    expect(canSendTestNotification({ scopes: [scope("PLATFORM", ["platform.settings.manage"])] })).toBe(true);
  });

  it("is not granted by country or school scope", () => {
    expect(canSendTestNotification({ scopes: [scope("COUNTRY", ["platform.settings.manage"])] })).toBe(false);
    expect(canSendTestNotification({ scopes: [scope("SCHOOL", ["school.read"])] })).toBe(false);
    expect(canSendTestNotification({ scopes: [] })).toBe(false);
  });
});

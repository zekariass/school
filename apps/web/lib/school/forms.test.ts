import { describe, expect, it } from "vitest";
import { contact, dateOrder, isBlank, pastDate, required, todayIso, validUsername } from "./forms";

describe("forms", () => {
  it("requires non-blank text", () => {
    expect(required("  ")).toBe("required");
    expect(required("Abebe")).toBeNull();
    expect(isBlank(undefined)).toBe(true);
  });

  it("accepts the usernames the API accepts", () => {
    expect(validUsername("")).toBeNull();
    expect(validUsername("abebe.k-1")).toBeNull();
    expect(validUsername("Abebe")).toBe("invalid");
    expect(validUsername("ab")).toBe("invalid");
    expect(validUsername("1abc")).toBe("invalid");
    expect(validUsername("a".repeat(32))).toBe("invalid");
  });

  it("rejects birth dates in the future", () => {
    expect(pastDate("2015-04-01", "2026-11-25")).toBeNull();
    expect(pastDate("2026-11-25", "2026-11-25")).toBeNull();
    expect(pastDate("2027-01-01", "2026-11-25")).toBe("future");
    expect(pastDate("1/2/2015", "2026-11-25")).toBe("invalid");
    expect(pastDate("", "2026-11-25")).toBeNull();
  });

  it("wants the end after the start", () => {
    expect(dateOrder("2026-09-01", "2027-06-30")).toBeNull();
    expect(dateOrder("2026-09-01", "2026-09-01")).toBe("order");
    expect(dateOrder("", "2026-09-01")).toBeNull();
  });

  it("wants exactly one of phone or email", () => {
    expect(contact("", "")).toEqual({ phone: "contactOne", email: "contactOne" });
    expect(contact("0911000000", "a@b.et")).toEqual({ phone: "contactOne", email: "contactOne" });
    expect(contact("0911000000", "")).toEqual({ phone: null, email: null });
    expect(contact("", "teacher@example.org")).toEqual({ phone: null, email: null });
    expect(contact("", "nope")).toEqual({ phone: null, email: "invalid" });
    expect(contact("abc", "")).toEqual({ phone: "invalid", email: null });
  });

  it("formats today", () => {
    expect(todayIso(new Date(2026, 10, 5))).toBe("2026-11-05");
  });
});

import { describe, expect, it } from "vitest";
import { formatRelativeTime } from "./relative-time";

const now = new Date("2026-06-10T12:00:00Z");
const ago = (seconds: number) => new Date(now.getTime() - seconds * 1000);

describe("formatRelativeTime", () => {
  it("picks the largest fitting unit", () => {
    expect(formatRelativeTime(ago(10), now, "en")).toBe("now");
    expect(formatRelativeTime(ago(5 * 60), now, "en")).toBe("5 minutes ago");
    expect(formatRelativeTime(ago(3 * 3600), now, "en")).toBe("3 hours ago");
    expect(formatRelativeTime(ago(24 * 3600), now, "en")).toBe("yesterday");
    expect(formatRelativeTime(ago(3 * 24 * 3600), now, "en")).toBe("3 days ago");
    expect(formatRelativeTime(ago(14 * 24 * 3600), now, "en")).toBe("2 weeks ago");
  });

  it("accepts ISO strings and other locales", () => {
    expect(formatRelativeTime("2026-06-10T11:00:00Z", now, "en")).toBe("1 hour ago");
    expect(formatRelativeTime(ago(5 * 60), now, "am")).not.toBe("5 minutes ago");
  });

  it("handles instants slightly in the future (clock skew)", () => {
    expect(formatRelativeTime(new Date(now.getTime() + 20_000), now, "en")).toBe("now");
  });
});

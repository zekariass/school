import { describe, expect, it } from "vitest";
import { FEED_TABS, filterQuery, isFeedTab, tabFilter } from "./tabs";

describe("feed tabs", () => {
  it("map to the API's server-side filters", () => {
    expect(tabFilter("feed")).toEqual({});
    expect(tabFilter("photos")).toEqual({ photos: true });
    expect(tabFilter("activities")).toEqual({ type: "ACTIVITY" });
    expect(tabFilter("achievements")).toEqual({ type: "ACHIEVEMENT" });
  });
  it("send no parameter for the plain feed", () => {
    expect(filterQuery(tabFilter("feed"))).toEqual({});
    expect(filterQuery(undefined)).toEqual({});
    expect(filterQuery({ photos: false })).toEqual({});
    expect(filterQuery(tabFilter("photos"))).toEqual({ photos: true });
    expect(filterQuery(tabFilter("achievements"))).toEqual({ type: "ACHIEVEMENT" });
  });
  it("knows its tabs in display order", () => {
    expect(FEED_TABS).toEqual(["feed", "photos", "activities", "achievements"]);
    expect(isFeedTab("photos")).toBe(true);
    expect(isFeedTab("announcements")).toBe(false);
    expect(isFeedTab(undefined)).toBe(false);
  });
});

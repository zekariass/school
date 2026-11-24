import { describe, expect, it } from "vitest";
import { DEFAULT_REALTIME_URL, resolveRealtimeUrl } from "./realtime-url";

describe("resolveRealtimeUrl", () => {
  it("defaults to the local gateway", () => {
    expect(resolveRealtimeUrl(undefined)).toBe(DEFAULT_REALTIME_URL);
    expect(resolveRealtimeUrl("  ")).toBe(DEFAULT_REALTIME_URL);
  });

  it("takes ws and wss URLs", () => {
    expect(resolveRealtimeUrl("wss://api.example.com/ws")).toBe("wss://api.example.com/ws");
    expect(resolveRealtimeUrl("ws://10.0.0.5:8180/ws")).toBe("ws://10.0.0.5:8180/ws");
  });

  it("refuses other schemes and garbage", () => {
    expect(resolveRealtimeUrl("https://api.example.com/ws")).toBe(DEFAULT_REALTIME_URL);
    expect(resolveRealtimeUrl("not a url")).toBe(DEFAULT_REALTIME_URL);
  });
});

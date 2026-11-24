import { describe, expect, it } from "vitest";
import { resolveUploadMode, uploadTarget } from "./upload-mode";

describe("upload mode", () => {
  it("defaults to direct and accepts proxy", () => {
    expect(resolveUploadMode(undefined)).toBe("direct");
    expect(resolveUploadMode("")).toBe("direct");
    expect(resolveUploadMode("direct")).toBe("direct");
    expect(resolveUploadMode(" Proxy ")).toBe("proxy");
    expect(resolveUploadMode("whatever")).toBe("direct");
  });

  it("sends the bytes to the presigned URL directly", () => {
    expect(uploadTarget("direct", "m1", "http://store/x?sig=1")).toEqual({ url: "http://store/x?sig=1", extraHeaders: {} });
  });

  it("sends them through the BFF in proxy mode, naming the presigned URL", () => {
    expect(uploadTarget("proxy", "m 1", "http://store/x?sig=1")).toEqual({
      url: "/api/bff/uploads/m%201",
      extraHeaders: { "x-upload-target": "http://store/x?sig=1" },
    });
  });
});

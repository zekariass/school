import { describe, expect, it } from "vitest";
import { parseDestination } from "./destination-input";
import { parseCountryCode } from "./country-input";
import { parseInvitationInput } from "./invitation-input";

const token = "abcDEF123_-abcDEF123_-abcDEF123_-abcDEF1";

describe("parseCountryCode", () => {
  it("passes two capital letters only", () => {
    expect(parseCountryCode("KE")).toBe("KE");
    expect(parseCountryCode("ke")).toBeUndefined();
    expect(parseCountryCode("KEN")).toBeUndefined();
    expect(parseCountryCode("K1")).toBeUndefined();
    expect(parseCountryCode("../")).toBeUndefined();
    expect(parseCountryCode(42)).toBeUndefined();
    expect(parseCountryCode(undefined)).toBeUndefined();
  });
});

describe("country in the sign-in and invitation input", () => {
  it("forwards a well-formed country with the sign-in destination and omits a missing or malformed one", () => {
    expect(parseDestination({ phone: "0712345678", country: "KE" })).toEqual({ phone: "0712345678", country: "KE" });
    expect(parseDestination({ phone: "0712345678" })).toEqual({ phone: "0712345678" });
    expect(parseDestination({ phone: "0712345678", country: "<script>" })).toEqual({ phone: "0712345678" });
    expect(parseDestination({ email: "a@b.org", country: "KE" })).toEqual({ email: "a@b.org", country: "KE" });
  });

  it("forwards it with an invitation's destination and accept request", () => {
    expect(parseInvitationInput({ token, phone: "0712345678", country: "KE" }, "destination")).toEqual({
      key: { token },
      phone: "0712345678",
      country: "KE",
    });
    expect(parseInvitationInput({ token, phone: "0712345678", country: "xx" }, "destination")?.country).toBeUndefined();
    expect(parseInvitationInput({ token, phone: "0712345678", country: "KE", otpCode: "123456" }, "accept")?.country).toBe("KE");
  });
});

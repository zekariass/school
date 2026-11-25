import { describe, expect, it } from "vitest";
import { parseInvitationInput } from "./invitation-input";

const token = "abcDEF123_-abcDEF123_-abcDEF123_-abcDEF1";

describe("parseInvitationInput", () => {
  it("accepts a bare token for the preview", () => {
    expect(parseInvitationInput({ token }, "key")).toEqual({ key: { token } });
  });

  it("accepts a printed parent code in any case and normalizes it", () => {
    expect(parseInvitationInput({ code: "ahr4p-a4ws9" }, "key")).toEqual({ key: { code: "AHR4PA4WS9" } });
  });

  it("needs exactly one of token and code", () => {
    expect(parseInvitationInput({ token, code: "AHR4P-A4WS9" }, "key")).toBeNull();
    expect(parseInvitationInput({}, "key")).toBeNull();
  });

  it("rejects a missing or malformed key", () => {
    expect(parseInvitationInput({ token: "../x" }, "key")).toBeNull();
    expect(parseInvitationInput({ code: "AHR4P" }, "key")).toBeNull();
    expect(parseInvitationInput({ code: "AHR4P-A4WS0" }, "key")).toBeNull(); // 0 is not in the alphabet
    expect(parseInvitationInput(null, "key")).toBeNull();
    expect(parseInvitationInput("token", "key")).toBeNull();
  });

  it("needs exactly one of phone and email for the code request", () => {
    expect(parseInvitationInput({ token, phone: "0911234567" }, "destination")).toEqual({
      key: { token },
      phone: "0911234567",
    });
    expect(parseInvitationInput({ token, email: "a@b.org" }, "destination")).toEqual({ key: { token }, email: "a@b.org" });
    expect(parseInvitationInput({ token }, "destination")).toBeNull();
    expect(parseInvitationInput({ token, phone: "1", email: "a@b.org" }, "destination")).toBeNull();
  });

  it("reads the accept request and drops unknown fields", () => {
    const input = parseInvitationInput(
      { token, phone: "0911234567", otpCode: "123456", displayName: " Almaz ", preferredLanguage: "am", extra: 1 },
      "accept",
    );
    expect(input).toEqual({
      key: { token },
      phone: "0911234567",
      otpCode: "123456",
      displayName: "Almaz",
      preferredLanguage: "am",
    });
  });

  it("requires a numeric one-time code to accept", () => {
    expect(parseInvitationInput({ token, phone: "0911234567" }, "accept")).toBeNull();
    expect(parseInvitationInput({ token, phone: "0911234567", otpCode: "12ab56" }, "accept")).toBeNull();
  });

  it("ignores a language that is not a language code", () => {
    const input = parseInvitationInput({ token, phone: "0911234567", otpCode: "123456", preferredLanguage: "<script>" }, "accept");
    expect(input?.preferredLanguage).toBeUndefined();
  });
});

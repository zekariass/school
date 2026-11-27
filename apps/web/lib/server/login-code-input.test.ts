import { describe, expect, it } from "vitest";
import { parseLoginCodeInput } from "./login-code-input";

const TOKEN = "abcDEF123_-abcDEF123_-";

describe("parseLoginCodeInput", () => {
  it("forwards exactly { code }: a text code in its canonical form", () => {
    expect(parseLoginCodeInput({ code: "ahr4p a4ws9" })).toEqual({ code: "AHR4P-A4WS9" });
    expect(parseLoginCodeInput({ code: "AHR4PA4WS9" })).toEqual({ code: "AHR4P-A4WS9" });
  });

  it("forwards a QR token untouched (it is case-sensitive)", () => {
    expect(parseLoginCodeInput({ code: TOKEN })).toEqual({ code: TOKEN });
    expect(parseLoginCodeInput({ code: ` ${TOKEN} ` })).toEqual({ code: TOKEN });
  });

  it("drops every other field the browser sent", () => {
    expect(parseLoginCodeInput({ code: TOKEN, role: "TEACHER", schoolId: "x", password: "y" })).toEqual({ code: TOKEN });
  });

  it("refuses what cannot be a code, so nothing reaches the API", () => {
    for (const bad of [null, undefined, "AHR4P-A4WS9", 42, [], [TOKEN], { code: 42 }, { code: null }, { code: { toString: () => TOKEN } }, {}]) {
      expect(parseLoginCodeInput(bad)).toBeNull();
    }
    for (const code of ["", "   ", "hello", "AHR4P-A4WS", "AHR4P-A4WS90", "AHR4P-A4WS0", TOKEN + "x", TOKEN.slice(1), `https://example.com/${TOKEN}`, "a".repeat(65), "WIFI:S:x;;"]) {
      expect(parseLoginCodeInput({ code }), code).toBeNull();
    }
  });
});

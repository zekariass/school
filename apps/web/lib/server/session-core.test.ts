import { describe, expect, it } from "vitest";
import {
  buildSessionCookieOptions,
  decideRefresh,
  readJwtExpiry,
  tokensFromResponse,
} from "./session-core";

/** An unsigned JWT with the given payload (the code never verifies signatures). */
function jwt(payload: object): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${encode({ alg: "ES256", typ: "at+jwt" })}.${encode(payload)}.signature`;
}

const NOW = Date.UTC(2026, 10, 24, 12, 0, 0);
const nowSeconds = NOW / 1000;

describe("readJwtExpiry", () => {
  it("reads exp", () => {
    expect(readJwtExpiry(jwt({ exp: 1_800_000_000 }))).toBe(1_800_000_000);
  });

  it("handles base64url payloads with padding-sensitive lengths and non-ASCII", () => {
    for (const sub of ["a", "ab", "abc", "ተጠቃሚ"]) {
      expect(readJwtExpiry(jwt({ sub, exp: 42 }))).toBe(42);
    }
  });

  it.each([null, undefined, "", "abc", "a.b", "a.b.c.d", "a..c", `a.${Buffer.from("[]").toString("base64url")}.c`])(
    "returns null for %j",
    (token) => {
      expect(readJwtExpiry(token)).toBeNull();
    },
  );

  it("returns null when exp is missing or not a finite number", () => {
    expect(readJwtExpiry(jwt({}))).toBeNull();
    expect(readJwtExpiry(jwt({ exp: "1800000000" }))).toBeNull();
    expect(readJwtExpiry(jwt({ exp: null }))).toBeNull();
  });
});

describe("decideRefresh", () => {
  const rt = "refresh-token";

  it("does nothing without a refresh token", () => {
    expect(decideRefresh({ accessToken: null, refreshToken: null, now: NOW })).toBe("none");
    expect(decideRefresh({ accessToken: jwt({ exp: nowSeconds - 5 }), refreshToken: undefined, now: NOW })).toBe("none");
  });

  it("refreshes when the access token is missing or unreadable", () => {
    expect(decideRefresh({ accessToken: null, refreshToken: rt, now: NOW })).toBe("refresh");
    expect(decideRefresh({ accessToken: "garbage", refreshToken: rt, now: NOW })).toBe("refresh");
    expect(decideRefresh({ accessToken: jwt({ sub: "x" }), refreshToken: rt, now: NOW })).toBe("refresh");
  });

  it("refreshes when expired or within 60 seconds of expiry", () => {
    expect(decideRefresh({ accessToken: jwt({ exp: nowSeconds - 1 }), refreshToken: rt, now: NOW })).toBe("refresh");
    expect(decideRefresh({ accessToken: jwt({ exp: nowSeconds + 59 }), refreshToken: rt, now: NOW })).toBe("refresh");
    expect(decideRefresh({ accessToken: jwt({ exp: nowSeconds + 60 }), refreshToken: rt, now: NOW })).toBe("refresh");
  });

  it("leaves a token with more than 60 seconds left alone", () => {
    expect(decideRefresh({ accessToken: jwt({ exp: nowSeconds + 61 }), refreshToken: rt, now: NOW })).toBe("none");
    expect(decideRefresh({ accessToken: jwt({ exp: nowSeconds + 900 }), refreshToken: rt, now: NOW })).toBe("none");
  });

  it("honours a custom skew", () => {
    const accessToken = jwt({ exp: nowSeconds + 120 });
    expect(decideRefresh({ accessToken, refreshToken: rt, now: NOW, skewSeconds: 30 })).toBe("none");
    expect(decideRefresh({ accessToken, refreshToken: rt, now: NOW, skewSeconds: 300 })).toBe("refresh");
  });
});

describe("buildSessionCookieOptions", () => {
  it("is httpOnly, lax and site-wide", () => {
    expect(buildSessionCookieOptions(900, false)).toEqual({
      httpOnly: true,
      secure: false,
      sameSite: "lax",
      path: "/",
      maxAge: 900,
    });
  });

  it("is Secure when asked (production)", () => {
    expect(buildSessionCookieOptions(900, true).secure).toBe(true);
  });

  it("floors fractional lifetimes and never goes below one second", () => {
    expect(buildSessionCookieOptions(899.9, false).maxAge).toBe(899);
    expect(buildSessionCookieOptions(0, false).maxAge).toBe(1);
    expect(buildSessionCookieOptions(-30, false).maxAge).toBe(1);
  });
});

describe("tokensFromResponse", () => {
  const body = {
    accessToken: "at",
    accessTokenExpiresIn: 900,
    refreshToken: "rt",
    refreshTokenExpiresIn: 1_209_600,
    tokenType: "Bearer",
  };

  it("maps the API's token response onto cookie lifetimes", () => {
    expect(tokensFromResponse(body)).toEqual({
      accessToken: "at",
      accessTokenMaxAge: 900,
      refreshToken: "rt",
      refreshTokenMaxAge: 1_209_600,
    });
  });

  it.each([
    null,
    "x",
    {},
    { ...body, accessToken: "" },
    { ...body, refreshToken: undefined },
    { ...body, accessTokenExpiresIn: "900" },
    { ...body, refreshTokenExpiresIn: undefined },
  ])("rejects %j", (value) => {
    expect(tokensFromResponse(value)).toBeNull();
  });
});

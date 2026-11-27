import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { setSessionCookies } = vi.hoisted(() => ({ setSessionCookies: vi.fn(async () => undefined) }));
vi.mock("server-only", () => ({}));
vi.mock("@/lib/server/session", () => ({ setSessionCookies }));

import { POST } from "./route";

const URL_ = "http://localhost:3100/api/auth/login-code";
const TOKEN = "abcDEF123_-abcDEF123_-";
const TOKENS = { accessToken: "ACCESS.JWT.TOKEN", accessTokenExpiresIn: 900, refreshToken: "REFRESH-TOKEN-VALUE", refreshTokenExpiresIn: 2_592_000, tokenType: "Bearer" };

function request(body: unknown, headers: Record<string, string> = { origin: "http://localhost:3100" }, raw?: string) {
  return new Request(URL_, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: raw ?? JSON.stringify(body),
  });
}

const problem = (status: number, code: string, detail: string, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify({ type: "about:blank", title: "Problem", status, code, detail }), {
    status,
    headers: { "content-type": "application/problem+json", ...headers },
  });

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  setSessionCookies.mockClear();
});
afterEach(() => vi.unstubAllGlobals());

describe("POST /api/auth/login-code", () => {
  describe("the origin guard", () => {
    it("refuses a request from another origin before anything else happens", async () => {
      const response = await POST(request({ code: TOKEN }, { origin: "https://evil.example" }));
      expect(response.status).toBe(403);
      expect(fetchMock).not.toHaveBeenCalled();
      expect(setSessionCookies).not.toHaveBeenCalled();
    });

    it("refuses a request that names no origin at all", async () => {
      expect((await POST(request({ code: TOKEN }, {}))).status).toBe(403);
      expect((await POST(request({ code: TOKEN }, { "sec-fetch-site": "cross-site" }))).status).toBe(403);
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it("accepts a same-origin fetch that sends Sec-Fetch-Site instead of Origin", async () => {
      fetchMock.mockResolvedValueOnce(Response.json(TOKENS));
      expect((await POST(request({ code: TOKEN }, { "sec-fetch-site": "same-origin" }))).status).toBe(200);
    });
  });

  describe("the input whitelist", () => {
    it("rejects bad input with a 400 and never calls the API", async () => {
      const bodies: Array<[unknown, string?]> = [
        [{}],
        [{ code: 123 }],
        [{ code: ["AHR4P-A4WS9"] }],
        [{ code: "hello" }],
        [{ code: "AHR4P-A4WS0" }],
        [{ code: `https://example.com/${TOKEN}` }],
        [null, "not json"],
        [null, ""],
        [[TOKEN]],
        [{ code: "a".repeat(5000) }],
      ];
      for (const [body, raw] of bodies) {
        const response = await POST(request(body, undefined, raw));
        expect(response.status).toBe(400);
        expect(response.headers.get("cache-control")).toBe("no-store");
      }
      expect(fetchMock).not.toHaveBeenCalled();
      expect(setSessionCookies).not.toHaveBeenCalled();
    });

    it("forwards only { code }, in canonical form, with no session, cookie or idempotency key", async () => {
      fetchMock.mockResolvedValueOnce(Response.json(TOKENS));
      await POST(
        request(
          { code: "ahr4p a4ws9", role: "SCHOOL_ADMIN", schoolId: "x", accessToken: "stolen" },
          { origin: "http://localhost:3100", cookie: "sch_at=abc; sch_rt=def", authorization: "Bearer abc", "idempotency-key": "k-1", "x-forwarded-for": "1.2.3.4" },
        ),
      );
      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [url, init] = fetchMock.mock.calls[0]!;
      expect(String(url)).toMatch(/\/api\/v1\/auth\/login-code$/);
      expect(init?.method).toBe("POST");
      expect(JSON.parse(String(init?.body))).toEqual({ code: "AHR4P-A4WS9" });
      const sent = new Headers(init?.headers);
      expect(sent.get("authorization")).toBeNull();
      expect(sent.get("cookie")).toBeNull();
      expect(sent.get("idempotency-key")).toBeNull();
      expect(sent.get("x-forwarded-for")).toBeNull();
    });

    it("sends a QR token as it is", async () => {
      fetchMock.mockResolvedValueOnce(Response.json(TOKENS));
      await POST(request({ code: TOKEN }));
      expect(JSON.parse(String(fetchMock.mock.calls[0]![1]?.body))).toEqual({ code: TOKEN });
    });
  });

  describe("a good code", () => {
    it("sets the session cookies from the tokens and returns only { ok: true }", async () => {
      fetchMock.mockResolvedValueOnce(Response.json(TOKENS));
      const response = await POST(request({ code: "AHR4P-A4WS9" }));
      expect(response.status).toBe(200);
      expect(response.headers.get("cache-control")).toBe("no-store");
      expect(setSessionCookies).toHaveBeenCalledTimes(1);
      expect(setSessionCookies).toHaveBeenCalledWith({
        accessToken: TOKENS.accessToken,
        accessTokenMaxAge: 900,
        refreshToken: TOKENS.refreshToken,
        refreshTokenMaxAge: 2_592_000,
      });
      const text = await response.text();
      expect(JSON.parse(text)).toEqual({ ok: true });
      // No token, and not the code, anywhere in what the browser receives: body or headers.
      for (const secret of [TOKENS.accessToken, TOKENS.refreshToken, "AHR4P"]) {
        expect(text).not.toContain(secret);
        expect([...response.headers.values()].join("\n")).not.toContain(secret);
      }
      expect(response.headers.get("set-cookie")).toBeNull();
    });

    it("answers 502 and sets nothing when the API's success answer is not a token pair", async () => {
      fetchMock.mockResolvedValueOnce(Response.json({ ok: true }));
      expect((await POST(request({ code: TOKEN }))).status).toBe(502);
      expect(setSessionCookies).not.toHaveBeenCalled();
    });
  });

  describe("a code the API refuses", () => {
    // The API answers a wrong, an expired, a revoked code and a school that is not ACTIVE with one and the same 400.
    const SAME = () => problem(400, "auth.invalid_code", "That code did not work.");

    it.each(["wrong", "expired", "revoked", "school not ACTIVE"])("relays the API's generic 400 for a %s code and signs nobody in", async () => {
      fetchMock.mockResolvedValueOnce(SAME());
      const response = await POST(request({ code: TOKEN }));
      expect(response.status).toBe(400);
      expect(await response.json()).toMatchObject({ code: "auth.invalid_code", detail: "That code did not work." });
      expect(setSessionCookies).not.toHaveBeenCalled();
    });

    it("relays the rate limit with its Retry-After so the page can show the wait", async () => {
      fetchMock.mockResolvedValueOnce(problem(429, "auth.rate_limited", "Too many tries.", { "retry-after": "42" }));
      const response = await POST(request({ code: TOKEN }));
      expect(response.status).toBe(429);
      expect(response.headers.get("retry-after")).toBe("42");
      expect(await response.json()).toMatchObject({ code: "auth.rate_limited" });
      expect(setSessionCookies).not.toHaveBeenCalled();
    });

    it("does not pass on headers the API should not set for the browser (cookies)", async () => {
      fetchMock.mockResolvedValueOnce(problem(400, "auth.invalid_code", "x", { "set-cookie": "evil=1" }));
      expect((await POST(request({ code: TOKEN }))).headers.get("set-cookie")).toBeNull();
    });

    it("answers 502 when the API cannot be reached", async () => {
      fetchMock.mockRejectedValueOnce(new Error("connect ECONNREFUSED"));
      const response = await POST(request({ code: TOKEN }));
      expect(response.status).toBe(502);
      expect(setSessionCookies).not.toHaveBeenCalled();
    });
  });

  it("never logs the code", async () => {
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    fetchMock.mockResolvedValueOnce(problem(400, "auth.invalid_code", "x"));
    await POST(request({ code: "AHR4P-A4WS9" }));
    fetchMock.mockRejectedValueOnce(new Error("down"));
    await POST(request({ code: TOKEN }));
    for (const spy of spies) {
      expect(JSON.stringify(spy.mock.calls)).not.toMatch(/AHR4P|abcDEF123/);
      spy.mockRestore();
    }
  });
});

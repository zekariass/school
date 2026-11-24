import { beforeEach, describe, expect, it, vi } from "vitest";
import { refreshSession, resetRefreshStore } from "./refresh";

const tokenBody = {
  accessToken: "new-at",
  accessTokenExpiresIn: 900,
  refreshToken: "new-rt",
  refreshTokenExpiresIn: 1_209_600,
  tokenType: "Bearer",
};

function respond(status: number, body: unknown = tokenBody) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

beforeEach(() => resetRefreshStore());

describe("refreshSession", () => {
  it("posts the refresh token and returns the new pair", async () => {
    const fetch = vi.fn(async () => respond(200));
    const outcome = await refreshSession("http://api.test/", "old-rt", { fetch: fetch as never, locale: "am" });
    expect(outcome).toEqual({
      status: "refreshed",
      tokens: { accessToken: "new-at", accessTokenMaxAge: 900, refreshToken: "new-rt", refreshTokenMaxAge: 1_209_600 },
    });
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://api.test/api/v1/auth/refresh");
    expect(JSON.parse(init.body as string)).toEqual({ refreshToken: "old-rt" });
    expect((init.headers as Record<string, string>)["accept-language"]).toBe("am");
  });

  it("shares one request between parallel callers with the same token (no stampede)", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const fetch = vi.fn(async () => {
      await gate;
      return respond(200);
    });
    const calls = [1, 2, 3, 4].map(() => refreshSession("http://api.test", "old-rt", { fetch: fetch as never }));
    release();
    const outcomes = await Promise.all(calls);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(new Set(outcomes).size).toBe(1);
    expect(outcomes[0]?.status).toBe("refreshed");
  });

  it("replays a fresh result to late callers still holding the old token, then forgets it", async () => {
    let now = 1_000;
    const fetch = vi.fn(async () => respond(200));
    const deps = { fetch: fetch as never, now: () => now };
    await refreshSession("http://api.test", "old-rt", deps);
    now += 5_000;
    await refreshSession("http://api.test", "old-rt", deps);
    expect(fetch).toHaveBeenCalledTimes(1);
    now += 20_000;
    await refreshSession("http://api.test", "old-rt", deps);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("does not mix up different refresh tokens", async () => {
    const fetch = vi.fn(async () => respond(200));
    await Promise.all([
      refreshSession("http://api.test", "rt-a", { fetch: fetch as never }),
      refreshSession("http://api.test", "rt-b", { fetch: fetch as never }),
    ]);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it.each([400, 401, 403])("treats %i as a rejected session", async (status) => {
    const fetch = vi.fn(async () => respond(status, { code: "auth.invalid_refresh_token" }));
    expect(await refreshSession("http://api.test", "old-rt", { fetch: fetch as never })).toEqual({ status: "rejected" });
  });

  it.each([429, 500, 503])("treats %i as unavailable and lets the next request retry", async (status) => {
    const fetch = vi.fn(async () => respond(status, {}));
    expect(await refreshSession("http://api.test", "old-rt", { fetch: fetch as never })).toEqual({
      status: "unavailable",
    });
    await refreshSession("http://api.test", "old-rt", { fetch: fetch as never });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("treats network errors and malformed bodies as unavailable", async () => {
    const down = vi.fn(async () => {
      throw new TypeError("fetch failed");
    });
    expect(await refreshSession("http://api.test", "rt-1", { fetch: down as never })).toEqual({ status: "unavailable" });
    const garbage = vi.fn(async () => respond(200, { hello: "world" }));
    expect(await refreshSession("http://api.test", "rt-2", { fetch: garbage as never })).toEqual({
      status: "unavailable",
    });
  });
});

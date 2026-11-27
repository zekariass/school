import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError } from "@/lib/api-error";
import { periodCsvFileName, periodQuery, periodQueryString, retryWait } from "./period-report";
import { downloadAttendanceCsv, downloadPeriodCsv, downloadStaffCsv } from "./queries";
import { dashboardQuery } from "./report";

describe("period report filters", () => {
  it("leaves empty and malformed filters out", () => {
    expect(periodQuery({ classId: "", slot: "", subjectId: "", from: "x", to: "" })).toEqual({});
    expect(periodQuery({ classId: "c", slot: "P2", subjectId: "s", from: "2026-11-01", to: "2026-11-30" })).toEqual({
      classId: "c",
      slot: "P2",
      subjectId: "s",
      from: "2026-11-01",
      to: "2026-11-30",
    });
  });

  it("builds the query string of the CSV call", () => {
    expect(periodQueryString({})).toBe("");
    expect(periodQueryString({ slot: "P1", from: "2026-11-01" })).toBe("?slot=P1&from=2026-11-01");
  });

  it("names the file after the slot and the range", () => {
    expect(periodCsvFileName({})).toBe("attendance-period-report.csv");
    expect(periodCsvFileName({ slot: "P3", from: "2026-11-01", to: "2026-11-30" })).toBe("attendance-period-report-P3-2026-11-01_2026-11-30.csv");
  });

  it("asks the dashboard for a subject only when one is chosen", () => {
    expect(dashboardQuery("2026-11-24", "P1", "m")).toEqual({ date: "2026-11-24", slot: "P1", subjectId: "m" });
    expect(dashboardQuery(null, "P1", null)).toEqual({ slot: "P1" });
  });

  it("splits a wait into minutes and seconds", () => {
    expect(retryWait(125)).toEqual({ minutes: 2, seconds: 5 });
    expect(retryWait(30.2)).toEqual({ minutes: 0, seconds: 31 });
    expect(retryWait(-4)).toEqual({ minutes: 0, seconds: 0 });
  });
});

describe("CSV downloads", () => {
  afterEach(() => vi.unstubAllGlobals());

  function stub(response: Response) {
    // A body can be read once: every call gets its own copy.
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response.clone()));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("window", { location: { origin: "https://app.test" } });
    vi.stubGlobal("document", { documentElement: { lang: "am" } });
    return fetchMock;
  }

  it("goes through the BFF, never cached, in the page's language, and returns the file", async () => {
    const fetchMock = stub(new Response("a,b\r\n", { headers: { "content-type": "text/csv" } }));
    const blob = await downloadPeriodCsv("sch 1", { slot: "P2", from: "2026-11-01" });
    expect(await blob.text()).toBe("a,b\r\n");
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("https://app.test/api/bff/api/v1/schools/sch%201/attendance/period-report.csv?slot=P2&from=2026-11-01");
    expect(init).toMatchObject({ cache: "no-store" });
    expect(init.headers["Accept-Language"]).toBe("am");
  });

  it("uses the staff summary paths", async () => {
    const fetchMock = stub(new Response("x"));
    await downloadStaffCsv("s", "monthly", { month: "2026-11" });
    await downloadStaffCsv("s", "daily", { from: "2026-11-01", to: "2026-11-30" });
    expect(fetchMock.mock.calls[0]![0]).toBe("https://app.test/api/bff/api/v1/schools/s/staff-attendance/reports/monthly.csv?month=2026-11");
    expect(fetchMock.mock.calls[1]![0]).toBe("https://app.test/api/bff/api/v1/schools/s/staff-attendance/reports/daily.csv?from=2026-11-01&to=2026-11-30");
  });

  it("keeps the student report on its own path", async () => {
    const fetchMock = stub(new Response("x"));
    await downloadAttendanceCsv("s", { classId: "c" });
    expect(fetchMock.mock.calls[0]![0]).toBe("https://app.test/api/bff/api/v1/schools/s/attendance/report.csv?classId=c");
  });

  it("throws the API's problem with the Retry-After seconds on a 429", async () => {
    const body = { type: "about:blank", title: "Too many", status: 429, code: "staff_attendance.export_rate_limited", detail: "Slow down." };
    stub(new Response(JSON.stringify(body), { status: 429, headers: { "content-type": "application/problem+json", "retry-after": "540" } }));
    const error = await downloadStaffCsv("s", "monthly", { month: "2026-11" }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiRequestError);
    const failure = error as ApiRequestError;
    expect(failure.status).toBe(429);
    expect(failure.retryAfter).toBe(540);
    expect(failure.problem?.detail).toBe("Slow down.");
  });

  it("throws without a problem when the body is not one", async () => {
    stub(new Response("oops", { status: 502 }));
    const error = (await downloadPeriodCsv("s", {}).catch((e: unknown) => e)) as ApiRequestError;
    expect(error.status).toBe(502);
    expect(error.problem).toBeUndefined();
    expect(error.retryAfter).toBeUndefined();
  });
});

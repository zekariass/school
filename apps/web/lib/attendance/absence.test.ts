import { describe, expect, it } from "vitest";
import { buildAbsenceRequest, canCancel, canDecide, hasErrors, inboxStatusParam, isReason, isReportStatus, reportDays, validateAbsenceForm, type AbsenceForm } from "./absence";

const today = "2026-11-25";
const form = (patch: Partial<AbsenceForm> = {}): AbsenceForm => ({ fromDate: "2026-11-26", toDate: "2026-11-27", reasonCode: "ILLNESS", note: "", ...patch });

describe("validateAbsenceForm", () => {
  it("accepts a normal report", () => {
    expect(validateAbsenceForm(form(), today)).toEqual({});
    expect(hasErrors(validateAbsenceForm(form(), today))).toBe(false);
  });

  it("requires dates and a reason", () => {
    expect(validateAbsenceForm(form({ fromDate: "", toDate: "", reasonCode: "" }), today)).toEqual({
      fromDate: "required",
      toDate: "required",
      reasonCode: "required",
    });
  });

  it("rejects impossible dates and unknown reasons", () => {
    expect(validateAbsenceForm(form({ fromDate: "2026-02-30" }), today).fromDate).toBe("invalid");
    expect(validateAbsenceForm(form({ reasonCode: "BORED" }), today).reasonCode).toBe("invalid");
  });

  it("rejects a last day before the first day", () => {
    expect(validateAbsenceForm(form({ fromDate: "2026-11-27", toDate: "2026-11-26" }), today).toDate).toBe("order");
  });

  it("accepts one day", () => {
    expect(validateAbsenceForm(form({ fromDate: "2026-11-26", toDate: "2026-11-26" }), today)).toEqual({});
  });

  it("keeps the API's window: 7 days back, 90 ahead (with a day of slack), 61 days long", () => {
    expect(validateAbsenceForm(form({ fromDate: "2026-11-18", toDate: "2026-11-18" }), today)).toEqual({});
    expect(validateAbsenceForm(form({ fromDate: "2026-11-17", toDate: "2026-11-18" }), today)).toEqual({});
    expect(validateAbsenceForm(form({ fromDate: "2026-11-16", toDate: "2026-11-18" }), today).fromDate).toBe("tooFarBack");
    expect(validateAbsenceForm(form({ fromDate: "2027-02-22", toDate: "2027-02-23" }), today)).toEqual({});
    expect(validateAbsenceForm(form({ fromDate: "2027-02-24", toDate: "2027-02-25" }), today).toDate).toBe("tooFarAhead");
    expect(validateAbsenceForm(form({ fromDate: "2026-11-26", toDate: "2027-01-25" }), today)).toEqual({}); // 61 days
    expect(validateAbsenceForm(form({ fromDate: "2026-11-26", toDate: "2027-01-26" }), today).toDate).toBe("tooLong");
  });

  it("skips the window checks when the caller does not know today", () => {
    expect(validateAbsenceForm(form({ fromDate: "2020-01-01", toDate: "2020-01-02" }), "")).toEqual({});
  });

  it("limits the note to 500 characters", () => {
    expect(validateAbsenceForm(form({ note: "x".repeat(500) }), today)).toEqual({});
    expect(validateAbsenceForm(form({ note: "x".repeat(501) }), today).note).toBe("noteTooLong");
  });
});

describe("absence report helpers", () => {
  it("builds the request without an empty note", () => {
    expect(buildAbsenceRequest(form({ note: "  " }))).toEqual({ fromDate: "2026-11-26", toDate: "2026-11-27", reasonCode: "ILLNESS" });
    expect(buildAbsenceRequest(form({ note: " fever " })).note).toBe("fever");
  });

  it("knows who may do what with a report", () => {
    expect(canCancel("SUBMITTED")).toBe(true);
    expect(canCancel("ACKNOWLEDGED")).toBe(true);
    expect(canCancel("DECLINED")).toBe(false);
    expect(canCancel("CANCELLED")).toBe(false);
    expect(canDecide("SUBMITTED")).toBe(true);
    expect(canDecide("ACKNOWLEDGED")).toBe(false); // decided once only
  });

  it("maps the inbox filter to the API's status", () => {
    expect(inboxStatusParam("PENDING")).toBe("SUBMITTED");
    expect(inboxStatusParam("ACKNOWLEDGED")).toBe("ACKNOWLEDGED");
    expect(inboxStatusParam("DECLINED")).toBe("DECLINED");
  });

  it("recognizes the reasons and statuses of the API", () => {
    expect(isReason("APPOINTMENT")).toBe(true);
    expect(isReason("appointment")).toBe(false);
    expect(isReportStatus("DECLINED")).toBe(true);
    expect(isReportStatus("PENDING")).toBe(false);
    expect(reportDays("2026-11-26", "2026-11-28")).toBe(3);
  });
});

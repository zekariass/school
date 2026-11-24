import { describe, expect, it } from "vitest";
import {
  afterStatus,
  IDLE,
  isBusy,
  percentOf,
  pollDelay,
  uploadReducer,
  type UploadState,
} from "./upload-state";

describe("uploadReducer", () => {
  it("walks through the stages to ready", () => {
    let state: UploadState = IDLE;
    state = uploadReducer(state, { type: "start" });
    expect(state).toEqual({ phase: "running", stage: "requesting", percent: 0 });
    state = uploadReducer(state, { type: "stage", stage: "uploading" });
    state = uploadReducer(state, { type: "progress", loaded: 50, total: 200 });
    expect(state).toEqual({ phase: "running", stage: "uploading", percent: 25 });
    state = uploadReducer(state, { type: "stage", stage: "completing" });
    expect(isBusy(state)).toBe(true);
    state = uploadReducer(state, { type: "stage", stage: "processing" });
    state = uploadReducer(state, { type: "ready", mediaId: "m1" });
    expect(state).toEqual({ phase: "ready", mediaId: "m1" });
    expect(isBusy(state)).toBe(false);
  });

  it("restarts progress when the bytes start flowing", () => {
    let state = uploadReducer(IDLE, { type: "start" });
    state = uploadReducer(state, { type: "stage", stage: "uploading" });
    state = uploadReducer(state, { type: "progress", loaded: 10, total: 10 });
    expect(state).toMatchObject({ percent: 100 });
    state = uploadReducer(state, { type: "stage", stage: "uploading" });
    expect(state).toMatchObject({ percent: 0 });
  });

  it("ignores progress outside the uploading stage and stages when idle", () => {
    const running = uploadReducer(IDLE, { type: "start" });
    expect(uploadReducer(running, { type: "progress", loaded: 5, total: 10 })).toBe(running);
    expect(uploadReducer(IDLE, { type: "stage", stage: "uploading" })).toBe(IDLE);
    expect(uploadReducer(IDLE, { type: "progress", loaded: 5, total: 10 })).toBe(IDLE);
  });

  it("records a failure and can be reset", () => {
    const failed = uploadReducer(uploadReducer(IDLE, { type: "start" }), { type: "failed", reason: "network" });
    expect(failed).toEqual({ phase: "failed", reason: "network" });
    expect(isBusy(failed)).toBe(false);
    expect(uploadReducer(failed, { type: "reset" })).toBe(IDLE);
    // a retry starts over
    expect(uploadReducer(failed, { type: "start" })).toEqual({ phase: "running", stage: "requesting", percent: 0 });
  });
});

describe("percentOf", () => {
  it("rounds down and stays within 0..100", () => {
    expect(percentOf(1, 3)).toBe(33);
    expect(percentOf(5, 0)).toBe(0);
    expect(percentOf(300, 200)).toBe(100);
    expect(percentOf(-5, 200)).toBe(0);
  });
});

describe("waiting for processing", () => {
  it("polls quickly at first, then every 2 seconds", () => {
    expect([0, 1, 2, 3, 4, 5, 50].map(pollDelay)).toEqual([400, 600, 1000, 1500, 2000, 2000, 2000]);
    expect(pollDelay(-3)).toBe(400);
  });

  it("maps statuses to the next step", () => {
    expect(afterStatus("READY")).toBe("ready");
    expect(afterStatus("UPLOADED")).toBe("wait");
    expect(afterStatus("PROCESSING")).toBe("wait");
    expect(afterStatus("PENDING_UPLOAD")).toBe("wait");
    expect(afterStatus("QUARANTINED")).toBe("rejected");
    expect(afterStatus("FAILED")).toBe("processing");
    expect(afterStatus("DELETED")).toBe("processing");
  });
});

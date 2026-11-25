import { describe, expect, it } from "vitest";
import {
  canCancel,
  canCommit,
  canDelete,
  commitPlan,
  effectiveStage,
  isStepDone,
  needsNewCheck,
  percent,
  pollDelayMs,
  stageOf,
  stepOf,
  type ImportStatus,
} from "./state";

const job = (status: ImportStatus, failure?: { phase: string }) => ({
  status,
  failure: failure ? { phase: failure.phase, code: "x", message: "m" } : undefined,
});

describe("stageOf (resuming a job from its state)", () => {
  it.each([
    ["UPLOADED", "columns"],
    ["PREVIEWED", "columns"],
    ["VALIDATING", "checking"],
    ["VALIDATED", "review"],
    ["COMMITTING", "applying"],
    ["COMPLETED", "done"],
    ["CANCELLED", "cancelled"],
  ] as const)("%s opens the %s screen", (status, stage) => {
    expect(stageOf(job(status))).toBe(stage);
  });

  it("sends a failed check to its own screen and a failed commit back to the review (the commit resumes)", () => {
    expect(stageOf(job("FAILED", { phase: "VALIDATE" }))).toBe("checkFailed");
    expect(stageOf(job("FAILED", { phase: "COMMIT" }))).toBe("review");
    expect(stageOf(job("FAILED"))).toBe("checkFailed");
  });
});

describe("effectiveStage", () => {
  it("lets the person go back to the columns from the review and from a failed check", () => {
    expect(effectiveStage(job("VALIDATED"), true)).toBe("columns");
    expect(effectiveStage(job("FAILED", { phase: "VALIDATE" }), true)).toBe("columns");
  });

  it("never goes back while running, after the import, or after a failed commit", () => {
    expect(effectiveStage(job("VALIDATING"), true)).toBe("checking");
    expect(effectiveStage(job("COMMITTING"), true)).toBe("applying");
    expect(effectiveStage(job("COMPLETED"), true)).toBe("done");
    expect(effectiveStage(job("FAILED", { phase: "COMMIT" }), true)).toBe("review");
  });

  it("follows the job when nothing was asked for", () => {
    expect(effectiveStage(job("VALIDATED"), false)).toBe("review");
  });
});

describe("steps", () => {
  it("maps stages to the five steps", () => {
    expect(stepOf("columns")).toBe("columns");
    expect(stepOf("checking")).toBe("check");
    expect(stepOf("checkFailed")).toBe("check");
    expect(stepOf("review")).toBe("review");
    expect(stepOf("applying")).toBe("apply");
    expect(stepOf("done")).toBe("apply");
    expect(stepOf("cancelled")).toBeNull();
  });

  it("marks the steps before the current one as done", () => {
    expect(isStepDone("file", "review")).toBe(true);
    expect(isStepDone("columns", "review")).toBe(true);
    expect(isStepDone("review", "review")).toBe(false);
    expect(isStepDone("apply", "review")).toBe(false);
    expect(isStepDone("file", null)).toBe(false);
  });
});

describe("pollDelayMs", () => {
  it("polls only while the job runs", () => {
    expect(pollDelayMs("VALIDATING", 0)).toBe(1000);
    expect(pollDelayMs("COMMITTING", 5)).toBe(1000);
    for (const status of ["UPLOADED", "PREVIEWED", "VALIDATED", "COMPLETED", "FAILED", "CANCELLED"] as const) {
      expect(pollDelayMs(status, 0)).toBe(false);
    }
    expect(pollDelayMs(undefined, 0)).toBe(false);
  });

  it("slows down for a long run", () => {
    expect(pollDelayMs("COMMITTING", 40)).toBe(2500);
    expect(pollDelayMs("COMMITTING", 200)).toBe(5000);
  });
});

describe("what a job allows", () => {
  it("cancels before the commit and deletes unless it runs", () => {
    expect(canCancel("UPLOADED")).toBe(true);
    expect(canCancel("VALIDATED")).toBe(true);
    expect(canCancel("FAILED")).toBe(true);
    expect(canCancel("COMMITTING")).toBe(false);
    expect(canCancel("COMPLETED")).toBe(false);
    expect(canCancel("CANCELLED")).toBe(false);
    expect(canDelete("VALIDATING")).toBe(false);
    expect(canDelete("COMMITTING")).toBe(false);
    expect(canDelete("COMPLETED")).toBe(true);
    expect(canDelete("CANCELLED")).toBe(true);
  });

  it("commits a validated job, or resumes a failed commit", () => {
    expect(canCommit(job("VALIDATED"))).toBe(true);
    expect(canCommit(job("FAILED", { phase: "COMMIT" }))).toBe(true);
    expect(canCommit(job("FAILED", { phase: "VALIDATE" }))).toBe(false);
    expect(canCommit(job("COMPLETED"))).toBe(false);
  });
});

describe("commitPlan", () => {
  const rows = { total: 10, valid: 6, invalid: 3, duplicates: 1, skipped: 0, toCreate: 4, toUpdate: 1, unchanged: 1 };

  it("is blocked by open duplicates first", () => {
    expect(commitPlan(rows, true).blocked).toBe("duplicates");
    expect(commitPlan(rows, false).blocked).toBe("duplicates");
  });

  it("is blocked by invalid rows unless they are left out, and then counts them as skipped", () => {
    const decided = { ...rows, duplicates: 0 };
    expect(commitPlan(decided, false).blocked).toBe("invalid");
    const skipping = commitPlan(decided, true);
    expect(skipping.blocked).toBeNull();
    expect(skipping.skipped).toBe(3);
    expect(skipping.toCreate).toBe(4);
  });

  it("adds the rows left out by decision to the skipped count", () => {
    expect(commitPlan({ ...rows, duplicates: 0, invalid: 0, skipped: 2 }, false).skipped).toBe(2);
  });

  it("has nothing to do when no row would be created, updated or unchanged", () => {
    expect(commitPlan({ total: 2, valid: 0, invalid: 0, duplicates: 0, skipped: 2, toCreate: 0, toUpdate: 0, unchanged: 0 }, false).blocked).toBe("nothing");
    expect(commitPlan(undefined, false).blocked).toBe("nothing");
  });
});

describe("needsNewCheck", () => {
  it("recognises the problems that mean the check is out of date", () => {
    expect(needsNewCheck("import.changed_since_dry_run")).toBe(true);
    expect(needsNewCheck("import.file_changed")).toBe(true);
    expect(needsNewCheck("import.has_open_duplicates")).toBe(false);
    expect(needsNewCheck(undefined)).toBe(false);
  });
});

describe("percent", () => {
  it("is 0 until the total is known and never leaves 0-100", () => {
    expect(percent(0, 0)).toBe(0);
    expect(percent(5, 10)).toBe(50);
    expect(percent(12, 10)).toBe(100);
    expect(percent(-1, 10)).toBe(0);
  });
});

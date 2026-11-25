import type { components } from "@school/api-client";

type S = components["schemas"];

export type ImportJob = S["ImportJobView"];
export type ImportPreview = S["ImportPreview"];
export type ImportRow = S["ImportRowView"];
export type ImportTypeInfo = S["ImportTypeView"];
export type ImportField = S["FieldView"];
export type ImportOptions = S["ImportOptions"];
export type MappingProfile = S["ProfileView"];
export type ImportStatus = ImportJob["status"];
export type ImportKind = ImportJob["importType"];
export type RowStatus = ImportRow["status"];

/** The order the API imports in (people before the things that refer to them); also the order of the type list. */
export const IMPORT_KINDS: readonly ImportKind[] = [
  "GRADE_LEVELS",
  "SUBJECTS",
  "CLASSES",
  "STAFF",
  "STUDENTS",
  "ENROLLMENTS",
];

/** The wizard's five steps, in order. */
export const STEPS = ["file", "columns", "check", "review", "apply"] as const;
export type Step = (typeof STEPS)[number];

/** What the job page shows: one screen per stage of the job's life. */
export type Stage = "columns" | "checking" | "checkFailed" | "review" | "applying" | "done" | "cancelled";

/** Where a job is in the wizard, from the job alone (this is also how a notification link resumes). */
export function stageOf(job: Pick<ImportJob, "status" | "failure">): Stage {
  switch (job.status) {
    case "UPLOADED":
    case "PREVIEWED":
      return "columns";
    case "VALIDATING":
      return "checking";
    case "VALIDATED":
      return "review";
    case "COMMITTING":
      return "applying";
    case "COMPLETED":
      return "done";
    case "CANCELLED":
      return "cancelled";
    case "FAILED":
      // A failed commit is resumed by the same call, from the review screen; a failed check is run again.
      return job.failure?.phase === "COMMIT" ? "review" : "checkFailed";
  }
}

/**
 * The stage to show: the job's own, unless the person went back to change the columns from the
 * review or the failure screen (the job only becomes PREVIEWED once the mapping is saved).
 */
export function effectiveStage(job: Pick<ImportJob, "status" | "failure">, editingColumns: boolean): Stage {
  const stage = stageOf(job);
  const mayEdit = stage === "review" || stage === "checkFailed";
  // A failed commit cannot be remapped: the commit resumes with the mapping it started with.
  const commitFailed = job.status === "FAILED" && job.failure?.phase === "COMMIT";
  return editingColumns && mayEdit && !commitFailed ? "columns" : stage;
}

/** The wizard step a stage belongs to (null for a cancelled import: there is nothing left to do). */
export function stepOf(stage: Stage): Step | null {
  switch (stage) {
    case "columns":
      return "columns";
    case "checking":
    case "checkFailed":
      return "check";
    case "review":
      return "review";
    case "applying":
    case "done":
      return "apply";
    case "cancelled":
      return null;
  }
}

/** Whether a step is already behind the current one (for the step indicator). */
export function isStepDone(step: Step, current: Step | null): boolean {
  return current !== null && STEPS.indexOf(step) < STEPS.indexOf(current);
}

// --- polling --------------------------------------------------------------------------------

export function isRunning(status: ImportStatus | undefined): boolean {
  return status === "VALIDATING" || status === "COMMITTING";
}

/** A job that will not change by itself any more. */
export function isFinished(status: ImportStatus | undefined): boolean {
  return status === "COMPLETED" || status === "CANCELLED";
}

/**
 * Milliseconds until the next status check, or false to stop. Only a running job is polled:
 * every second at first, then slower for a long run (a school of thousands takes a while).
 * `updates` is how many answers were received so far.
 */
export function pollDelayMs(status: ImportStatus | undefined, updates: number): number | false {
  if (!isRunning(status)) return false;
  if (updates < 30) return 1000;
  if (updates < 90) return 2500;
  return 5000;
}

// --- what a job allows ----------------------------------------------------------------------

/** Cancel is for a job that was not committed yet (the API refuses it during a run too, see canCancel). */
export function canCancel(status: ImportStatus): boolean {
  return status === "UPLOADED" || status === "PREVIEWED" || status === "VALIDATED" || status === "FAILED" || status === "VALIDATING";
}

/** Delete removes the job with its rows and file; refused while it runs. */
export function canDelete(status: ImportStatus): boolean {
  return !isRunning(status);
}

/** The commit button: validated, or a commit that failed and resumes. */
export function canCommit(job: Pick<ImportJob, "status" | "failure">): boolean {
  return job.status === "VALIDATED" || (job.status === "FAILED" && job.failure?.phase === "COMMIT");
}

export interface CommitPlan {
  /** Rows the commit creates. */
  toCreate: number;
  toUpdate: number;
  /** Rows that change nothing. */
  unchanged: number;
  /** Rows left out: skipped by a decision, and invalid ones when they are skipped. */
  skipped: number;
  invalid: number;
  duplicatesOpen: number;
  /** Why the commit button is disabled (null when it can go ahead). */
  blocked: "duplicates" | "invalid" | "nothing" | null;
}

/**
 * The counts the confirmation shows and whether the commit may go on. `skipInvalid` is the person's choice to leave
 * invalid rows out; the API refuses a commit with open duplicates, and with invalid rows unless they are skipped.
 */
export function commitPlan(rows: ImportJob["rows"], skipInvalid: boolean): CommitPlan {
  const toCreate = rows?.toCreate ?? 0;
  const toUpdate = rows?.toUpdate ?? 0;
  const unchanged = rows?.unchanged ?? 0;
  const invalid = rows?.invalid ?? 0;
  const duplicatesOpen = rows?.duplicates ?? 0;
  const skipped = (rows?.skipped ?? 0) + (skipInvalid ? invalid : 0);
  let blocked: CommitPlan["blocked"] = null;
  if (duplicatesOpen > 0) blocked = "duplicates";
  else if (invalid > 0 && !skipInvalid) blocked = "invalid";
  else if (toCreate + toUpdate + unchanged === 0) blocked = "nothing";
  return { toCreate, toUpdate, unchanged, skipped, invalid, duplicatesOpen, blocked };
}

/** Problem codes of the API that mean "the check no longer matches: run it again". */
const RECHECK_CODES = new Set([
  "import.changed_since_dry_run",
  "import.file_changed",
  "import.target_missing",
]);

export function needsNewCheck(code: string | undefined): boolean {
  return code !== undefined && RECHECK_CODES.has(code);
}

/** 0-100 for a progress bar; 0 when the total is not known yet. */
export function percent(done: number, total: number): number {
  if (!(total > 0)) return 0;
  return Math.min(100, Math.max(0, Math.floor((done / total) * 100)));
}

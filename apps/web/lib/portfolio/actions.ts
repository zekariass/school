import type { PortfolioDecision, PortfolioItem, PortfolioReview, PortfolioVisibility } from "./queries";

/**
 * What the item page offers. Everything comes from the API's `capabilities` of the item (the server decides, this only
 * shows or hides buttons); `featured` says which way the feature button goes.
 */
export interface ItemActions {
  edit: boolean;
  /** The submit button, labelled "send again" when the teacher asked for changes. */
  submit: boolean;
  resubmit: boolean;
  delete: boolean;
  review: boolean;
  archive: boolean;
  unarchive: boolean;
  feature: boolean;
  unfeature: boolean;
  comment: boolean;
}

export function itemActions(item: PortfolioItem): ItemActions {
  const c = item.capabilities;
  return {
    edit: !!c.canEdit,
    submit: !!c.canSubmit,
    resubmit: !!c.canSubmit && item.status === "CHANGES_REQUESTED",
    delete: !!c.canDelete,
    review: !!c.canReview,
    archive: !!c.canArchive,
    unarchive: !!c.canUnarchive,
    feature: !!c.canFeature && !item.featured,
    unfeature: !!c.canFeature && !!item.featured,
    comment: !!c.canComment,
  };
}

/** True when the item shows any control at all (a card hides its action row otherwise). */
export function hasAnyAction(item: PortfolioItem): boolean {
  return Object.values(itemActions(item)).some(Boolean);
}

export const DECISIONS: readonly PortfolioDecision[] = ["APPROVE", "REQUEST_CHANGES", "REJECT"];

export const MAX_NOTE = 500;
export const MAX_REASON = 200;

/** A decision other than approving needs a note for the student. */
export const noteRequired = (decision: PortfolioDecision): boolean => decision !== "APPROVE";

export type NoteProblem = "required" | "tooLong";

export function noteProblem(decision: PortfolioDecision, note: string): NoteProblem | null {
  const text = note.trim();
  if (noteRequired(decision) && !text) return "required";
  if (text.length > MAX_NOTE) return "tooLong";
  return null;
}

/**
 * The review request. The audience is sent only when the reviewer made it narrower while approving (the API refuses a
 * wider one); the note only when there is one.
 */
export function buildReviewBody(
  item: Pick<PortfolioItem, "visibility">,
  decision: PortfolioDecision,
  note: string,
  visibility: PortfolioVisibility,
): PortfolioReview {
  const text = note.trim();
  return {
    decision,
    note: text || undefined,
    visibility: decision === "APPROVE" && visibility !== item.visibility ? visibility : undefined,
  };
}

import type {
  PortfolioItem,
  PortfolioItemCreate,
  PortfolioItemType,
  PortfolioItemUpdate,
  PortfolioQuickCapture,
  PortfolioVisibility,
} from "./queries";

/** The limits the API enforces (the API still decides). */
export const MAX_TITLE = 120;
export const MAX_DESCRIPTION = 1000;
export const MAX_REFLECTION = 1000;
export const MAX_TEXT = 5000;
export const MAX_FILES = 10;
export const MAX_TAGS = 20;

/**
 * The item types the web builds. DRAWING needs an in-app canvas (a phone feature, M12c); AUDIO and VIDEO are not
 * available (the API refuses them). The form offers these types when the API's `capabilities.itemTypes` lists them.
 */
export const WEB_ITEM_TYPES: readonly PortfolioItemType[] = ["TEXT", "PHOTO", "SCAN", "DOCUMENT"];

export function offeredTypes(apiTypes: readonly string[] | undefined): PortfolioItemType[] {
  return WEB_ITEM_TYPES.filter((type) => !apiTypes || apiTypes.includes(type));
}

export const carriesFiles = (type: PortfolioItemType): boolean => type !== "TEXT";

const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];
const PDF = "application/pdf";

/** Which file types fit an item type (the API checks again): photos are images, a document a PDF, a scan either. */
export function allowedMimeTypes(type: PortfolioItemType): string[] {
  switch (type) {
    case "PHOTO":
    case "DRAWING":
      return IMAGE_TYPES;
    case "SCAN":
      return [...IMAGE_TYPES, PDF];
    case "DOCUMENT":
      return [PDF];
    default:
      return [];
  }
}

export interface ComposerFile {
  mediaId: string;
  kind: "IMAGE" | "DOCUMENT" | "OTHER" | "VIDEO" | "AUDIO";
}

/**
 * Everything the author has typed or chosen. It lives in memory while the form is open and is never lost when the API
 * refuses the request (the consent gate creates nothing, so the same state is simply sent again).
 */
export interface ItemFormState {
  itemType: PortfolioItemType;
  title: string;
  description: string;
  reflection: string;
  textContent: string;
  /** `yyyy-MM-dd`, empty for "today" (the API's default). */
  workDate: string;
  /** Empty for none (the API picks the only class of a student who has exactly one). */
  classId: string;
  subjectId: string;
  visibility: PortfolioVisibility;
  files: ComposerFile[];
  taggedStudentIds: string[];
  /** "No child without media consent appears in these files": only meaningful with files and no tag. */
  confirmNoUnconsented: boolean;
  /** Quick capture only: other children appear (or may appear) in the files. */
  showsOtherChildren: boolean;
}

export function initialForm(defaults: { visibility?: PortfolioVisibility; itemType?: PortfolioItemType; classId?: string } = {}): ItemFormState {
  return {
    itemType: defaults.itemType ?? "TEXT",
    title: "",
    description: "",
    reflection: "",
    textContent: "",
    workDate: "",
    classId: defaults.classId ?? "",
    subjectId: "",
    visibility: defaults.visibility ?? "GUARDIANS",
    files: [],
    taggedStudentIds: [],
    confirmNoUnconsented: false,
    showsOtherChildren: true,
  };
}

/** An existing item as form state (for editing after changes were requested, or finishing a draft). */
export function formFromItem(item: PortfolioItem): ItemFormState {
  return {
    itemType: item.itemType,
    title: item.title ?? "",
    description: item.description ?? "",
    reflection: item.reflection ?? "",
    textContent: item.textContent ?? "",
    workDate: item.workDate ?? "",
    classId: item.classId ?? "",
    subjectId: item.subjectId ?? "",
    visibility: item.visibility,
    files: item.media.map((m) => ({ mediaId: m.mediaId, kind: m.kind })),
    taggedStudentIds: (item.taggedStudents ?? []).map((t) => t.studentId),
    confirmNoUnconsented: !!item.mediaConfirmed,
    showsOtherChildren: !!item.showsOtherChildren,
  };
}

export type FormProblem =
  | "titleRequired"
  | "titleTooLong"
  | "descriptionTooLong"
  | "reflectionTooLong"
  | "textRequired"
  | "textTooLong"
  | "filesRequired"
  | "tooManyFiles"
  | "tooManyTags";

/** True when the files have no tagged child: the author may then state that nobody without consent appears. */
export function needsConfirmation(state: Pick<ItemFormState, "files" | "taggedStudentIds">): boolean {
  return state.files.length > 0 && state.taggedStudentIds.length === 0;
}

/** What is wrong before anything is sent. `complete` adds what a submission needs (the text or the files). */
export function formProblems(state: ItemFormState, complete: boolean): FormProblem[] {
  const problems: FormProblem[] = [];
  const title = state.title.trim();
  if (!title) problems.push("titleRequired");
  else if (title.length > MAX_TITLE) problems.push("titleTooLong");
  if (state.description.trim().length > MAX_DESCRIPTION) problems.push("descriptionTooLong");
  if (state.reflection.trim().length > MAX_REFLECTION) problems.push("reflectionTooLong");
  if (state.itemType === "TEXT") {
    const text = state.textContent.trim();
    if (complete && !text) problems.push("textRequired");
    if (text.length > MAX_TEXT) problems.push("textTooLong");
  } else {
    if (complete && state.files.length === 0) problems.push("filesRequired");
    if (state.files.length > MAX_FILES) problems.push("tooManyFiles");
  }
  if (state.taggedStudentIds.length > MAX_TAGS) problems.push("tooManyTags");
  return problems;
}

const trimmed = (value: string) => value.trim();
const orUndefined = (value: string) => value.trim() || undefined;

/** The confirmation is sent only with files and without tags, and only when the author ticked it. */
export function confirmationFlag(state: ItemFormState): boolean | undefined {
  return needsConfirmation(state) && state.confirmNoUnconsented ? true : undefined;
}

function shared(state: ItemFormState) {
  const files = carriesFiles(state.itemType);
  return {
    description: orUndefined(state.description),
    reflection: orUndefined(state.reflection),
    textContent: state.itemType === "TEXT" ? trimmed(state.textContent) : undefined,
    workDate: state.workDate || undefined,
    subjectId: state.subjectId || undefined,
    visibility: state.visibility,
    mediaIds: files ? state.files.map((f) => f.mediaId) : undefined,
    taggedStudentIds: files ? state.taggedStudentIds : undefined,
    confirmNoUnconsentedStudents: files ? confirmationFlag(state) : undefined,
  };
}

/** A student's new item: kept as a draft, or sent for review at once. */
export function buildCreateBody(state: ItemFormState, mode: "DRAFT" | "SUBMIT"): PortfolioItemCreate {
  return {
    itemType: state.itemType,
    title: trimmed(state.title),
    classId: state.classId || undefined,
    mode,
    ...shared(state),
  };
}

/**
 * The student's edit: what the form shows replaces the item's fields (an empty description or reflection clears it).
 * The class and subject cannot be cleared through an update (the API leaves a missing value as it is).
 */
export function buildUpdateBody(state: ItemFormState): PortfolioItemUpdate {
  const base = shared(state);
  return {
    ...base,
    title: trimmed(state.title),
    description: trimmed(state.description),
    reflection: trimmed(state.reflection),
    classId: state.classId || undefined,
    // A text item keeps no files; a file item keeps no text (the API refuses text on a file item).
    textContent: state.itemType === "TEXT" ? trimmed(state.textContent) : undefined,
    // `showsOtherChildren` follows the tags for a student (the API turns it on by itself when a child is tagged).
    showsOtherChildren: carriesFiles(state.itemType) ? state.taggedStudentIds.length > 0 : undefined,
  };
}

/** A teacher's quick capture for one student. `showsOtherChildren` is always sent (the API's own default is true). */
export function buildQuickCaptureBody(state: ItemFormState, studentId: string): PortfolioQuickCapture {
  const base = shared(state);
  const files = carriesFiles(state.itemType);
  return {
    itemType: state.itemType,
    studentId,
    title: trimmed(state.title),
    ...base,
    showsOtherChildren: files ? state.showsOtherChildren || state.taggedStudentIds.length > 0 : undefined,
  };
}

/** The state without some children (the author removes the tags of those whose consent is missing). */
export function withoutStudents(state: ItemFormState, ids: readonly string[]): ItemFormState {
  const drop = new Set(ids);
  return { ...state, taggedStudentIds: state.taggedStudentIds.filter((id) => !drop.has(id)), confirmNoUnconsented: false };
}

/** Changing the files or the tags invalidates a confirmation given for the old set. */
export function withFiles(state: ItemFormState, files: ComposerFile[]): ItemFormState {
  return { ...state, files, confirmNoUnconsented: false };
}

export function withStudents(state: ItemFormState, taggedStudentIds: string[]): ItemFormState {
  return { ...state, taggedStudentIds, confirmNoUnconsented: false };
}

/** A new type starts without files (the old ones may not fit) and without a confirmation. */
export function withType(state: ItemFormState, itemType: PortfolioItemType): ItemFormState {
  if (itemType === state.itemType) return state;
  return { ...state, itemType, files: [], taggedStudentIds: [], confirmNoUnconsented: false };
}

/**
 * The key identifying one logical submission. The same body keeps its Idempotency-Key (a retry after a network
 * failure never creates two items); a changed body gets a new one.
 */
export function submissionFingerprint(body: unknown): string {
  return JSON.stringify(body);
}

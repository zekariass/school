import type { ReasonCode } from "./types";
import { REPORT_REASONS } from "./types";

/** Role labels a case carries instead of names. The API sends a code (`STUDENT`), or a phrase ("a student") in old answers. */
export const ROLE_KEYS = ["STUDENT", "TEACHER", "STAFF", "GUARDIAN", "OTHER"] as const;
export type RoleKey = (typeof ROLE_KEYS)[number];

/** Maps what the API sent to a role key; anything unknown is OTHER ("a person"): never a guess about who it was. */
export function roleKey(role: string | null | undefined): RoleKey {
  const value = (role ?? "").trim().toUpperCase().replace(/^AN? /, "");
  return (ROLE_KEYS as readonly string[]).includes(value) ? (value as RoleKey) : "OTHER";
}

/** The kinds of content a reviewed item can be. */
export const ITEM_TYPE_KEYS = ["POST", "COMMENT", "STORY"] as const;
export type ItemTypeKey = (typeof ITEM_TYPE_KEYS)[number];

export function itemTypeKey(type: string | null | undefined): ItemTypeKey {
  return (ITEM_TYPE_KEYS as readonly string[]).includes(type ?? "") ? (type as ItemTypeKey) : "POST";
}

/** The history codes the API writes (`moderation_actions.action`); an unknown code shows as a generic "update". */
export const ACTION_KEYS = [
  "ASSIGN",
  "REASSIGN",
  "NO_ACTION",
  "HIDE",
  "REMOVE",
  "WARN_USER",
  "SUSPEND_USER",
  "ESCALATE",
  "REOPEN",
  "RESTORE",
  "LIFT_SUSPENSION",
  "UNLOCK_USER",
  "REFER_UP",
  "SET_PRIORITY",
  "HOLD_SET",
  "HOLD_LIFTED",
] as const;
export type ActionKey = (typeof ACTION_KEYS)[number];

export function actionKey(action: string | null | undefined): ActionKey | "OTHER" {
  return (ACTION_KEYS as readonly string[]).includes(action ?? "") ? (action as ActionKey) : "OTHER";
}

/** A reason code the dialog knows; anything else shows as OTHER. */
export function reasonKey(code: string | null | undefined): ReasonCode {
  return (REPORT_REASONS as readonly string[]).includes(code ?? "") ? (code as ReasonCode) : "OTHER";
}

/** Reasons of a case summary ({code: count}) as a stable, most-reported-first list. */
export function reasonCounts(reasons: Record<string, number> | undefined): { code: ReasonCode; count: number }[] {
  return Object.entries(reasons ?? {})
    .map(([code, count]) => ({ code: reasonKey(code), count }))
    .sort((a, b) => b.count - a.count || a.code.localeCompare(b.code));
}

/** HIGH and CRITICAL stand out in the queue. */
export function isUrgentPriority(priority: string | null | undefined): boolean {
  return priority === "HIGH" || priority === "CRITICAL";
}

/** The known values of an enum-like field, so an unexpected one falls back to a generic label instead of a key. */
export function knownOr<T extends string>(value: string | null | undefined, known: readonly T[], fallback: T): T {
  return (known as readonly string[]).includes(value ?? "") ? (value as T) : fallback;
}

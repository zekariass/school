import type { GroupKind } from "./types";

/** Kinds that belong to a class (the API needs `classId`). */
export function isClassBound(kind: GroupKind): boolean {
  return kind === "CLASS_STUDENTS" || kind === "CLASS_PARENTS" || kind === "STUDENT_GROUP";
}

/** Kinds whose members follow the class list: there is nobody to pick. */
export function followsRoster(kind: GroupKind): boolean {
  return kind === "CLASS_STUDENTS" || kind === "CLASS_PARENTS";
}

/** Kinds with a title the creator writes (class conversations are named by the class). */
export function needsTitle(kind: GroupKind): boolean {
  return kind === "STAFF" || kind === "STUDENT_GROUP" || kind === "SCHOOL_STAFF";
}

/** The fewest students a conversation with students may have (`messaging.group_too_small`): with one it would be a private chat with a child. */
export const MIN_STUDENTS = 2;

export type GroupProblem = "kind" | "class" | "title" | "members" | "tooFewStudents";

/**
 * What is still missing before a group can be created. The API checks the same and more (eligibility, roles); this only keeps the
 * button off, and shows the "at least two students" rule, which exists to keep adults out of private chats with a single child.
 */
export function groupFormProblem(form: { kind: GroupKind | ""; classId: string; title: string; memberIds: readonly string[] }): GroupProblem | null {
  if (!form.kind) return "kind";
  if (isClassBound(form.kind) && !form.classId) return "class";
  if (needsTitle(form.kind) && !form.title.trim()) return "title";
  if (form.kind === "STUDENT_GROUP") return form.memberIds.length < MIN_STUDENTS ? "tooFewStudents" : null;
  if (!followsRoster(form.kind) && form.memberIds.length === 0) return "members";
  return null;
}

/** The request body for creating a group (member ids only for kinds that have chosen members). */
export function groupRequest(form: { kind: GroupKind; classId: string; title: string; memberIds: readonly string[]; postingPolicy: string }) {
  return {
    kind: form.kind,
    classId: isClassBound(form.kind) ? form.classId : undefined,
    title: form.title.trim() || undefined,
    memberUserIds: followsRoster(form.kind) ? undefined : [...form.memberIds],
    postingPolicy: form.postingPolicy || undefined,
  };
}

/** Proposed student groups that wait for a decision, newest first as the API lists them. */
export function pendingApprovals<T extends { status: string }>(groups: readonly T[]): T[] {
  return groups.filter((g) => g.status === "PENDING_APPROVAL");
}

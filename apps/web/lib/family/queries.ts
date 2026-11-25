import type { components } from "@school/api-client";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";

type S = components["schemas"];

export type MyChildren = S["MyChildren"];
export type MyChild = S["Child"];
export type PendingLink = S["PendingLink"];
export type MyConsents = S["MyConsents"];
export type ChildConsents = S["ChildConsents"];
export type ConsentItem = S["ConsentItem"];

/** Query keys of the guardian screens. Children's data: kept in memory only (never persisted). */
export const familyKeys = {
  all: ["family"] as const,
  children: ["family", "children"] as const,
  consents: ["family", "consents"] as const,
};

export const fetchMyChildren = async () => unwrap(await getBrowserApi().GET("/api/v1/me/children"));

export const fetchMyConsents = async () => unwrap(await getBrowserApi().GET("/api/v1/me/consents"));

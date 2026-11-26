"use client";

import { UserRoundIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useCallback } from "react";
import { initialsOf, personName, contactName, type PersonName } from "@/lib/messaging/participants";
import type { ContactLabel, ConversationSummary, Participant } from "@/lib/messaging/types";
import { cn } from "@/lib/utils";

/** Words for a person: a name, or the guardian label the API computed for this viewer. */
export function usePersonText() {
  const t = useTranslations("school.messaging.person");
  const tRel = useTranslations("school.messaging.relationship");
  const text = useCallback(
    (name: PersonName): string => {
      switch (name.kind) {
        case "name":
          return name.name;
        case "guardianOf":
          return t("guardianOf", { student: name.student, relationship: tRel(safeRelation(name.relationship)) });
        case "guardianRelation":
          return t("yourGuardian", { relationship: tRel(safeRelation(name.relationship)) });
        case "guardian":
          return t("parent");
        default:
          return t("unknown");
      }
    },
    [t, tRel],
  );
  return {
    text,
    participant: useCallback((p: Participant | undefined) => text(personName(p)), [text]),
    contact: useCallback((label: ContactLabel | undefined) => text(contactName(label)), [text]),
  };
}

type Relation = "MOTHER" | "FATHER" | "GRANDPARENT" | "SIBLING" | "AUNT_UNCLE" | "LEGAL_GUARDIAN" | "OTHER";
const RELATIONS: ReadonlySet<string> = new Set(["MOTHER", "FATHER", "GRANDPARENT", "SIBLING", "AUNT_UNCLE", "LEGAL_GUARDIAN", "OTHER"]);
function safeRelation(value: string): Relation {
  return RELATIONS.has(value) ? (value as Relation) : "OTHER";
}

/** The title of a conversation: its own title, the other person of a direct one, or its kind. */
export function useConversationTitle() {
  const t = useTranslations("school.messaging.kind");
  const { participant } = usePersonText();
  return useCallback(
    (c: Pick<ConversationSummary, "title" | "counterpart" | "type" | "groupKind" | "directKind">): string => {
      if (c.title?.trim()) return c.title;
      if (c.type === "DIRECT") return c.counterpart ? participant(c.counterpart) : t("direct");
      return t(c.groupKind ?? "GROUP");
    },
    [t, participant],
  );
}

/** A round badge with initials (or a generic mark for labels), colours from the design tokens. */
export function PersonBadge({ name, className }: { name: PersonName; className?: string }) {
  const initials = initialsOf(name);
  return (
    <span aria-hidden className={cn("grid size-10 shrink-0 place-items-center rounded-full bg-primary/10 text-sm font-bold text-primary", className)}>
      {initials || <UserRoundIcon className="size-5" />}
    </span>
  );
}

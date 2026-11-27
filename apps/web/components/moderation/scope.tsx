"use client";

import { createContext, useContext, useMemo } from "react";
import { useConsole } from "@/components/console/console-context";
import { useSchool } from "@/components/school/school-context";
import type { Viewer } from "@/lib/moderation/access";

/**
 * Where the moderation and safeguarding screens run: a school's workspace (`/s/{slug}/…`) or the platform console
 * (`/platform/…`). The screens are the same; this says which queue and which incident list they read, where their
 * links go and who the viewer is (permissions from `/me`: they only choose which buttons to show).
 */
export interface ModerationScope {
  kind: "school" | "platform";
  /** The school whose queue and incidents these are; null on the platform. */
  schoolId: string | null;
  /** The school's name, for the page's subtitle; null on the platform. */
  schoolName: string | null;
  /** Locale-less paths for next-intl's `Link`. */
  queuePath: string;
  incidentsPath: string;
  /** The page of the audited messaging review, where a reported message is read (school workspaces only). */
  messageReviewPath: string | null;
  viewer: Viewer;
}

const ScopeContext = createContext<ModerationScope | null>(null);

export function useModerationScope(): ModerationScope {
  const value = useContext(ScopeContext);
  if (!value) throw new Error("useModerationScope needs a scope provider");
  return value;
}

/** Inside a school workspace (needs the SchoolProvider of the workspace layout). */
export function SchoolModerationScope({ children }: { children: React.ReactNode }) {
  const { school, userId, permissions } = useSchool();
  const value = useMemo<ModerationScope>(() => {
    const base = `/s/${encodeURIComponent(school.slug)}`;
    return {
      kind: "school",
      schoolId: school.id,
      schoolName: school.name,
      queuePath: `${base}/moderation`,
      incidentsPath: `${base}/safeguarding`,
      messageReviewPath: `${base}/messages/review`,
      viewer: { userId, permissions },
    };
  }, [school.id, school.name, school.slug, userId, permissions]);
  return <ScopeContext.Provider value={value}>{children}</ScopeContext.Provider>;
}

/** Inside the platform console (needs the ConsoleProvider of the platform layout). */
export function PlatformModerationScope({ children }: { children: React.ReactNode }) {
  const { userId, permissions } = useConsole();
  const value = useMemo<ModerationScope>(
    () => ({
      kind: "platform",
      schoolId: null,
      schoolName: null,
      queuePath: "/platform/moderation",
      incidentsPath: "/platform/safeguarding",
      messageReviewPath: null,
      viewer: { userId, permissions },
    }),
    [userId, permissions],
  );
  return <ScopeContext.Provider value={value}>{children}</ScopeContext.Provider>;
}

/** For tests and stories: a ready-made scope. */
export function ModerationScopeProvider({ scope, children }: { scope: ModerationScope; children: React.ReactNode }) {
  return <ScopeContext.Provider value={scope}>{children}</ScopeContext.Provider>;
}

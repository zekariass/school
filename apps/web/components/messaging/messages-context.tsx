"use client";

import { createContext, useContext, useMemo } from "react";
import type { MessagingViewer } from "@/lib/messaging/viewer";

/**
 * Where the messaging screens live: `/messages` for everybody, or `/s/{slug}/messages` inside a school's workspace (the
 * teachers' and administrators' shell). The same screens run in both; links and the school filter come from here, and so does
 * what kind of viewer this is (from `/me`: only for choosing which links exist, the API authorises every call).
 */
export interface MessagesPlace {
  /** The route prefix, without locale: "/messages" or "/s/sunrise/messages". */
  base: string;
  /** In the workspace: the school the inbox is limited to. */
  school: { id: string; slug: string; name: string } | null;
  viewer: MessagingViewer;
}

const DEFAULT_VIEWER: MessagingViewer = { isStudent: false, canStart: true, schools: [], showGroups: false };
const MessagesContext = createContext<MessagesPlace>({ base: "/messages", school: null, viewer: DEFAULT_VIEWER });

export function MessagesPlaceProvider({ school, viewer, children }: { school: MessagesPlace["school"]; viewer: MessagingViewer; children: React.ReactNode }) {
  const value = useMemo<MessagesPlace>(() => ({ base: school ? `/s/${encodeURIComponent(school.slug)}/messages` : "/messages", school, viewer }), [school, viewer]);
  return <MessagesContext.Provider value={value}>{children}</MessagesContext.Provider>;
}

export function useMessagesPlace(): MessagesPlace {
  return useContext(MessagesContext);
}

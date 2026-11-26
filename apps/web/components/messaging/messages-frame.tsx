"use client";

import { useMemo } from "react";
import { usePathname } from "@/i18n/navigation";
import type { MessagingViewer } from "@/lib/messaging/viewer";
import { MessagesPlaceProvider } from "./messages-context";
import { MessagesNav } from "./messages-nav";
import { useSchool } from "@/components/school/school-context";
import { SP } from "@/lib/school/access";

/** The section tabs and the place (base route, viewer) for the screens under `/messages`. */
export function MessagesFrame({ viewer, children }: { viewer: MessagingViewer; children: React.ReactNode }) {
  return (
    <MessagesPlaceProvider school={null} viewer={viewer}>
      <MessagesNav showGroups={viewer.showGroups} showScheduled={!viewer.isStudent} />
      {children}
    </MessagesPlaceProvider>
  );
}

/** The same inside a school's workspace: the inbox is that school's, links stay under `/s/{slug}/messages`. The review page has no tabs. */
export function WorkspaceMessagesFrame({ children }: { children: React.ReactNode }) {
  const { school, permissions, isTeacher, isStaff } = useSchool();
  const pathname = usePathname();
  const viewer = useMemo<MessagingViewer>(
    () => ({
      isStudent: false,
      canStart: true,
      schools: [{ id: school.id, name: school.name }],
      showGroups: isTeacher || isStaff || permissions.has(SP.messagingModerate),
    }),
    [school.id, school.name, isTeacher, isStaff, permissions],
  );
  const review = pathname.endsWith("/messages/review");
  return (
    <MessagesPlaceProvider school={school} viewer={viewer}>
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-6">
        {review ? null : <MessagesNav showGroups={viewer.showGroups} />}
        {children}
      </div>
    </MessagesPlaceProvider>
  );
}

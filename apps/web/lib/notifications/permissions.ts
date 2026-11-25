import type { components } from "@school/api-client";

type Me = components["schemas"]["MeResponse"];

/** The API's test-notification endpoint is for holders of this permission at platform scope. */
export const TEST_NOTIFICATION_PERMISSION = "platform.settings.manage";

/** True for platform administrators (the API still checks the call). */
export function canSendTestNotification(me: Pick<Me, "scopes">): boolean {
  return me.scopes.some((s) => s.scope.type === "PLATFORM" && s.permissions.includes(TEST_NOTIFICATION_PERMISSION));
}

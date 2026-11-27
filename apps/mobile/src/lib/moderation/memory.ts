import type { QueryClient } from '@tanstack/react-query';

import { MODERATION_ROOT } from './keys';

/**
 * Forgets everything reporting holds in memory: the cached safety text and any request still in flight. Called at sign-out and
 * when the session ends (next to `clearMessagingMemory`). A report in progress lives in its sheet's state, which goes with the
 * screens; nothing here is ever on disk.
 */
export function clearModerationMemory(client: QueryClient): void {
  void client.cancelQueries({ queryKey: [MODERATION_ROOT] });
  client.removeQueries({ queryKey: [MODERATION_ROOT] });
}

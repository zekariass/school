package com.nemaz.school.messaging;

import java.util.UUID;

/**
 * A message became visible to its conversation: sent now, or released by the schedule (published in that transaction;
 * delivered at least once to listeners). Ids only: the text never travels in an event.
 */
public record MessageSent(UUID messageId, UUID conversationId, UUID schoolId, UUID senderId) {
}

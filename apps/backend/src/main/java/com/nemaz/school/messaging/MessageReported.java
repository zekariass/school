package com.nemaz.school.messaging;

import java.util.UUID;

/**
 * A member reported a message (published in the reporting transaction, for the safeguarding workflow of M13). Carries ids and
 * the reason code only: never the text, the note, or a name.
 */
public record MessageReported(UUID reportId, UUID messageId, UUID conversationId, UUID schoolId, UUID reporterId,
		String reason) {
}

package com.nemaz.school.feed;

import java.util.UUID;

/**
 * Somebody reported a post: the hook of the moderation queue (M13: the listener in this module hands it to the moderation
 * module, idempotently, keyed by {@code reportId}). The report is also written to the audit log ({@code feed.post.report}).
 * Carries the reporter's reason code only, never post text.
 */
public record ContentReported(UUID reportId, String contentType, UUID contentId, UUID schoolId, UUID reporterId,
		String reasonCode) {
}

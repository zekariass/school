package com.nemaz.school.feed;

import java.util.UUID;

/**
 * Somebody reported a post: the hook for the moderation queue of M13 (this module builds no moderation). The report is
 * also written to the audit log ({@code feed.post.report}) so that nothing is lost while no listener exists. Carries the
 * reporter's reason code only, never post text.
 */
public record ContentReported(String contentType, UUID contentId, UUID schoolId, UUID reporterId, String reasonCode) {
}

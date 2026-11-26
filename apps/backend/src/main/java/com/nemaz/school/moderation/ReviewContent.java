package com.nemaz.school.moderation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a moderator may read of a reported target: the target and its immediate context, through the owner's reviewer
 * path. {@code kind} is TEXT (the items are here) or MESSAGING_REVIEW (a message: read it through the audited,
 * reason-bound messaging review of {@code reviewConversationId}, never here). Returned by {@link ModerationTarget#read} and
 * handed to the client; nothing of it is stored by moderation, logged or audited.
 */
@Schema(name = "ModerationReviewContent")
public record ReviewContent(String kind, UUID reviewConversationId, List<Item> items) {

	public ReviewContent {
		items = List.copyOf(items);
	}

	public static ReviewContent pointer(UUID conversationId) {
		return new ReviewContent("MESSAGING_REVIEW", conversationId, List.of());
	}

	public static ReviewContent text(List<Item> items) {
		return new ReviewContent("TEXT", null, items);
	}

	/** One piece of content: {@code highlighted} marks the reported one among its context; {@code mediaIds} are ids only. */
	@Schema(name = "ModerationReviewItem")
	public record Item(String type, UUID id, UUID authorId, String authorRole, String text, String status,
			List<UUID> mediaIds, Instant createdAt, boolean highlighted) {

		public Item {
			mediaIds = List.copyOf(mediaIds);
		}

	}

}

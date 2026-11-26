package com.nemaz.school.moderation;

import java.util.UUID;

/**
 * What the moderation module needs to know about a target, as the owning module describes it: the tenant ({@code schoolId},
 * null for a platform-scope target), the person whose content or conduct it is ({@code subjectUserId}, who must be kept out
 * of their own case), whether it belongs to a safeguarded adult-student conversation, and an owner-defined
 * {@code contextId} (the conversation of a message, the post of a comment) that the review path needs. Ids and flags only.
 */
public record TargetRef(String type, UUID id, UUID schoolId, UUID subjectUserId, boolean safeguardedConversation,
		UUID contextId) {

	public TargetRef(String type, UUID id, UUID schoolId, UUID subjectUserId) {
		this(type, id, schoolId, subjectUserId, false, null);
	}

}

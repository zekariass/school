package com.nemaz.school.moderation;

import java.util.Set;
import java.util.UUID;

/**
 * Implemented by the modules whose content moderation may take down (docs/Safeguarding_Rules.md S2.4, principle 4): the
 * original is NEVER destroyed, hiding and removing only make it unavailable to others. Every method is idempotent and runs
 * in the caller's transaction. The owner uses its own existing hide mechanism (post and story status HIDDEN, comment
 * HIDDEN, message HIDDEN) and records the moderation state in {@code moderation_case_id} / {@code moderation_hidden_at}, so
 * that its own un-hide and delete paths cannot undo or erase a case.
 */
public interface ModerationTarget {

	Set<String> targetTypes();

	/**
	 * Marks the content as the subject of an open case (evidence hold): from now on a delete by its author keeps the files
	 * and an expiry job keeps the photo. The content stays visible; the author is not told.
	 */
	void hold(String targetType, UUID targetId, UUID caseId);

	/**
	 * Makes the content unavailable to others. {@code removal} true is REMOVE (the case cannot restore it); false is HIDE
	 * (reversible). Content that is already unavailable stays so.
	 */
	void takeDown(String targetType, UUID targetId, UUID caseId, UUID actorId, boolean removal);

	/** Undoes a HIDE of this case; no-op when it was not hidden by it. */
	void restore(String targetType, UUID targetId, UUID caseId, UUID actorId);

	/** The case-bound read of the target and its immediate context (see {@link ReviewContent}). */
	ReviewContent read(String targetType, UUID targetId);

}

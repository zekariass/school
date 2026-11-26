package com.nemaz.school.portfolio;

import java.util.UUID;

/**
 * An item was reviewed (APPROVED, CHANGES_REQUESTED or REJECTED) or quick-captured by a teacher (APPROVED, {@code captured}).
 * Published in that transaction; ids and the decision only, never the item's text or the reviewer's note.
 */
public record PortfolioItemReviewed(UUID itemId, UUID schoolId, UUID studentId, UUID approvalId, String toStatus,
		boolean captured) {
}

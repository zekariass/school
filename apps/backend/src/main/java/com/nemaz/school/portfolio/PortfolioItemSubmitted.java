package com.nemaz.school.portfolio;

import java.util.UUID;

/** An item was submitted (or resubmitted) for review. Published in that transaction; ids only, never the item's text. */
public record PortfolioItemSubmitted(UUID itemId, UUID schoolId, UUID approvalId) {
}

package com.nemaz.school.portfolio;

import java.util.UUID;

/** A comment was written on an item. Published in that transaction; ids only, never the comment. */
public record PortfolioCommentPosted(UUID commentId, UUID itemId, UUID schoolId, UUID authorId) {
}

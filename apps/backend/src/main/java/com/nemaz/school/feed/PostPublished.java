package com.nemaz.school.feed;

import java.util.UUID;

/**
 * A post became visible to families (published now, by the scheduler, or by the approval of a contribution).
 * Published in the publishing transaction; {@code classId} is null for a school-wide post. Carries no text.
 */
public record PostPublished(UUID postId, UUID schoolId, UUID classId, UUID authorId, boolean contribution) {
}

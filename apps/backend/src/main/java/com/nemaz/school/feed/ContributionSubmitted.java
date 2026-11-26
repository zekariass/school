package com.nemaz.school.feed;

import java.util.UUID;

/** A guardian or student contribution waits for approval in the class's queue. Carries no text. */
public record ContributionSubmitted(UUID postId, UUID schoolId, UUID classId, UUID contributorId) {
}

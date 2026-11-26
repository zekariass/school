package com.nemaz.school.feed;

import java.util.UUID;

/** A contribution was approved (and published) or rejected. The rejection reason is not carried. */
public record ContributionDecided(UUID postId, UUID schoolId, UUID classId, UUID contributorId, UUID decidedBy,
		boolean approved) {
}

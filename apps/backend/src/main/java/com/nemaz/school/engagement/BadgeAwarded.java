package com.nemaz.school.engagement;

import java.util.UUID;

/** A student earned a badge (published in the granting transaction); {@code awardedBy} is null for the rule engine. */
public record BadgeAwarded(UUID studentBadgeId, UUID badgeId, String badgeCode, UUID schoolId, UUID studentId,
		UUID awardedBy) {
}

package com.nemaz.school.moderation;

import java.util.UUID;

/**
 * A moderation case was decided (published in the deciding transaction). Ids and the decision code only: never a reason, a
 * reporter, content or a name. For later modules that must react (a portfolio item that was removed). Delivered at least once.
 */
public record CaseDecided(UUID caseId, UUID schoolId, String targetType, UUID targetId, String decision) {
}

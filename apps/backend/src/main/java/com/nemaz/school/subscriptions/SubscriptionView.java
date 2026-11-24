package com.nemaz.school.subscriptions;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionView(UUID id, UUID schoolId, String planCode, SubscriptionStatus status,
		Instant currentPeriodStart, Instant currentPeriodEnd, Instant trialEndsAt, Instant createdAt) {
}

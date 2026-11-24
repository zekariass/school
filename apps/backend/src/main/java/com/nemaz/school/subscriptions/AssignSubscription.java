package com.nemaz.school.subscriptions;

import java.time.Instant;

/**
 * Manual assignment of a plan (P1). {@code status} defaults to ACTIVE; for TRIALING,
 * {@code currentPeriodEnd} is the end of the trial. A null end means open-ended.
 */
public record AssignSubscription(String planCode, SubscriptionStatus status, Instant currentPeriodEnd) {
}

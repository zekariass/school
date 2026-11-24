package com.nemaz.school.subscriptions;

import java.util.UUID;

/** A school's plan changed (published in the assigning transaction). */
public record SubscriptionAssigned(UUID subscriptionId, UUID schoolId, String planCode, SubscriptionStatus status) {
}

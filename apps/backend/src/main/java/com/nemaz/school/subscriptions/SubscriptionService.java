package com.nemaz.school.subscriptions;

import java.util.UUID;

/** Public API of the subscriptions module. */
public interface SubscriptionService {

	/** Plan code every school falls back to when it has no live subscription. */
	String FREE_PLAN_CODE = "SCHOOL_FREE";

	/** The school's current entitlements (SCHOOL_FREE when it has no live subscription). */
	SchoolEntitlements entitlementsFor(UUID schoolId);

	/**
	 * Assigns a plan to a school, ending its current subscription. The caller has already
	 * checked that the school exists. Publishes {@link SubscriptionAssigned}.
	 */
	SubscriptionView assignToSchool(UUID schoolId, AssignSubscription command, UUID actorId);

}

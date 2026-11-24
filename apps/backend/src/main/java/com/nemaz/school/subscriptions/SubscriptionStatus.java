package com.nemaz.school.subscriptions;

/** Lifecycle of a subscription. Only TRIALING and ACTIVE subscriptions entitle features. */
public enum SubscriptionStatus {

	TRIALING, ACTIVE, PAST_DUE, CANCELLED, EXPIRED;

	public boolean entitles() {
		return this == TRIALING || this == ACTIVE;
	}

}

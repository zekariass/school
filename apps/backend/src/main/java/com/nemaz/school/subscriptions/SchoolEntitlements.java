package com.nemaz.school.subscriptions;

import java.util.Map;

/**
 * Entitlements a school currently has. A school without a live subscription gets the
 * SCHOOL_FREE plan ({@code freeFallback}). Feature keys the plan does not list are not
 * entitled.
 */
public record SchoolEntitlements(String planCode, boolean freeFallback, Map<String, Entitlement> byFeature) {

	public SchoolEntitlements {
		byFeature = Map.copyOf(byFeature);
	}

	public Entitlement of(String featureKey) {
		return this.byFeature.getOrDefault(featureKey, Entitlement.NONE);
	}

}

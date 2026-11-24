package com.nemaz.school.subscriptions;

/** What a plan gives for one feature key: on/off and an optional numeric limit (null = unlimited). */
public record Entitlement(boolean enabled, Long limit) {

	public static final Entitlement NONE = new Entitlement(false, null);

}

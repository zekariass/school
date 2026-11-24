package com.nemaz.school.common.security;

/**
 * Permission names used with {@code @PreAuthorize("hasAuthority(...)")}. Until the
 * authorization module (M1b) maps roles to permissions, authorities carry these names
 * directly; keeping them in one place makes that switch mechanical. Format:
 * {@code <area>.<resource>.<action>}.
 */
public final class Permissions {

	public static final String PLATFORM_ORGANIZATIONS_MANAGE = "platform.organizations.manage";

	public static final String PLATFORM_SCHOOLS_MANAGE = "platform.schools.manage";

	public static final String PLATFORM_SUBSCRIPTIONS_MANAGE = "platform.subscriptions.manage";

	public static final String PLATFORM_FEATURES_MANAGE = "platform.features.manage";

	public static final String PLATFORM_SETTINGS_MANAGE = "platform.settings.manage";

	/** SpEL for {@code @PreAuthorize}: constant expressions, so usable in annotations. */
	public static final String CAN_MANAGE_ORGANIZATIONS = "hasAuthority('" + PLATFORM_ORGANIZATIONS_MANAGE + "')";

	public static final String CAN_MANAGE_SCHOOLS = "hasAuthority('" + PLATFORM_SCHOOLS_MANAGE + "')";

	public static final String CAN_MANAGE_SUBSCRIPTIONS = "hasAuthority('" + PLATFORM_SUBSCRIPTIONS_MANAGE + "')";

	public static final String CAN_MANAGE_FEATURES = "hasAuthority('" + PLATFORM_FEATURES_MANAGE + "')";

	public static final String CAN_MANAGE_SETTINGS = "hasAuthority('" + PLATFORM_SETTINGS_MANAGE + "')";

	/** Viewing a school's resolved configuration: anyone who can change part of it. */
	public static final String CAN_VIEW_CONFIGURATION = "hasAnyAuthority('" + PLATFORM_SCHOOLS_MANAGE + "','"
			+ PLATFORM_FEATURES_MANAGE + "','" + PLATFORM_SETTINGS_MANAGE + "')";

	private Permissions() {
	}

}

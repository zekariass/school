package com.nemaz.school.common.security;

import java.util.List;

/**
 * Permission codes: the single source of truth in code. The database seed
 * ({@code R__40_roles_permissions.sql}) declares the same codes and maps them to roles;
 * {@code RolesAndPermissionsSeedTest} keeps the two in step. Format:
 * {@code <area>.<resource>.<action>} or {@code <resource>.<action>}.
 *
 * <p>
 * A permission is held <em>at a scope</em> (platform, country, organization, school or
 * campus) through a role assignment; checks go through {@link AccessControl} or the
 * {@code @authz} bean in {@code @PreAuthorize}. Permissions held at platform scope are also
 * exposed as Spring Security authorities, so {@code hasAuthority(...)} means "held
 * platform-wide".
 */
public final class Permissions {

	public static final String PLATFORM_ORGANIZATIONS_MANAGE = "platform.organizations.manage";

	public static final String PLATFORM_SCHOOLS_MANAGE = "platform.schools.manage";

	public static final String PLATFORM_SUBSCRIPTIONS_MANAGE = "platform.subscriptions.manage";

	public static final String PLATFORM_FEATURES_MANAGE = "platform.features.manage";

	public static final String PLATFORM_SETTINGS_MANAGE = "platform.settings.manage";

	public static final String PLATFORM_ROLES_MANAGE = "platform.roles.manage";

	public static final String SCHOOL_READ = "school.read";

	public static final String SCHOOL_MANAGE = "school.manage";

	public static final String SCHOOL_SETTINGS_MANAGE = "school.settings.manage";

	public static final String AUDIT_READ = "audit.read";

	/** Every permission code, in seed order. */
	public static final List<String> ALL = List.of(PLATFORM_ORGANIZATIONS_MANAGE, PLATFORM_SCHOOLS_MANAGE,
			PLATFORM_SUBSCRIPTIONS_MANAGE, PLATFORM_FEATURES_MANAGE, PLATFORM_SETTINGS_MANAGE, PLATFORM_ROLES_MANAGE,
			SCHOOL_READ, SCHOOL_MANAGE, SCHOOL_SETTINGS_MANAGE, AUDIT_READ);

	private Permissions() {
	}

}

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

	/** Academic years, periods, grade levels and subjects of a school. */
	public static final String SCHOOL_ACADEMIC_MANAGE = "school.academic.manage";

	public static final String SCHOOL_STAFF_MANAGE = "school.staff.manage";

	public static final String SCHOOL_STUDENTS_MANAGE = "school.students.manage";

	/** Roster imports of a school: upload, check and commit files of students, staff, classes, subjects and grade levels. */
	public static final String SCHOOL_IMPORT_MANAGE = "school.import.manage";

	/** Every class of the covered school or campus (rosters, teachers, login codes). */
	public static final String CLASS_MANAGE = "class.manage";

	/**
	 * Guardians of the school's students: invite, verify, change permission flags and custody restrictions, revoke,
	 * record paper consent (M5).
	 */
	public static final String SCHOOL_GUARDIANS_MANAGE = "school.guardians.manage";

	/** The school's own policy texts (account participation, media publication) (M5). */
	public static final String SCHOOL_POLICIES_MANAGE = "school.policies.manage";

	/**
	 * A school's point categories and badge definitions, school-wide awards, reversing any point of the school and the
	 * school's activity templates (M6).
	 */
	public static final String SCHOOL_POINTS_MANAGE = "school.points.manage";

	/** The school-wide points reports (CSV export included) and any student's points of the school (M6). */
	public static final String SCHOOL_POINTS_READ = "school.points.read";

	/**
	 * Attendance of the whole school: take or correct any class's roll, edit a locked roll with an audited reason,
	 * decide on absence reports, read the absence-report notes (M7).
	 */
	public static final String SCHOOL_ATTENDANCE_MANAGE = "school.attendance.manage";

	/** The school-wide attendance dashboard, summary reports (CSV export included) and any student's attendance (M7). */
	public static final String SCHOOL_ATTENDANCE_READ = "school.attendance.read";

	/** Every permission code, in seed order. */
	public static final List<String> ALL = List.of(PLATFORM_ORGANIZATIONS_MANAGE, PLATFORM_SCHOOLS_MANAGE,
			PLATFORM_SUBSCRIPTIONS_MANAGE, PLATFORM_FEATURES_MANAGE, PLATFORM_SETTINGS_MANAGE, PLATFORM_ROLES_MANAGE,
			SCHOOL_READ, SCHOOL_MANAGE, SCHOOL_SETTINGS_MANAGE, AUDIT_READ, SCHOOL_ACADEMIC_MANAGE, SCHOOL_STAFF_MANAGE,
			SCHOOL_STUDENTS_MANAGE, CLASS_MANAGE, SCHOOL_IMPORT_MANAGE, SCHOOL_GUARDIANS_MANAGE, SCHOOL_POLICIES_MANAGE,
			SCHOOL_POINTS_MANAGE, SCHOOL_POINTS_READ, SCHOOL_ATTENDANCE_MANAGE, SCHOOL_ATTENDANCE_READ);

	private Permissions() {
	}

}

package com.nemaz.school.academic;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;

/**
 * Who may do what in a class: the reusable rule for attendance, points, the feed and messaging
 * (Database_Design §5, "can user U see class C content"). Deny by default.
 * <ul>
 * <li><b>View:</b> a student who is ACTIVE in {@code class_students}; a teacher who is ACTIVE in
 * {@code class_teachers} (any role); an administrator holding {@code class.manage} at a scope covering the
 * class's school or campus; and whatever registered {@link ClassAccessRule}s add (guardians, M5).</li>
 * <li><b>Teach:</b> an ACTIVE class teacher or a manager.</li>
 * <li><b>Manage:</b> {@code class.manage} covering the class: school-wide for a school administrator,
 * the campus for a campus administrator (a class without campus needs a school-wide grant).</li>
 * </ul>
 * FORMER and REMOVED members have no access here (history access is a content-level setting,
 * {@code classes.former_member_history_access}, applied by the module that owns the content). Subject
 * teachers see the whole class; narrowing a gradebook to their subject is the gradebook's business.
 *
 * <p>
 * List endpoints must not filter in Java: put {@link #visibleClasses} into the query's WHERE clause.
 */
public interface ClassAccess {

	boolean canView(CurrentUser user, UUID classId);

	boolean canTeach(CurrentUser user, UUID classId);

	boolean canManage(CurrentUser user, UUID classId);

	/** ACTIVE LEAD teacher of the class, or a manager. */
	boolean canLead(CurrentUser user, UUID classId);

	/** The user's own ACTIVE membership of the class as student or teacher (teacher wins), empty for none. */
	Optional<ClassMembershipView> membershipOf(CurrentUser user, UUID classId);

	/** Ids of every class the user may view (for small result sets; prefer {@link #visibleClasses} in queries). */
	Set<UUID> accessibleClassIds(CurrentUser user);

	/**
	 * A boolean SQL expression, true for the rows of {@code classes} (aliased {@code classAlias}) that the user may
	 * view, with named parameters (all prefixed {@code ca_}). Embed it in a query with
	 * {@code NamedParameterJdbcTemplate} or {@code JdbcClient}.
	 */
	SqlFragment visibleClasses(CurrentUser user, String classAlias);

	/** The class's school, campus and status regardless of who asks (callers authorize); empty when unknown. */
	Optional<ClassRef> find(UUID classId);

	record ClassRef(UUID id, UUID schoolId, UUID campusId, UUID academicYearId, UUID gradeLevelId, String name,
			boolean archived) {
	}

}

package com.nemaz.school.calendar.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.calendar.application.CalendarModels.EventType;
import com.nemaz.school.calendar.application.EventRows.EventRow;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.guardians.GuardianAccess;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The access rules of the calendar, in one place so that every read (the event, the union, the sources of later
 * milestones that reuse it) and every write answers the same way. The 404-or-403 rule: a school the caller cannot see is 404
 * {@code school.not_found}; an event they may not see is 404 {@code event.not_found}; members who lack the right get 403.
 *
 * <p>
 * <b>Who sees which native event</b> (deny by default, decided in SQL, {@link #visible}):
 * <ul>
 * <li><b>SCHOOL:</b> ACTIVE staff, ACTIVE students and guardians (ACTIVE link, not custody-restricted) of an ACTIVE
 * student of the school; holders of {@code school.read} on it.</li>
 * <li><b>CAMPUS:</b> the same, narrowed to the campus: its staff (and staff without a campus), the students whose campus
 * or class campus it is and their guardians; {@code school.read} holders see all campuses.</li>
 * <li><b>GRADE_LEVEL:</b> the teachers and students of the grade's ACTIVE classes and the guardians of those students;
 * {@code school.read} holders.</li>
 * <li><b>CLASS:</b> whoever {@link ClassAccess#visibleClasses} lets see the class (its teachers and students, managers, and
 * guardians with {@code can_view_academics} of its students). Former members see nothing.</li>
 * <li><b>INVITEES</b> (parent meetings): the organiser, and the invited guardians <em>while their link still is ACTIVE, not
 * custody-restricted and allows academics</em>, checked at every read. Nobody else, administrators included.</li>
 * <li><b>PERSONAL:</b> the owner.</li>
 * </ul>
 * With a child filter (guardians only) everything narrows to that child's school, campus, grade, classes and meetings.
 */
@Component
@Transactional(readOnly = true)
public class CalendarAccess {

	/** What the person belongs to, as a viewer: schools, campuses, grades and (for a child filter) classes. */
	record Scope(Set<UUID> memberSchools, Set<UUID> allCampusSchools, Set<UUID> campuses, Set<UUID> grades,
			Set<UUID> childClasses) {
	}

	/**
	 * The resolved audience of one request: the schools to look in (visible, calendar enabled), those among them where the
	 * caller is an administrator ({@code school.read}), and the viewer scope.
	 */
	record Target(UUID userId, UUID studentId, Set<UUID> schools, Set<UUID> adminSchools, Scope scope) {
	}

	record Pred(String sql, Map<String, Object> params) {
	}

	private final SchoolGate gate;

	private final AccessControl access;

	private final ClassAccess classes;

	private final GuardianAccess guardians;

	private final CalendarSettings settings;

	private final JdbcClient jdbc;

	CalendarAccess(SchoolGate gate, AccessControl access, ClassAccess classes, GuardianAccess guardians,
			CalendarSettings settings, JdbcClient jdbc) {
		this.gate = gate;
		this.access = access;
		this.classes = classes;
		this.guardians = guardians;
		this.settings = settings;
		this.jdbc = jdbc;
	}

	// --- the school ---------------------------------------------------------------------------

	public boolean manager(CurrentUser user, UUID schoolId) {
		return this.access.can(user, Permissions.SCHOOL_CALENDAR_MANAGE, AccessScope.school(schoolId));
	}

	/**
	 * For acting in a school's calendar: 404 unless the caller can see the school (member, administrator, or guardian of a
	 * student of it), 403 {@code calendar.feature_disabled} when the school switched it off.
	 */
	void requireSchool(CurrentUser user, UUID schoolId) {
		if (this.gate.canSee(user, schoolId)) {
			this.gate.requireVisible(user, schoolId);
		}
		else if (!profile(user, null).memberSchools().contains(schoolId)) {
			throw ApiException.notFound("school.not_found");
		}
		if (!this.settings.enabled(schoolId)) {
			throw ApiException.forbidden("calendar.feature_disabled");
		}
	}

	// --- the audience of a request ------------------------------------------------------------

	/**
	 * Resolves the schools a request covers. With {@code schoolId}: that school, 404 when the caller cannot see it and 403
	 * when its calendar is off. Without: every school the caller belongs to (or administers directly) with the calendar on.
	 * With {@code studentId}: the child must be an ACTIVE-link child of the caller (404 {@code calendar.student_not_found}),
	 * and everything narrows to that child.
	 */
	Target target(CurrentUser user, UUID schoolId, UUID studentId) {
		Scope scope;
		Set<UUID> schools;
		if (studentId != null) {
			if (!this.guardians.isActiveGuardian(user.userId(), studentId)) {
				throw ApiException.notFound("calendar.student_not_found");
			}
			scope = profile(user, studentId);
			schools = new LinkedHashSet<>(scope.memberSchools());
			if (schoolId != null) {
				if (!schools.contains(schoolId)) {
					throw ApiException.notFound("calendar.student_not_found");
				}
				schools = new LinkedHashSet<>(Set.of(schoolId));
			}
		}
		else {
			scope = profile(user, null);
			if (schoolId != null) {
				if (this.gate.canSee(user, schoolId)) {
					this.gate.requireVisible(user, schoolId);
				}
				else if (!scope.memberSchools().contains(schoolId)) {
					throw ApiException.notFound("school.not_found");
				}
				schools = new LinkedHashSet<>(Set.of(schoolId));
			}
			else {
				schools = new LinkedHashSet<>(scope.memberSchools());
				schools.addAll(this.access.accessible(user, Permissions.SCHOOL_READ).schoolIds());
			}
		}
		Set<UUID> enabled = new LinkedHashSet<>();
		for (UUID school : schools) {
			if (this.settings.enabled(school)) {
				enabled.add(school);
			}
		}
		if (schoolId != null && !enabled.contains(schoolId)) {
			throw ApiException.forbidden("calendar.feature_disabled");
		}
		return resolved(user, enabled, studentId, scope);
	}

	/**
	 * The target of schools that were already checked (visible, calendar on), for the sources of the union: they trust the
	 * schools of their {@link com.nemaz.school.calendar.CalendarQuery} instead of working them out again (an administrator
	 * of a whole country or organization reaches a school that is in no list of "their" schools).
	 */
	Target resolved(CurrentUser user, Set<UUID> schools, UUID studentId) {
		return resolved(user, schools, studentId, profile(user, studentId));
	}

	private Target resolved(CurrentUser user, Set<UUID> schools, UUID studentId, Scope scope) {
		Set<UUID> admin = new LinkedHashSet<>();
		if (studentId == null) {
			for (UUID school : schools) {
				if (this.access.can(user, Permissions.SCHOOL_READ, AccessScope.school(school))) {
					admin.add(school);
				}
			}
		}
		return new Target(user.userId(), studentId, schools, admin, scope);
	}

	/** What the person covers as staff, student and guardian (of {@code studentId} only when given). */
	Scope profile(CurrentUser user, UUID studentId) {
		Set<UUID> schools = new LinkedHashSet<>();
		Set<UUID> allCampus = new LinkedHashSet<>();
		Set<UUID> campuses = new LinkedHashSet<>();
		Set<UUID> grades = new LinkedHashSet<>();
		Set<UUID> childClasses = new LinkedHashSet<>();
		UUID me = user.userId();
		collect("""
				select ss.school_id, ss.campus_id, c.id as class_id, c.campus_id as class_campus, c.grade_level_id
				from student_guardians sg
				join school_students ss on ss.student_id = sg.student_id and ss.status = 'ACTIVE'
				left join class_students cs on cs.student_id = ss.student_id and cs.school_id = ss.school_id and cs.status = 'ACTIVE'
				left join classes c on c.id = cs.class_id and c.status = 'ACTIVE'
				where sg.guardian_id = :me and sg.status = 'ACTIVE' and not sg.custody_restricted
				  and (cast(:student as uuid) is null or sg.student_id = :student)""", me, studentId, schools, allCampus,
				campuses, grades, childClasses, false);
		if (studentId == null) {
			collect("""
					select ss.school_id, ss.campus_id, c.id as class_id, c.campus_id as class_campus, c.grade_level_id
					from school_students ss
					left join class_students cs on cs.student_id = ss.student_id and cs.school_id = ss.school_id and cs.status = 'ACTIVE'
					left join classes c on c.id = cs.class_id and c.status = 'ACTIVE'
					where ss.student_id = :me and ss.status = 'ACTIVE' and cast(:student as uuid) is null""", me, null,
					schools, allCampus, campuses, grades, childClasses, false);
			collect("""
					select st.school_id, st.campus_id, c.id as class_id, c.campus_id as class_campus, c.grade_level_id
					from school_staff st
					left join class_teachers ct on ct.teacher_id = st.user_id and ct.school_id = st.school_id and ct.status = 'ACTIVE'
					left join classes c on c.id = ct.class_id and c.status = 'ACTIVE'
					where st.user_id = :me and st.status = 'ACTIVE' and cast(:student as uuid) is null""", me, null, schools,
					allCampus, campuses, grades, childClasses, true);
		}
		return new Scope(schools, allCampus, campuses, grades, childClasses);
	}

	private void collect(String sql, UUID me, UUID student, Set<UUID> schools, Set<UUID> allCampus, Set<UUID> campuses,
			Set<UUID> grades, Set<UUID> childClasses, boolean staff) {
		this.jdbc.sql(sql).param("me", me).param("student", student).query((rs) -> {
			UUID school = rs.getObject("school_id", UUID.class);
			schools.add(school);
			UUID campus = rs.getObject("campus_id", UUID.class);
			if (campus != null) {
				campuses.add(campus);
			}
			else if (staff) {
				allCampus.add(school);
			}
			add(campuses, rs.getObject("class_campus", UUID.class));
			add(grades, rs.getObject("grade_level_id", UUID.class));
			add(childClasses, rs.getObject("class_id", UUID.class));
		});
	}

	private static void add(Set<UUID> set, UUID id) {
		if (id != null) {
			set.add(id);
		}
	}

	// --- reading ------------------------------------------------------------------------------

	/**
	 * The SQL predicate "the caller may see this native event" over {@code events} aliased {@code e} (deleted rows
	 * excluded), restricted to the target's schools. {@code personal} adds the caller's own personal events (not with a child
	 * filter).
	 */
	Pred visible(CurrentUser user, Target target, boolean personal, boolean schoolEvents) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("cv_me", target.userId());
		List<String> any = new ArrayList<>();
		if (personal && target.studentId() == null) {
			any.add("(e.scope_type = 'PERSONAL' and e.owner_id = :cv_me)");
		}
		if (schoolEvents && !target.schools().isEmpty()) {
			params.put("cv_schools", target.schools());
			String student = (target.studentId() != null) ? " and e.regarding_student_id = :cv_student" : "";
			if (target.studentId() != null) {
				params.put("cv_student", target.studentId());
			}
			any.add("""
					(e.scope_type = 'INVITEES' and e.school_id in (:cv_schools)%s and exists (
					   select 1 from event_participants p where p.event_id = e.id and p.user_id = :cv_me and (p.role = 'ORGANIZER'
					     or exists (select 1 from student_guardians sg
					                join school_students ss on ss.student_id = sg.student_id and ss.status = 'ACTIVE'
					                     and ss.school_id = e.school_id
					                where sg.student_id = e.regarding_student_id and sg.guardian_id = :cv_me and sg.status = 'ACTIVE'
					                  and not sg.custody_restricted and sg.can_view_academics))))""".formatted(student));
			List<String> school = new ArrayList<>();
			if (!target.adminSchools().isEmpty()) {
				params.put("cv_admin", target.adminSchools());
				school.add("(e.scope_type in ('SCHOOL', 'CAMPUS', 'GRADE_LEVEL') and e.school_id in (:cv_admin))");
			}
			Scope scope = target.scope();
			if (!scope.memberSchools().isEmpty()) {
				params.put("cv_member", scope.memberSchools());
				school.add("(e.scope_type = 'SCHOOL' and e.school_id in (:cv_member))");
			}
			if (!scope.campuses().isEmpty() || !scope.allCampusSchools().isEmpty()) {
				List<String> campus = new ArrayList<>();
				if (!scope.campuses().isEmpty()) {
					params.put("cv_campuses", scope.campuses());
					campus.add("e.campus_id in (:cv_campuses)");
				}
				if (!scope.allCampusSchools().isEmpty()) {
					params.put("cv_all_campus", scope.allCampusSchools());
					campus.add("e.school_id in (:cv_all_campus)");
				}
				school.add("(e.scope_type = 'CAMPUS' and (" + String.join(" or ", campus) + "))");
			}
			if (!scope.grades().isEmpty()) {
				params.put("cv_grades", scope.grades());
				school.add("(e.scope_type = 'GRADE_LEVEL' and e.grade_level_id in (:cv_grades))");
			}
			SqlFragment visibleClasses = this.classes.visibleClasses(user, "cv_c");
			params.putAll(visibleClasses.params());
			String childOnly = "";
			if (target.studentId() != null) {
				if (scope.childClasses().isEmpty()) {
					childOnly = " and false";
				}
				else {
					params.put("cv_child_classes", scope.childClasses());
					childOnly = " and e.class_id in (:cv_child_classes)";
				}
			}
			school.add("(e.scope_type = 'CLASS'%s and exists (select 1 from classes cv_c where cv_c.id = e.class_id and (%s)))"
				.formatted(childOnly, visibleClasses.sql()));
			any.add("(e.school_id in (:cv_schools) and e.scope_type <> 'INVITEES' and (" + String.join(" or ", school) + "))");
		}
		if (any.isEmpty()) {
			return new Pred("false", Map.of());
		}
		return new Pred("(e.deleted_at is null and (" + String.join(" or ", any) + "))", params);
	}

	// --- writing ------------------------------------------------------------------------------

	/** True when the caller may change, cancel or delete the event. */
	boolean mayManage(CurrentUser user, EventRow event) {
		return switch (event.scope()) {
			case PERSONAL -> user.userId().equals(event.ownerId());
			case INVITEES -> user.userId().equals(event.createdBy());
			case SCHOOL, CAMPUS, GRADE_LEVEL -> manager(user, event.schoolId());
			case CLASS -> manager(user, event.schoolId()) || this.classes.canManage(user, event.classId())
					|| (this.settings.teachersCanCreateClassEvents(event.schoolId()) && leads(user, event.classId()));
		};
	}

	/** ACTIVE LEAD or CO_TEACHER of the class. */
	boolean leads(CurrentUser user, UUID classId) {
		return this.classes.membershipOf(user, classId)
			.filter((m) -> m.kind() == Kind.TEACHER)
			.map((m) -> m.teacherRole() == TeacherRole.LEAD || m.teacherRole() == TeacherRole.CO_TEACHER)
			.orElse(false);
	}

	/** May the caller create a CLASS event for the class (they can see it)? */
	boolean mayCreateClassEvent(CurrentUser user, UUID schoolId, UUID classId) {
		return manager(user, schoolId) || this.classes.canManage(user, classId)
				|| (this.settings.teachersCanCreateClassEvents(schoolId) && leads(user, classId));
	}

	/** The classes of the school the caller may create events for without the school-wide permission. */
	Set<UUID> creatableClasses(CurrentUser user, UUID schoolId) {
		Set<UUID> result = new LinkedHashSet<>();
		if (this.settings.teachersCanCreateClassEvents(schoolId)) {
			result.addAll(this.jdbc.sql("""
					select ct.class_id from class_teachers ct join classes c on c.id = ct.class_id and c.status = 'ACTIVE'
					where ct.teacher_id = :me and ct.school_id = :s and ct.status = 'ACTIVE' and ct.role in ('LEAD', 'CO_TEACHER')""")
				.param("me", user.userId())
				.param("s", schoolId)
				.query(UUID.class)
				.list());
		}
		for (UUID classId : this.classes.accessibleClassIds(user)) {
			if (!result.contains(classId) && this.classes.canManage(user, classId)
					&& this.classes.find(classId).filter((c) -> c.schoolId().equals(schoolId) && !c.archived()).isPresent()) {
				result.add(classId);
			}
		}
		return result;
	}

	/**
	 * May the caller invite the guardians of this student to a parent meeting? An ACTIVE LEAD, CO_TEACHER or SUBJECT_TEACHER
	 * of a class in which the student is ACTIVE ("a teacher about one of their own students").
	 */
	boolean teachesStudent(UUID teacherId, UUID schoolId, UUID studentId) {
		return this.jdbc.sql("""
				select exists (select 1 from class_teachers ct
				               join class_students cs on cs.class_id = ct.class_id and cs.student_id = :student and cs.status = 'ACTIVE'
				               where ct.teacher_id = :me and ct.school_id = :school and ct.status = 'ACTIVE'
				                 and ct.role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER'))""")
			.param("me", teacherId)
			.param("school", schoolId)
			.param("student", studentId)
			.query(Boolean.class)
			.single();
	}

	/** The ACTIVE students of the school (404 {@code event.student_not_found} for others). */
	void requireActiveStudent(UUID schoolId, UUID studentId) {
		boolean active = studentId != null && this.jdbc
			.sql("select exists (select 1 from school_students where school_id = :s and student_id = :st and status = 'ACTIVE')")
			.param("s", schoolId)
			.param("st", studentId)
			.query(Boolean.class)
			.single();
		if (!active) {
			throw ApiException.notFound("event.student_not_found");
		}
	}

	/** Ids that belong to the school: 400 {@code event.audience_invalid} otherwise (no hint which). */
	void requireOwned(String table, UUID id, UUID schoolId) {
		boolean owned = id != null
				&& this.jdbc.sql("select exists (select 1 from " + table + " where id = :id and school_id = :s)")
					.param("id", id)
					.param("s", schoolId)
					.query(Boolean.class)
					.single();
		if (!owned) {
			throw ApiException.badRequest("event.audience_invalid");
		}
	}

	/** Guardians of the student who may be invited to a parent meeting: ACTIVE, not restricted, allowed academics. */
	List<UUID> invitableGuardians(UUID schoolId, UUID studentId) {
		return this.jdbc.sql("""
				select distinct sg.guardian_id from student_guardians sg
				join school_students ss on ss.student_id = sg.student_id and ss.status = 'ACTIVE' and ss.school_id = :school
				where sg.student_id = :student and sg.status = 'ACTIVE' and not sg.custody_restricted and sg.can_view_academics""")
			.param("school", schoolId)
			.param("student", studentId)
			.query(UUID.class)
			.list();
	}

	static boolean schoolWideType(EventType type) {
		return type == EventType.SCHOOL_EVENT || type == EventType.EXAM || type == EventType.OTHER;
	}

}

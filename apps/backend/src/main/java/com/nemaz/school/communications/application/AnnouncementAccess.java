package com.nemaz.school.communications.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.authorization.RoleAssignments;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementAudienceInput;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementChannel;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementType;
import com.nemaz.school.communications.application.AnnouncementModels.AudienceType;
import com.nemaz.school.communications.application.AnnouncementModels.RecipientRole;
import com.nemaz.school.tenancy.FeatureService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The access questions of announcements. The 404-or-403 rule: a school the caller cannot see is 404, a class they cannot
 * see is 404 {@code class.not_found}, an announcement they may not read is 404 {@code announcement.not_found}; members who
 * lack the right get 403.
 *
 * <p>
 * <b>Who may announce to what</b> (compose, publish, schedule):
 * <ul>
 * <li>{@code school.announcements.manage} at the school: any audience (school, campus, grade level, class, chosen
 * people) and any role, students only when the school allows announcements to students.</li>
 * <li>An ACTIVE LEAD or CO_TEACHER of a class while {@code announcements.teachers_can_announce} (default on), and a class
 * manager ({@code class.manage}): CLASS audiences of that class and chosen people among its students and their guardians,
 * to the roles GUARDIAN and STUDENT only.</li>
 * <li>URGENT and SAFETY additionally need {@code school.announcements.urgent} at the school.</li>
 * </ul>
 *
 * <p>
 * <b>Who may read</b> a PUBLISHED or WITHDRAWN announcement ("reader access"): its author and the school's announcement
 * managers; a person in the recipient snapshot who still holds the snapshot role in the school (a revoked or restricted
 * guardian, a withdrawn student or a former employee loses access); and, for announcements published in the last
 * {@value #LATE_JOINER_DAYS} days, a person the audience rule matches <em>now</em> (a guardian linked later, a student
 * who joined the class later): they see it in the school's list but were not notified and are not counted in the report.
 * Matching uses the same eligibility as the recipient snapshot (ACTIVE, not custody-restricted guardian with
 * {@code can_receive_notifications}; ACTIVE student, only when the school addresses students; ACTIVE staff).
 */
@Component
@Transactional(readOnly = true)
public class AnnouncementAccess {

	/** How long after publishing a person who matches the audience only now (a late joiner) can still open it. */
	static final int LATE_JOINER_DAYS = 180;

	/** The basis an announcement was authorized on, kept to re-check a scheduled one at publish time. */
	public enum Basis {

		MANAGER, TEACHER

	}

	/** The ids a person covers in one role: whole schools, campuses, grade levels and classes. */
	public record RoleScope(Set<UUID> schools, Set<UUID> campuses, Set<UUID> grades, Set<UUID> classes) {

		static RoleScope empty() {
			return new RoleScope(Set.of(), Set.of(), Set.of(), Set.of());
		}

	}

	public record ViewerProfile(UUID userId, RoleScope guardian, RoleScope student, RoleScope staff) {

		boolean inSchool(UUID schoolId) {
			return this.guardian.schools().contains(schoolId) || this.student.schools().contains(schoolId)
					|| this.staff.schools().contains(schoolId);
		}

		Set<UUID> allSchools() {
			Set<UUID> all = new LinkedHashSet<>(this.guardian.schools());
			all.addAll(this.student.schools());
			all.addAll(this.staff.schools());
			return all;
		}

	}

	/** A SQL boolean expression and its named parameters. */
	record Pred(String sql, Map<String, Object> params) {
	}

	private final SchoolGate gate;

	private final AccessControl access;

	private final ClassAccess classes;

	private final FeatureService features;

	private final CommunicationsSettings settings;

	private final JdbcClient jdbc;

	private final RoleAssignments roleAssignments;

	AnnouncementAccess(SchoolGate gate, AccessControl access, ClassAccess classes, FeatureService features,
			CommunicationsSettings settings, JdbcClient jdbc, RoleAssignments roleAssignments) {
		this.gate = gate;
		this.access = access;
		this.classes = classes;
		this.features = features;
		this.settings = settings;
		this.jdbc = jdbc;
		this.roleAssignments = roleAssignments;
	}

	// --- the school ---------------------------------------------------------------------------

	public boolean manager(CurrentUser user, UUID schoolId) {
		return this.access.can(user, Permissions.SCHOOL_ANNOUNCEMENTS_MANAGE, AccessScope.school(schoolId));
	}

	boolean urgentAllowed(CurrentUser user, UUID schoolId) {
		return this.access.can(user, Permissions.SCHOOL_ANNOUNCEMENTS_URGENT, AccessScope.school(schoolId));
	}

	/**
	 * For acting on announcements (compose, edit, report...): 404 unless the school is visible to the caller (a member or
	 * administrator of it, or a guardian of one of its students, who then gets 403 for what they may not do), 403 when the
	 * feature is off.
	 */
	void requireWrite(CurrentUser user, UUID schoolId) {
		if (this.gate.canSee(user, schoolId)) {
			this.gate.requireVisible(user, schoolId);
		}
		else if (!profile(user).inSchool(schoolId)) {
			throw ApiException.notFound("school.not_found");
		}
		requireFeature(schoolId);
	}

	/** For reading: guardians are not school members, so a relation in the profile also makes the school visible. */
	void requireRead(CurrentUser user, UUID schoolId, ViewerProfile profile) {
		if (!profile.inSchool(schoolId)) {
			this.gate.requireVisible(user, schoolId);
		}
		requireFeature(schoolId);
	}

	void requireFeature(UUID schoolId) {
		if (!this.features.isEnabled(CommunicationsSettings.FEATURE, schoolId)) {
			throw ApiException.forbidden("announcement.feature_disabled");
		}
	}

	boolean featureEnabled(UUID schoolId) {
		return this.features.isEnabled(CommunicationsSettings.FEATURE, schoolId);
	}

	/**
	 * A stand-in for the author when a job acts for them (the scheduled publisher): their ACTIVE grants, so the same
	 * permission checks apply as on a request.
	 */
	CurrentUser userFor(UUID userId) {
		return new CurrentUser(userId, null, Locale.ENGLISH, this.roleAssignments.activeGrants(userId));
	}

	// --- composing ----------------------------------------------------------------------------

	/**
	 * Checks that the caller may send an announcement of this type and channels to these audiences and returns the basis.
	 * Everything the caller named is validated against the school: no id of another school can slip in.
	 */
	@Transactional(readOnly = true)
	public Basis authorize(CurrentUser user, UUID schoolId, AnnouncementType type, Set<AnnouncementChannel> channels,
			List<AnnouncementAudienceInput> audiences) {
		boolean manager = manager(user, schoolId);
		if (type.urgent() && !urgentAllowed(user, schoolId)) {
			throw ApiException.forbidden("announcement.urgent_not_allowed");
		}
		boolean teachersAllowed = this.settings.teachersCanAnnounce(schoolId);
		boolean studentsEnabled = this.settings.studentsEnabled(schoolId);
		Set<UUID> teachingClasses = null;
		for (AnnouncementAudienceInput audience : audiences) {
			if (audience.roles().contains(RecipientRole.STUDENT) && !studentsEnabled) {
				throw ApiException.badRequest("announcement.students_disabled");
			}
			if (!manager && audience.roles().contains(RecipientRole.STAFF)) {
				throw ApiException.forbidden("announcement.audience_not_allowed");
			}
			switch (audience.type()) {
				case SCHOOL -> requireManager(manager);
				case CAMPUS -> {
					requireManager(manager);
					requireOwned("campuses", audience.campusId(), schoolId);
				}
				case GRADE_LEVEL -> {
					requireManager(manager);
					requireOwned("grade_levels", audience.gradeLevelId(), schoolId);
				}
				case CLASS -> {
					ClassRef ref = (audience.classId() == null) ? null
							: this.classes.find(audience.classId()).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
					if (ref == null || (!manager && !this.classes.canView(user, ref.id()))) {
						throw ApiException.notFound("class.not_found");
					}
					if (ref.archived()) {
						throw ApiException.conflict("class.archived");
					}
					if (!manager && !this.classes.canManage(user, ref.id()) && !(teachersAllowed && teaches(user, ref.id()))) {
						throw ApiException.forbidden("announcement.audience_not_allowed");
					}
				}
				case USER -> {
					if (audience.userIds().isEmpty()) {
						throw ApiException.badRequest("announcement.audience_invalid");
					}
					if (manager) {
						requireRelatedToSchool(audience.userIds(), schoolId);
					}
					else {
						if (teachingClasses == null) {
							teachingClasses = announceClasses(user, schoolId, teachersAllowed);
						}
						requireRelatedToClasses(audience.userIds(), schoolId, teachingClasses);
					}
				}
			}
		}
		return manager ? Basis.MANAGER : Basis.TEACHER;
	}

	private static void requireManager(boolean manager) {
		if (!manager) {
			throw ApiException.forbidden("announcement.audience_not_allowed");
		}
	}

	/** 400 {@code announcement.audience_invalid} unless the row exists and belongs to the school (no hint which). */
	private void requireOwned(String table, UUID id, UUID schoolId) {
		boolean owned = id != null && this.jdbc.sql("select exists (select 1 from " + table + " where id = :id and school_id = :s)")
			.param("id", id)
			.param("s", schoolId)
			.query(Boolean.class)
			.single();
		if (!owned) {
			throw ApiException.badRequest("announcement.audience_invalid");
		}
	}

	private boolean teaches(CurrentUser user, UUID classId) {
		return this.classes.membershipOf(user, classId)
			.filter((m) -> m.kind() == Kind.TEACHER)
			.map((m) -> m.teacherRole() == TeacherRole.LEAD || m.teacherRole() == TeacherRole.CO_TEACHER)
			.orElse(false);
	}

	/** Classes the caller may announce to without the school-wide permission: their lead/co classes and those they manage. */
	Set<UUID> announceClasses(CurrentUser user, UUID schoolId, boolean teachersAllowed) {
		Set<UUID> result = new LinkedHashSet<>();
		if (teachersAllowed) {
			result.addAll(this.jdbc.sql("""
					select ct.class_id from class_teachers ct join classes c on c.id = ct.class_id and c.status = 'ACTIVE'
					where ct.teacher_id = :me and ct.school_id = :s and ct.status = 'ACTIVE'
					  and ct.role in ('LEAD', 'CO_TEACHER')""")
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

	private void requireRelatedToSchool(List<UUID> userIds, UUID schoolId) {
		Set<UUID> distinct = new HashSet<>(userIds);
		int found = this.jdbc.sql("""
				select count(*) from users u where u.id in (:ids) and u.status = 'ACTIVE' and u.deleted_at is null and (
				  exists (select 1 from school_staff st where st.user_id = u.id and st.school_id = :s and st.status = 'ACTIVE')
				  or exists (select 1 from school_students ss where ss.student_id = u.id and ss.school_id = :s
				             and ss.status = 'ACTIVE')
				  or exists (select 1 from student_guardians sg
				             join school_students ss on ss.student_id = sg.student_id and ss.school_id = :s and ss.status = 'ACTIVE'
				             where sg.guardian_id = u.id and sg.status = 'ACTIVE' and not sg.custody_restricted
				               and sg.can_receive_notifications))""")
			.param("ids", distinct)
			.param("s", schoolId)
			.query(Integer.class)
			.single();
		if (found != distinct.size()) {
			throw ApiException.badRequest("announcement.audience_user_invalid");
		}
	}

	private void requireRelatedToClasses(List<UUID> userIds, UUID schoolId, Set<UUID> classIds) {
		Set<UUID> distinct = new HashSet<>(userIds);
		if (classIds.isEmpty()) {
			throw ApiException.forbidden("announcement.audience_not_allowed");
		}
		int found = this.jdbc.sql("""
				select count(*) from users u where u.id in (:ids) and u.status = 'ACTIVE' and u.deleted_at is null and (
				  exists (select 1 from class_students cs where cs.student_id = u.id and cs.status = 'ACTIVE'
				          and cs.class_id in (:classes))
				  or exists (select 1 from student_guardians sg
				             join class_students cs on cs.student_id = sg.student_id and cs.status = 'ACTIVE'
				             where sg.guardian_id = u.id and sg.status = 'ACTIVE' and not sg.custody_restricted
				               and sg.can_receive_notifications and cs.class_id in (:classes)))""")
			.param("ids", distinct)
			.param("classes", classIds)
			.query(Integer.class)
			.single();
		if (found != distinct.size()) {
			throw ApiException.badRequest("announcement.audience_user_invalid");
		}
	}

	// --- reading ------------------------------------------------------------------------------

	/** What the person covers as guardian (through their children), student and staff member. */
	public ViewerProfile profile(CurrentUser user) {
		UUID me = user.userId();
		RoleScope guardian = scope("""
				select ss.school_id, ss.campus_id, c.id as class_id, c.grade_level_id, c.campus_id as class_campus
				from student_guardians sg
				join school_students ss on ss.student_id = sg.student_id and ss.status = 'ACTIVE'
				left join class_students cs on cs.student_id = ss.student_id and cs.school_id = ss.school_id
				                           and cs.status = 'ACTIVE'
				left join classes c on c.id = cs.class_id and c.status = 'ACTIVE'
				where sg.guardian_id = :me and sg.status = 'ACTIVE' and not sg.custody_restricted
				  and sg.can_receive_notifications""", me, false);
		RoleScope student = scope("""
				select ss.school_id, ss.campus_id, c.id as class_id, c.grade_level_id, c.campus_id as class_campus
				from school_students ss
				left join class_students cs on cs.student_id = ss.student_id and cs.school_id = ss.school_id
				                           and cs.status = 'ACTIVE'
				left join classes c on c.id = cs.class_id and c.status = 'ACTIVE'
				where ss.student_id = :me and ss.status = 'ACTIVE'""", me, true);
		RoleScope staff = scope("""
				select st.school_id, st.campus_id, c.id as class_id, c.grade_level_id, c.campus_id as class_campus
				from school_staff st
				left join class_teachers ct on ct.teacher_id = st.user_id and ct.school_id = st.school_id
				                           and ct.status = 'ACTIVE'
				left join classes c on c.id = ct.class_id and c.status = 'ACTIVE'
				where st.user_id = :me and st.status = 'ACTIVE'""", me, false);
		return new ViewerProfile(me, guardian, student, staff);
	}

	private RoleScope scope(String sql, UUID me, boolean studentRole) {
		Set<UUID> schools = new LinkedHashSet<>();
		Set<UUID> campuses = new LinkedHashSet<>();
		Set<UUID> grades = new LinkedHashSet<>();
		Set<UUID> classIds = new LinkedHashSet<>();
		Map<UUID, Boolean> enabled = new HashMap<>();
		this.jdbc.sql(sql).param("me", me).query((rs) -> {
			UUID school = rs.getObject("school_id", UUID.class);
			if (studentRole && !enabled.computeIfAbsent(school, this.settings::studentsEnabled)) {
				return;
			}
			schools.add(school);
			add(campuses, rs.getObject("campus_id", UUID.class));
			add(campuses, rs.getObject("class_campus", UUID.class));
			add(grades, rs.getObject("grade_level_id", UUID.class));
			add(classIds, rs.getObject("class_id", UUID.class));
		});
		return new RoleScope(schools, campuses, grades, classIds);
	}

	private static void add(Set<UUID> set, UUID id) {
		if (id != null) {
			set.add(id);
		}
	}

	/**
	 * The SQL predicate "the viewer may read this announcement" over {@code announcements} aliased {@code an}.
	 * {@code snapshotOnly} limits it to the recipient snapshot (the inbox of what was delivered to the person);
	 * otherwise the author, school managers ({@code manager}) and late joiners count too (the school's list).
	 */
	Pred readable(ViewerProfile profile, boolean snapshotOnly, boolean manager, Instant now) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("me", profile.userId());
		List<String> any = new ArrayList<>();
		if (!snapshotOnly) {
			any.add("an.author_id = :me");
			if (manager) {
				any.add("true");
			}
		}
		List<String> snapshotRoles = new ArrayList<>();
		addRoleSchool(snapshotRoles, params, "GUARDIAN", "g", profile.guardian());
		addRoleSchool(snapshotRoles, params, "STUDENT", "s", profile.student());
		addRoleSchool(snapshotRoles, params, "STAFF", "t", profile.staff());
		if (!snapshotRoles.isEmpty()) {
			any.add("exists (select 1 from announcement_recipients r where r.announcement_id = an.id and r.user_id = :me and ("
					+ String.join(" or ", snapshotRoles) + "))");
		}
		if (!snapshotOnly) {
			List<String> dynamic = new ArrayList<>();
			addRoleAudience(dynamic, params, "GUARDIAN", "g", profile.guardian());
			addRoleAudience(dynamic, params, "STUDENT", "s", profile.student());
			addRoleAudience(dynamic, params, "STAFF", "t", profile.staff());
			if (!dynamic.isEmpty()) {
				params.put("late_cutoff", java.sql.Timestamp.from(now.minus(Duration.ofDays(LATE_JOINER_DAYS))));
				any.add("(an.published_at >= :late_cutoff and exists (select 1 from announcement_audiences a "
						+ "where a.announcement_id = an.id and a.audience_type <> 'USER' and (" + String.join(" or ", dynamic)
						+ ")))");
			}
		}
		if (any.isEmpty()) {
			return new Pred("false", Map.of());
		}
		return new Pred("(" + String.join(" or ", any) + ")", params);
	}

	private static void addRoleSchool(List<String> parts, Map<String, Object> params, String role, String prefix,
			RoleScope scope) {
		if (!scope.schools().isEmpty()) {
			params.put(prefix + "_schools", scope.schools());
			parts.add("(r.recipient_role = '" + role + "' and an.school_id in (:" + prefix + "_schools))");
		}
	}

	private static void addRoleAudience(List<String> parts, Map<String, Object> params, String role, String prefix,
			RoleScope scope) {
		List<String> conditions = new ArrayList<>();
		if (!scope.schools().isEmpty()) {
			params.put(prefix + "_schools", scope.schools());
			conditions.add("(a.audience_type = 'SCHOOL' and an.school_id in (:" + prefix + "_schools))");
		}
		if (!scope.campuses().isEmpty()) {
			params.put(prefix + "_campuses", scope.campuses());
			conditions.add("(a.audience_type = 'CAMPUS' and a.campus_id in (:" + prefix + "_campuses))");
		}
		if (!scope.grades().isEmpty()) {
			params.put(prefix + "_grades", scope.grades());
			conditions.add("(a.audience_type = 'GRADE_LEVEL' and a.grade_level_id in (:" + prefix + "_grades))");
		}
		if (!scope.classes().isEmpty()) {
			params.put(prefix + "_classes", scope.classes());
			conditions.add("(a.audience_type = 'CLASS' and a.class_id in (:" + prefix + "_classes))");
		}
		if (!conditions.isEmpty()) {
			parts.add("('" + role + "' = any(a.recipient_roles) and (" + String.join(" or ", conditions) + "))");
		}
	}

}

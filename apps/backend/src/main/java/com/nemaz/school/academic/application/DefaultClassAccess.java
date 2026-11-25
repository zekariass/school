package com.nemaz.school.academic.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccessRule;
import com.nemaz.school.academic.ClassMembershipView;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.AccessibleScopes;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ClassAccess} in SQL: one predicate decides view access for single classes and for lists alike, so
 * the two can never disagree. Branches (any one grants): ACTIVE student; ACTIVE teacher; {@code class.manage}
 * over the class's school, campus, organization, country or the whole platform; contributed
 * {@link ClassAccessRule}s.
 */
@Component
@Transactional(readOnly = true)
class DefaultClassAccess implements ClassAccess {

	private final JdbcClient jdbc;

	private final AccessControl access;

	private final List<ClassAccessRule> rules;

	DefaultClassAccess(JdbcClient jdbc, AccessControl access, List<ClassAccessRule> rules) {
		this.jdbc = jdbc;
		this.access = access;
		this.rules = rules;
	}

	@Override
	public boolean canView(CurrentUser user, UUID classId) {
		if (user == null || classId == null) {
			return false;
		}
		SqlFragment visible = visibleClasses(user, "c");
		return this.jdbc.sql("select exists (select 1 from classes c where c.id = :ca_class and (" + visible.sql() + "))")
			.param("ca_class", classId)
			.params(visible.params())
			.query(Boolean.class)
			.single();
	}

	@Override
	public boolean canManage(CurrentUser user, UUID classId) {
		if (user == null || classId == null || !this.access.canAnywhere(user, Permissions.CLASS_MANAGE)) {
			return false;
		}
		return find(classId).map((c) -> this.access.can(user, Permissions.CLASS_MANAGE,
				(c.campusId() != null) ? AccessScope.campus(c.schoolId(), c.campusId()) : AccessScope.school(c.schoolId())))
			.orElse(false);
	}

	@Override
	public boolean canTeach(CurrentUser user, UUID classId) {
		return teacherRole(user, classId).isPresent() || canManage(user, classId);
	}

	@Override
	public boolean canLead(CurrentUser user, UUID classId) {
		return teacherRole(user, classId).filter((r) -> r == TeacherRole.LEAD).isPresent() || canManage(user, classId);
	}

	@Override
	public Optional<ClassMembershipView> membershipOf(CurrentUser user, UUID classId) {
		if (user == null || classId == null) {
			return Optional.empty();
		}
		Optional<ClassMembershipView> teacher = this.jdbc.sql("""
				select class_id, role, subject_id, joined_at from class_teachers
				where class_id = :c and teacher_id = :u and status = 'ACTIVE'
				order by case role when 'LEAD' then 0 else 1 end, joined_at limit 1""")
			.param("c", classId)
			.param("u", user.userId())
			.query((rs, i) -> new ClassMembershipView(Rows.uuid(rs, "class_id"), Kind.TEACHER,
					TeacherRole.valueOf(rs.getString("role")), Rows.uuid(rs, "subject_id"), Rows.instant(rs, "joined_at")))
			.optional();
		if (teacher.isPresent()) {
			return teacher;
		}
		return this.jdbc
			.sql("select class_id, joined_at from class_students where class_id = :c and student_id = :u and status = 'ACTIVE'")
			.param("c", classId)
			.param("u", user.userId())
			.query((rs, i) -> new ClassMembershipView(Rows.uuid(rs, "class_id"), Kind.STUDENT, null, null,
					Rows.instant(rs, "joined_at")))
			.optional();
	}

	@Override
	public Set<UUID> accessibleClassIds(CurrentUser user) {
		if (user == null) {
			return Set.of();
		}
		SqlFragment visible = visibleClasses(user, "c");
		return new LinkedHashSet<>(this.jdbc.sql("select c.id from classes c where " + visible.sql() + " order by c.id")
			.params(visible.params())
			.query(UUID.class)
			.list());
	}

	@Override
	public SqlFragment visibleClasses(CurrentUser user, String a) {
		if (user == null) {
			return SqlFragment.never();
		}
		Map<String, Object> params = new HashMap<>();
		List<String> any = new ArrayList<>();
		params.put("ca_user", user.userId());
		any.add("exists (select 1 from class_students ca_cs where ca_cs.class_id = " + a + ".id"
				+ " and ca_cs.student_id = :ca_user and ca_cs.status = 'ACTIVE')");
		any.add("exists (select 1 from class_teachers ca_ct where ca_ct.class_id = " + a + ".id"
				+ " and ca_ct.teacher_id = :ca_user and ca_ct.status = 'ACTIVE')");
		AccessibleScopes managed = this.access.accessible(user, Permissions.CLASS_MANAGE);
		if (managed.platform()) {
			return SqlFragment.always();
		}
		if (!managed.schoolIds().isEmpty()) {
			any.add(a + ".school_id in (:ca_schools)");
			params.put("ca_schools", managed.schoolIds());
		}
		if (!managed.campusIds().isEmpty()) {
			any.add(a + ".campus_id in (:ca_campuses)");
			params.put("ca_campuses", managed.campusIds());
		}
		if (!managed.organizationIds().isEmpty()) {
			any.add(a + ".school_id in (select ca_s.id from schools ca_s where ca_s.organization_id in (:ca_orgs))");
			params.put("ca_orgs", managed.organizationIds());
		}
		if (!managed.countryCodes().isEmpty()) {
			any.add(a + ".school_id in (select ca_s2.id from schools ca_s2 where ca_s2.country_code in (:ca_countries))");
			params.put("ca_countries", managed.countryCodes());
		}
		for (ClassAccessRule rule : this.rules) {
			rule.visibleClasses(user, a).ifPresent((fragment) -> {
				any.add("(" + fragment.sql() + ")");
				params.putAll(fragment.params());
			});
		}
		return new SqlFragment("(" + String.join(" or ", any) + ")", params);
	}

	@Override
	public Optional<ClassRef> find(UUID classId) {
		if (classId == null) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				select id, school_id, campus_id, academic_year_id, grade_level_id, name, status from classes where id = :id""")
			.param("id", classId)
			.query(DefaultClassAccess::ref)
			.optional();
	}

	private Optional<TeacherRole> teacherRole(CurrentUser user, UUID classId) {
		if (user == null || classId == null) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				select role from class_teachers where class_id = :c and teacher_id = :u and status = 'ACTIVE'
				order by case role when 'LEAD' then 0 else 1 end limit 1""")
			.param("c", classId)
			.param("u", user.userId())
			.query((rs, i) -> TeacherRole.valueOf(rs.getString("role")))
			.optional();
	}

	private static ClassRef ref(ResultSet rs, int row) throws SQLException {
		return new ClassRef(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), Rows.uuid(rs, "campus_id"),
				Rows.uuid(rs, "academic_year_id"), Rows.uuid(rs, "grade_level_id"), rs.getString("name"),
				"ARCHIVED".equals(rs.getString("status")));
	}

}

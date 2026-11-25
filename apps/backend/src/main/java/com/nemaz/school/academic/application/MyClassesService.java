package com.nemaz.school.academic.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.tenancy.TenantDirectory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own classes, as teacher or student, across schools: the feed of "My classes" in the apps.
 * Memberships only, not administrative reach (an administrator who also teaches sees those classes).
 */
@Service
@Transactional(readOnly = true)
public class MyClassesService {

	static final int MAX = 200;

	private final JdbcClient jdbc;

	private final TenantDirectory directory;

	MyClassesService(JdbcClient jdbc, TenantDirectory directory) {
		this.jdbc = jdbc;
		this.directory = directory;
	}

	public List<MyClass> list(CurrentUser user, boolean includeArchived) {
		return this.jdbc.sql("""
				select c.id, c.school_id, c.name, c.class_type, c.icon, c.color, c.status, c.grade_level_id, gl.name as grade_name,
				       c.academic_year_id, m.kind, m.role, m.subject_id,
				       (select count(*) from class_students s where s.class_id = c.id and s.status = 'ACTIVE') as student_count
				from (select ct.class_id, 'TEACHER' as kind, ct.role, ct.subject_id from class_teachers ct
				        where ct.teacher_id = :u and ct.status = 'ACTIVE'
				      union all
				      select cs.class_id, 'STUDENT', null, null from class_students cs
				        where cs.student_id = :u and cs.status = 'ACTIVE') m
				join classes c on c.id = m.class_id
				left join grade_levels gl on gl.id = c.grade_level_id
				where (:archived or c.status = 'ACTIVE')
				order by c.school_id, c.name, c.id limit :max""")
			.param("u", user.userId())
			.param("archived", includeArchived)
			.param("max", MAX)
			.query((rs, i) -> {
				UUID schoolId = Rows.uuid(rs, "school_id");
				TenantDirectory.SchoolRef school = this.directory.school(schoolId).orElse(null);
				String role = rs.getString("role");
				return new MyClass(Rows.uuid(rs, "id"), schoolId, (school != null) ? school.slug() : null,
						(school != null) ? school.name() : null, rs.getString("name"),
						ClassService.ClassType.valueOf(rs.getString("class_type")), rs.getString("icon"),
						rs.getString("color"), ClassService.ClassStatus.valueOf(rs.getString("status")),
						Rows.uuid(rs, "grade_level_id"), rs.getString("grade_name"), Kind.valueOf(rs.getString("kind")),
						(role != null) ? TeacherRole.valueOf(role) : null, Rows.uuid(rs, "subject_id"),
						rs.getInt("student_count"));
			})
			.list();
	}

	public record MyClass(@NotNull UUID id, @NotNull UUID schoolId, String schoolSlug, String schoolName,
			@NotNull String name, @NotNull ClassService.ClassType classType, String icon, String color,
			@NotNull ClassService.ClassStatus status, UUID gradeLevelId, String gradeLevelName,
			@NotNull @Schema(description = "The caller's role in this class") Kind role,
			@Schema(description = "Teacher role when role is TEACHER") TeacherRole teacherRole, UUID subjectId,
			@NotNull int studentCount) {
	}

}

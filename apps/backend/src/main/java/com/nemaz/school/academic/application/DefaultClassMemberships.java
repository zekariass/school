package com.nemaz.school.academic.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassMembershipChanged;
import com.nemaz.school.academic.ClassMembershipChanged.Change;
import com.nemaz.school.academic.ClassMembershipChanged.MemberType;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.persistence.Rows;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultClassMemberships implements ClassMemberships {

	private final JdbcClient jdbc;

	private final RosterWrites writes;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	DefaultClassMemberships(JdbcClient jdbc, RosterWrites writes, ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.writes = writes;
		this.events = events;
		this.clock = clock;
	}

	@Override
	public void addStudent(UUID schoolId, UUID classId, UUID studentId, UUID actorId) {
		String status = this.jdbc.sql("select status from classes where id = :id and school_id = :s for update")
			.param("id", classId)
			.param("s", schoolId)
			.query(String.class)
			.optional()
			.orElseThrow(() -> ApiException.notFound("class.not_found"));
		if ("ARCHIVED".equals(status)) {
			throw ApiException.conflict("class.archived");
		}
		this.writes.addStudent(schoolId, classId, studentId, actorId);
	}

	@Override
	public int endStudentMemberships(UUID schoolId, UUID studentId, String reason, UUID actorId) {
		List<UUID> classIds = this.jdbc.sql("""
				select class_id from class_students where school_id = :s and student_id = :st and status in ('PENDING', 'ACTIVE')
				order by class_id for update""")
			.param("s", schoolId)
			.param("st", studentId)
			.query(UUID.class)
			.list();
		int ended = 0;
		for (UUID classId : classIds) {
			ended += this.writes.endStudent(schoolId, classId, studentId, reason, actorId);
		}
		return ended;
	}

	@Override
	public int endTeacherMemberships(UUID schoolId, UUID teacherId, UUID actorId) {
		record Row(UUID classId, String role) {
		}
		List<Row> rows = this.jdbc.sql("""
				select id, class_id, role from class_teachers
				where school_id = :s and teacher_id = :t and status in ('PENDING', 'ACTIVE') order by id for update""")
			.param("s", schoolId)
			.param("t", teacherId)
			.query((rs, i) -> new Row(Rows.uuid(rs, "class_id"), rs.getString("role")))
			.list();
		this.jdbc.sql("""
				update class_teachers set status = 'FORMER', left_at = :now, updated_at = now(), version = version + 1
				where school_id = :s and teacher_id = :t and status in ('PENDING', 'ACTIVE')""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("s", schoolId)
			.param("t", teacherId)
			.update();
		for (Row row : rows) {
			this.events.publishEvent(new ClassMembershipChanged(row.classId(), schoolId, teacherId, MemberType.TEACHER,
					Change.LEFT, row.role(), "LEFT_SCHOOL", actorId));
		}
		return rows.size();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean teachesStudent(UUID teacherId, UUID studentId) {
		return this.jdbc.sql("""
				select exists (select 1 from class_teachers ct join class_students cs on cs.class_id = ct.class_id
				where ct.teacher_id = :t and ct.status = 'ACTIVE' and cs.student_id = :st and cs.status = 'ACTIVE')""")
			.param("t", teacherId)
			.param("st", studentId)
			.query(Boolean.class)
			.single();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean shareActiveClass(UUID a, UUID b) {
		return this.jdbc.sql("""
				select exists (
				  select 1 from (select class_id from class_students where student_id = :a and status = 'ACTIVE'
				                 union select class_id from class_teachers where teacher_id = :a and status = 'ACTIVE') ma
				  join (select class_id from class_students where student_id = :b and status = 'ACTIVE'
				        union select class_id from class_teachers where teacher_id = :b and status = 'ACTIVE') mb
				  on mb.class_id = ma.class_id)""")
			.param("a", a)
			.param("b", b)
			.query(Boolean.class)
			.single();
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> activeStudentIds(UUID classId) {
		return new LinkedHashSet<>(this.jdbc
			.sql("select student_id from class_students where class_id = :c and status = 'ACTIVE' order by student_id")
			.param("c", classId)
			.query(UUID.class)
			.list());
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> activeStudentIdsInGrade(UUID schoolId, UUID gradeLevelId) {
		return new LinkedHashSet<>(this.jdbc.sql("""
				select distinct cs.student_id from class_students cs join classes c on c.id = cs.class_id
				where c.school_id = :s and c.grade_level_id = :g and cs.status = 'ACTIVE'""")
			.param("s", schoolId)
			.param("g", gradeLevelId)
			.query(UUID.class)
			.list());
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> activeStudentIdsOfTeacher(UUID teacherId) {
		return new LinkedHashSet<>(this.jdbc.sql("""
				select distinct cs.student_id from class_teachers ct join class_students cs on cs.class_id = ct.class_id
				where ct.teacher_id = :t and ct.status = 'ACTIVE' and cs.status = 'ACTIVE'""")
			.param("t", teacherId)
			.query(UUID.class)
			.list());
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, List<StudentClass>> activeClassesOf(Collection<UUID> studentIds) {
		Map<UUID, List<StudentClass>> result = new HashMap<>();
		if (studentIds.isEmpty()) {
			return result;
		}
		this.jdbc.sql("""
				select cs.student_id, c.id, c.name, c.grade_level_id from class_students cs join classes c on c.id = cs.class_id
				where cs.student_id in (:ids) and cs.status = 'ACTIVE' order by c.name, c.id""")
			.param("ids", studentIds)
			.query((rs, i) -> {
				result.computeIfAbsent(Rows.uuid(rs, "student_id"), (k) -> new java.util.ArrayList<>())
					.add(new StudentClass(Rows.uuid(rs, "id"), rs.getString("name"), Rows.uuid(rs, "grade_level_id")));
				return null;
			})
			.list();
		return result;
	}

}

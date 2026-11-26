package com.nemaz.school.academic.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.moderation.SchoolSuspension;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The class memberships of a school-level suspension (docs/Safeguarding_Rules.md S2.4): the person's ACTIVE class rows become
 * FORMER (a suspended teacher or student has no access to any class of the school), and a lift reactivates exactly those rows,
 * unless the person was put into the same class again in the meantime. Nothing is deleted.
 */
@Component
class AcademicSuspension implements SchoolSuspension {

	private final JdbcClient jdbc;

	private final Clock clock;

	AcademicSuspension(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional
	public Result suspend(UUID userId, UUID schoolId) {
		Timestamp now = Timestamp.from(this.clock.instant());
		List<UUID> teachers = this.jdbc.sql("""
				update class_teachers set status = 'FORMER', left_at = :now, updated_at = :now, version = version + 1
				where school_id = :s and teacher_id = :u and status = 'ACTIVE' returning id""")
			.param("now", now)
			.param("s", schoolId)
			.param("u", userId)
			.query(UUID.class)
			.list();
		List<UUID> students = this.jdbc.sql("""
				update class_students set status = 'FORMER', left_at = :now, left_reason = 'REMOVED', updated_at = :now, version = version + 1
				where school_id = :s and student_id = :u and status = 'ACTIVE' returning id""")
			.param("now", now)
			.param("s", schoolId)
			.param("u", userId)
			.query(UUID.class)
			.list();
		List<UUID> affected = new ArrayList<>(teachers);
		affected.addAll(students);
		return affected.isEmpty() ? Result.nothing() : new Result(true, affected);
	}

	@Override
	@Transactional
	public void lift(UUID userId, UUID schoolId, List<UUID> affected) {
		if (affected.isEmpty()) {
			return;
		}
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				update class_teachers t set status = 'ACTIVE', left_at = null, updated_at = :now, version = version + 1
				where t.id in (:ids) and t.status = 'FORMER'
				  and not exists (select 1 from class_teachers x where x.class_id = t.class_id and x.teacher_id = t.teacher_id
				                  and coalesce(x.subject_id, '00000000-0000-0000-0000-000000000000') = coalesce(t.subject_id, '00000000-0000-0000-0000-000000000000')
				                  and x.status in ('PENDING', 'ACTIVE'))""")
			.param("now", now)
			.param("ids", affected)
			.update();
		this.jdbc.sql("""
				update class_students t set status = 'ACTIVE', left_at = null, left_reason = null, updated_at = :now, version = version + 1
				where t.id in (:ids) and t.status = 'FORMER' and t.left_reason = 'REMOVED'
				  and not exists (select 1 from class_students x where x.class_id = t.class_id and x.student_id = t.student_id
				                  and x.status in ('PENDING', 'ACTIVE'))""")
			.param("now", now)
			.param("ids", affected)
			.update();
	}

}

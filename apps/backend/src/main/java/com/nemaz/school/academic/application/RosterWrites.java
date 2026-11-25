package com.nemaz.school.academic.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import com.nemaz.school.academic.ClassMembershipChanged;
import com.nemaz.school.academic.ClassMembershipChanged.Change;
import com.nemaz.school.academic.ClassMembershipChanged.MemberType;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Constraints;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The two student-roster writes every roster operation shares; each publishes {@link ClassMembershipChanged}. */
@Component
class RosterWrites {

	private final JdbcClient jdbc;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	RosterWrites(JdbcClient jdbc, ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.events = events;
		this.clock = clock;
	}

	/** Adds an ACTIVE membership; 409 {@code class.student_already_member} when one is live. */
	void addStudent(UUID schoolId, UUID classId, UUID studentId, UUID actorId) {
		try {
			this.jdbc.sql("""
					insert into class_students (id, class_id, school_id, student_id, status, joined_at, added_by)
					values (:id, :c, :s, :st, 'ACTIVE', :now, :by)""")
				.param("id", Ids.newId())
				.param("c", classId)
				.param("s", schoolId)
				.param("st", studentId)
				.param("now", Timestamp.from(this.clock.instant()))
				.param("by", actorId)
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			if (Constraints.violated(ex, "ux_class_student_active")) {
				throw ApiException.conflict("class.student_already_member");
			}
			throw ex;
		}
		this.events.publishEvent(new ClassMembershipChanged(classId, schoolId, studentId, MemberType.STUDENT, Change.JOINED,
				null, null, actorId));
	}

	/** Ends the live membership: REMOVED for {@code reason} REMOVED, otherwise FORMER. Returns 0 when there was none. */
	int endStudent(UUID schoolId, UUID classId, UUID studentId, String reason, UUID actorId) {
		String status = "REMOVED".equals(reason) ? "REMOVED" : "FORMER";
		int ended = this.jdbc.sql("""
				update class_students set status = :status, left_at = :now, left_reason = :reason, updated_at = now(),
				       version = version + 1
				where class_id = :c and student_id = :st and status in ('PENDING', 'ACTIVE')""")
			.param("status", status)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("reason", reason)
			.param("c", classId)
			.param("st", studentId)
			.update();
		if (ended > 0) {
			this.events.publishEvent(new ClassMembershipChanged(classId, schoolId, studentId, MemberType.STUDENT,
					Change.LEFT, null, reason, actorId));
		}
		return ended;
	}

}

package com.nemaz.school.calendar.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.calendar.application.EventRows.EventRow;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who is told about an event (notifications and reminders). Not the same as who may see it: the audience is guardians
 * who allow notifications (ACTIVE link, not custody-restricted, {@code can_receive_notifications}) of the ACTIVE students it
 * concerns and the staff it concerns; students are not pushed (they find events in the calendar). Resolved when needed, so
 * a link revoked a minute ago is already out.
 */
@Component
class EventAudiences {

	private final JdbcClient jdbc;

	EventAudiences(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Guardians and staff of a SCHOOL, CAMPUS, GRADE_LEVEL or CLASS event; empty for the other scopes. */
	Set<UUID> audience(EventRow event) {
		String guardianScope;
		String staffScope;
		switch (event.scope()) {
			case SCHOOL -> {
				guardianScope = "";
				staffScope = "";
			}
			case CAMPUS -> {
				guardianScope = """
						 and (ss.campus_id = :scope or exists (select 1 from class_students cs
						        join classes c on c.id = cs.class_id and c.status = 'ACTIVE'
						        where cs.student_id = ss.student_id and cs.status = 'ACTIVE' and c.campus_id = :scope))""";
				staffScope = """
						 and (st.campus_id = :scope or st.campus_id is null or st.user_id in (
						        select ct.teacher_id from class_teachers ct join classes c on c.id = ct.class_id and c.status = 'ACTIVE'
						        where ct.status = 'ACTIVE' and c.campus_id = :scope))""";
			}
			case GRADE_LEVEL -> {
				guardianScope = """
						 and exists (select 1 from class_students cs join classes c on c.id = cs.class_id and c.status = 'ACTIVE'
						        where cs.student_id = ss.student_id and cs.status = 'ACTIVE' and c.grade_level_id = :scope)""";
				staffScope = """
						 and st.user_id in (select ct.teacher_id from class_teachers ct
						        join classes c on c.id = ct.class_id and c.status = 'ACTIVE'
						        where ct.status = 'ACTIVE' and c.grade_level_id = :scope)""";
			}
			case CLASS -> {
				guardianScope = """
						 and exists (select 1 from class_students cs where cs.student_id = ss.student_id and cs.status = 'ACTIVE'
						        and cs.class_id = :scope)""";
				staffScope = """
						 and st.user_id in (select ct.teacher_id from class_teachers ct where ct.status = 'ACTIVE'
						        and ct.class_id = :scope)""";
			}
			default -> {
				return Set.of();
			}
		}
		UUID scopeId = switch (event.scope()) {
			case CAMPUS -> event.campusId();
			case GRADE_LEVEL -> event.gradeLevelId();
			case CLASS -> event.classId();
			default -> event.schoolId();
		};
		Set<UUID> result = new LinkedHashSet<>();
		result.addAll(this.jdbc.sql("""
				select distinct sg.guardian_id from student_guardians sg
				join school_students ss on ss.student_id = sg.student_id and ss.status = 'ACTIVE' and ss.school_id = :school
				where sg.status = 'ACTIVE' and not sg.custody_restricted and sg.can_receive_notifications""" + " " + guardianScope)
			.param("school", event.schoolId())
			.param("scope", scopeId)
			.query(UUID.class)
			.list());
		result.addAll(this.jdbc.sql("""
				select distinct st.user_id from school_staff st where st.school_id = :school and st.status = 'ACTIVE'""" + " " + staffScope)
			.param("school", event.schoolId())
			.param("scope", scopeId)
			.query(UUID.class)
			.list());
		return result;
	}

	/**
	 * The people of a parent meeting who may still see it: the organiser and the invited guardians whose link is ACTIVE, not
	 * custody-restricted and allows academics now.
	 */
	Set<UUID> participants(EventRow event) {
		return new LinkedHashSet<>(this.jdbc.sql("""
				select p.user_id from event_participants p
				where p.event_id = :event and (p.role = 'ORGANIZER' or exists (
				  select 1 from student_guardians sg
				  join school_students ss on ss.student_id = sg.student_id and ss.status = 'ACTIVE' and ss.school_id = :school
				  where sg.student_id = :student and sg.guardian_id = p.user_id and sg.status = 'ACTIVE'
				    and not sg.custody_restricted and sg.can_view_academics))""")
			.param("event", event.id())
			.param("school", event.schoolId())
			.param("student", event.regardingStudentId())
			.query(UUID.class)
			.list());
	}

	/** The people who answered one of the given answers. */
	Set<UUID> answered(UUID eventId, List<String> statuses) {
		return new LinkedHashSet<>(
				this.jdbc.sql("select user_id from event_participants where event_id = :event and rsvp_status in (:statuses)")
					.param("event", eventId)
					.param("statuses", statuses)
					.query(UUID.class)
					.list());
	}

}

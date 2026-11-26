package com.nemaz.school.communications.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementPreview;
import com.nemaz.school.communications.application.AnnouncementModels.RecipientRole;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who an announcement reaches. One SQL statement (kept in this class so the rule has one home), from the audience rows
 * to people:
 * <ol>
 * <li><b>Students</b> reached by a SCHOOL, CAMPUS, GRADE_LEVEL or CLASS audience: ACTIVE in {@code school_students} of
 * the announcement's school and, for the narrower audiences, ACTIVE in an ACTIVE class of that campus, grade or class (a
 * CAMPUS audience also counts students enrolled at the campus without a class).</li>
 * <li><b>Guardians</b> (role GUARDIAN): the ACTIVE, not custody-restricted links of those students that carry
 * {@code can_receive_notifications}; PENDING, REJECTED and REVOKED links and restricted guardians never count. A guardian
 * of several of those students is one recipient, {@code via_student_id} the first child (smallest id, the oldest).</li>
 * <li><b>Students</b> (role STUDENT) themselves, only when the school allows announcements to students.</li>
 * <li><b>Staff</b> (role STAFF): ACTIVE school staff (SCHOOL), staff of the campus or teachers of its classes (CAMPUS),
 * ACTIVE teachers of the classes of the grade (GRADE_LEVEL) or of the class (CLASS), in ACTIVE classes.</li>
 * <li><b>USER</b> audiences: the named people, for whatever they are in this school (staff, student when allowed,
 * eligible guardian); anybody not related to the school resolves to nothing.</li>
 * </ol>
 * Only ACTIVE accounts, never the author, one row per person (STAFF before GUARDIAN before STUDENT when someone is
 * several). {@code AnnouncementAccess} answers the same question from the person's side for reading; a test keeps the two
 * identical.
 */
@Component
class AudienceResolver {

	/** Parameters: {@code ann} (announcement id), {@code students_enabled}, {@code only} (a user id or null). */
	static final String RESOLVE = """
			with ann as (select id, school_id, author_id from announcements where id = :ann),
			aud as (select a.* from announcement_audiences a where a.announcement_id = :ann),
			stu as (
			  select ss.student_id, a.recipient_roles
			  from aud a
			  join ann on true
			  join school_students ss on ss.school_id = ann.school_id and ss.status = 'ACTIVE'
			  where a.audience_type <> 'USER' and (
			       a.audience_type = 'SCHOOL'
			    or (a.audience_type = 'CAMPUS' and (ss.campus_id = a.campus_id or exists (
			          select 1 from class_students cs join classes c on c.id = cs.class_id and c.status = 'ACTIVE'
			          where cs.student_id = ss.student_id and cs.status = 'ACTIVE' and cs.school_id = ann.school_id
			            and c.campus_id = a.campus_id)))
			    or (a.audience_type = 'GRADE_LEVEL' and exists (
			          select 1 from class_students cs join classes c on c.id = cs.class_id and c.status = 'ACTIVE'
			          where cs.student_id = ss.student_id and cs.status = 'ACTIVE' and cs.school_id = ann.school_id
			            and c.grade_level_id = a.grade_level_id))
			    or (a.audience_type = 'CLASS' and exists (
			          select 1 from class_students cs join classes c on c.id = cs.class_id and c.status = 'ACTIVE'
			          where cs.student_id = ss.student_id and cs.status = 'ACTIVE' and cs.school_id = ann.school_id
			            and cs.class_id = a.class_id)))
			),
			people as (
			  select sg.guardian_id as user_id, 'GUARDIAN' as role, sg.student_id as via
			  from stu join student_guardians sg on sg.student_id = stu.student_id
			  where 'GUARDIAN' = any(stu.recipient_roles) and sg.status = 'ACTIVE' and not sg.custody_restricted
			    and sg.can_receive_notifications
			  union all
			  select stu.student_id, 'STUDENT', null::uuid from stu
			  where 'STUDENT' = any(stu.recipient_roles) and :students_enabled
			  union all
			  select st.user_id, 'STAFF', null::uuid
			  from aud a
			  join ann on true
			  join school_staff st on st.school_id = ann.school_id and st.status = 'ACTIVE'
			  where a.audience_type <> 'USER' and 'STAFF' = any(a.recipient_roles) and (
			       a.audience_type = 'SCHOOL'
			    or (a.audience_type = 'CAMPUS' and (st.campus_id = a.campus_id or exists (
			          select 1 from class_teachers ct join classes c on c.id = ct.class_id and c.status = 'ACTIVE'
			          where ct.teacher_id = st.user_id and ct.status = 'ACTIVE' and ct.school_id = ann.school_id
			            and c.campus_id = a.campus_id)))
			    or (a.audience_type = 'GRADE_LEVEL' and exists (
			          select 1 from class_teachers ct join classes c on c.id = ct.class_id and c.status = 'ACTIVE'
			          where ct.teacher_id = st.user_id and ct.status = 'ACTIVE' and ct.school_id = ann.school_id
			            and c.grade_level_id = a.grade_level_id))
			    or (a.audience_type = 'CLASS' and exists (
			          select 1 from class_teachers ct join classes c on c.id = ct.class_id and c.status = 'ACTIVE'
			          where ct.teacher_id = st.user_id and ct.status = 'ACTIVE' and ct.school_id = ann.school_id
			            and ct.class_id = a.class_id)))
			  union all
			  select a.user_id, 'GUARDIAN', sg.student_id
			  from aud a
			  join ann on true
			  join student_guardians sg on sg.guardian_id = a.user_id and sg.status = 'ACTIVE' and not sg.custody_restricted
			                           and sg.can_receive_notifications
			  join school_students ss on ss.student_id = sg.student_id and ss.school_id = ann.school_id and ss.status = 'ACTIVE'
			  where a.audience_type = 'USER'
			  union all
			  select a.user_id, 'STUDENT', null::uuid
			  from aud a
			  join ann on true
			  join school_students ss on ss.student_id = a.user_id and ss.school_id = ann.school_id and ss.status = 'ACTIVE'
			  where a.audience_type = 'USER' and :students_enabled
			  union all
			  select a.user_id, 'STAFF', null::uuid
			  from aud a
			  join ann on true
			  join school_staff st on st.user_id = a.user_id and st.school_id = ann.school_id and st.status = 'ACTIVE'
			  where a.audience_type = 'USER'
			)
			select distinct on (p.user_id) p.user_id, p.role, p.via as via_student_id
			from people p
			join ann on true
			join users u on u.id = p.user_id and u.status = 'ACTIVE' and u.deleted_at is null
			where p.user_id <> ann.author_id and (cast(:only as uuid) is null or p.user_id = cast(:only as uuid))
			order by p.user_id, case p.role when 'STAFF' then 0 when 'GUARDIAN' then 1 else 2 end, p.via""";

	private final JdbcClient jdbc;

	AudienceResolver(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Writes the snapshot of the announcement; returns how many recipients it has. */
	int snapshot(UUID announcementId, boolean studentsEnabled) {
		return this.jdbc.sql("""
				insert into announcement_recipients (announcement_id, school_id, user_id, recipient_role, via_student_id)
				select :ann, (select school_id from announcements where id = :ann), r.user_id, r.role, r.via_student_id
				from (""" + RESOLVE + """
				) r""")
			.param("ann", announcementId)
			.param("students_enabled", studentsEnabled)
			.param("only", null, java.sql.Types.OTHER)
			.update();
	}

	/** How many people, by role, the announcement would reach now (the publish preview). */
	AnnouncementPreview preview(UUID announcementId, boolean studentsEnabled) {
		List<String> roles = this.jdbc.sql("select r.role from (" + RESOLVE + ") r")
			.param("ann", announcementId)
			.param("students_enabled", studentsEnabled)
			.param("only", null, java.sql.Types.OTHER)
			.query(String.class)
			.list();
		int guardians = (int) roles.stream().filter(RecipientRole.GUARDIAN.name()::equals).count();
		int students = (int) roles.stream().filter(RecipientRole.STUDENT.name()::equals).count();
		int staff = (int) roles.stream().filter(RecipientRole.STAFF.name()::equals).count();
		return new AnnouncementPreview(roles.size(), guardians, students, staff);
	}

	/** The role and first child through which {@code userId} would be a recipient now; empty when they would not be. */
	java.util.Optional<String> roleOf(UUID announcementId, UUID userId, boolean studentsEnabled) {
		return this.jdbc.sql("select r.role from (" + RESOLVE + ") r")
			.param("ann", announcementId)
			.param("students_enabled", studentsEnabled)
			.param("only", userId)
			.query(String.class)
			.optional();
	}

}

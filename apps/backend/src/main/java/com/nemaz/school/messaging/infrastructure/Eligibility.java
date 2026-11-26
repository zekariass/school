package com.nemaz.school.messaging.infrastructure;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.messaging.infrastructure.MessagingSettings.SchoolPolicy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * <b>The live eligibility rules of messaging (rule book R1), as SQL, in one place.</b> A membership row is never enough: for
 * every read and every write the person and the conversation must pass these predicates <em>now</em>. The same text is used by
 * the inbox, the message lists, the single-conversation check, the delivery of notifications and the observers' check, so
 * the rules cannot drift apart.
 *
 * <p>
 * The predicates are written over the aliases {@code c} (conversations), a member alias passed in, and {@code sp} (the
 * school's settings, one row per school from {@link #statement}'s {@code with sp as (values ...)} prefix) plus the parameter
 * {@code :el_today}. A <b>member</b> passes when the account and school are ACTIVE and, by capacity:
 * <ul>
 * <li>OBSERVER: an ACTIVE, not custody-restricted guardian link to the conversation's student, who is an ACTIVE student of the
 * school (no message flag needed: observing protects the child);</li>
 * <li>STUDENT: ACTIVE student of the school, student messaging on, age limit met (an unknown date of birth fails a limit),
 * ACTIVE in the conversation's class when it has one;</li>
 * <li>GUARDIAN: an ACTIVE, not custody-restricted link with {@code can_message_staff} to an ACTIVE student of the school who
 * is the thread's child (guardian-teacher), the member's own child (guardian-guardian) or ACTIVE in the class (parent group);</li>
 * <li>TEACHER: ACTIVE teacher staff of the school who teaches the thread's child, or the conversation's class, as LEAD,
 * CO_TEACHER or SUBJECT_TEACHER (assistants are not participants);</li>
 * <li>STAFF: ACTIVE staff of the school.</li>
 * </ul>
 * The <b>conversation</b> passes when it is ACTIVE, LOCKED or ARCHIVED, the school is ACTIVE and, for a DIRECT conversation,
 * both participants pass, the setting of its kind is on and (guardian-guardian, student-student) the two share an ACTIVE
 * class; a parent group needs {@code messaging.parent_teacher.enabled}.
 */
@Component
public class Eligibility {

	private static final String TEACHES_THREAD_STUDENT = """
			exists (select 1 from class_teachers ct
			        join class_students cs on cs.class_id = ct.class_id and cs.status = 'ACTIVE'
			        where ct.teacher_id = %1$s.user_id and ct.school_id = c.school_id and ct.status = 'ACTIVE'
			          and ct.role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER') and cs.student_id = c.context_student_id)""";

	private static final String TEACHES_CLASS = """
			exists (select 1 from class_teachers ct
			        where ct.class_id = c.class_id and ct.teacher_id = %1$s.user_id and ct.status = 'ACTIVE'
			          and ct.role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER'))""";

	private static final String MEMBER = """
			(%1$s.left_at is null
			 and exists (select 1 from users eu where eu.id = %1$s.user_id and eu.status = 'ACTIVE' and eu.deleted_at is null)
			 and case
			   when %1$s.member_role = 'OBSERVER' then
			     c.context_student_id is not null
			     and exists (select 1 from school_students ss where ss.school_id = c.school_id
			                 and ss.student_id = c.context_student_id and ss.status = 'ACTIVE')
			     and exists (select 1 from student_guardians g where g.guardian_id = %1$s.user_id
			                 and g.student_id = c.context_student_id and g.status = 'ACTIVE' and not g.custody_restricted)
			   when %1$s.acting_as = 'STUDENT' then
			     sp.students_enabled
			     and exists (select 1 from school_students ss where ss.school_id = c.school_id
			                 and ss.student_id = %1$s.user_id and ss.status = 'ACTIVE')
			     and (sp.min_age is null or exists (select 1 from users du where du.id = %1$s.user_id
			                 and du.date_of_birth is not null
			                 and du.date_of_birth <= (cast(:el_today as date) - make_interval(years => sp.min_age))))
			     and (c.class_id is null or exists (select 1 from class_students cs where cs.class_id = c.class_id
			                 and cs.student_id = %1$s.user_id and cs.status = 'ACTIVE'))
			   when %1$s.acting_as = 'GUARDIAN' then
			     exists (select 1 from student_guardians g
			             where g.guardian_id = %1$s.user_id and g.status = 'ACTIVE' and not g.custody_restricted
			               and g.can_message_staff
			               and exists (select 1 from school_students ss where ss.school_id = c.school_id
			                           and ss.student_id = g.student_id and ss.status = 'ACTIVE')
			               and case
			                 when c.direct_kind = 'GUARDIAN_TEACHER' then g.student_id = c.context_student_id
			                 when c.direct_kind = 'GUARDIAN_GUARDIAN' then g.student_id = %1$s.on_behalf_of_student_id
			                 when c.group_kind = 'CLASS_PARENTS' then exists (select 1 from class_students cs
			                      where cs.class_id = c.class_id and cs.student_id = g.student_id and cs.status = 'ACTIVE')
			                 else false end)
			   when %1$s.acting_as = 'TEACHER' then
			     exists (select 1 from school_staff st where st.school_id = c.school_id and st.user_id = %1$s.user_id
			             and st.status = 'ACTIVE' and st.staff_type = 'TEACHER')
			     and case
			       when c.direct_kind in ('GUARDIAN_TEACHER', 'TEACHER_STUDENT') then
			         """ + TEACHES_THREAD_STUDENT + """

			       when c.class_id is not null then
			         """ + TEACHES_CLASS + """

			       else true end
			   when %1$s.acting_as = 'STAFF' then
			     exists (select 1 from school_staff st where st.school_id = c.school_id and st.user_id = %1$s.user_id
			             and st.status = 'ACTIVE')
			   else false end)""";

	private static final String PAIR_SHARES_CLASS_AS_GUARDIANS = """
			exists (select 1 from conversation_members pa
			        join conversation_members pb on pb.conversation_id = pa.conversation_id and pb.id <> pa.id
			        join class_students ca on ca.student_id = pa.on_behalf_of_student_id and ca.status = 'ACTIVE'
			        join class_students cb on cb.class_id = ca.class_id and cb.student_id = pb.on_behalf_of_student_id
			                                  and cb.status = 'ACTIVE'
			        where pa.conversation_id = c.id and pa.left_at is null and pb.left_at is null
			          and pa.member_role <> 'OBSERVER' and pb.member_role <> 'OBSERVER')""";

	private static final String PAIR_SHARES_CLASS_AS_STUDENTS = """
			exists (select 1 from conversation_members pa
			        join conversation_members pb on pb.conversation_id = pa.conversation_id and pb.id <> pa.id
			        join class_students ca on ca.student_id = pa.user_id and ca.status = 'ACTIVE'
			        join class_students cb on cb.class_id = ca.class_id and cb.student_id = pb.user_id and cb.status = 'ACTIVE'
			        where pa.conversation_id = c.id and pa.left_at is null and pb.left_at is null
			          and pa.member_role <> 'OBSERVER' and pb.member_role <> 'OBSERVER')""";

	/** A conversation with students needs at least two of them: with one it would be a private conversation without the floor. */
	private static final String AT_LEAST_TWO_STUDENTS = """
			(select count(*) from conversation_members sm where sm.conversation_id = c.id and sm.left_at is null
			                                                     and sm.acting_as = 'STUDENT'
			                                                     and exists (select 1 from class_students scs where scs.class_id = c.class_id
			                                                                 and scs.student_id = sm.user_id and scs.status = 'ACTIVE')) >= 2""";

	private static final String CONVERSATION = "(c.status in ('ACTIVE', 'LOCKED', 'ARCHIVED') and sp.feature_enabled\n"
			+ " and exists (select 1 from schools es where es.id = c.school_id and es.status = 'ACTIVE')\n"
			+ " and case c.conversation_type\n"
			+ "   when 'DIRECT' then\n"
			+ "     (select count(*) from conversation_members d where d.conversation_id = c.id and d.left_at is null"
			+ " and d.member_role <> 'OBSERVER') = 2\n"
			+ "     and not exists (select 1 from conversation_members d where d.conversation_id = c.id and d.left_at is null"
			+ " and d.member_role <> 'OBSERVER' and not " + MEMBER.formatted("d") + ")\n"
			+ "     and case c.direct_kind\n"
			+ "       when 'GUARDIAN_TEACHER' then sp.parent_teacher_enabled\n"
			+ "       when 'GUARDIAN_GUARDIAN' then sp.parent_parent_enabled and " + PAIR_SHARES_CLASS_AS_GUARDIANS + "\n"
			+ "       when 'TEACHER_STUDENT' then sp.teacher_direct_enabled\n"
			+ "       when 'STUDENT_STUDENT' then sp.student_direct_enabled and " + PAIR_SHARES_CLASS_AS_STUDENTS + "\n"
			+ "       else false end\n"
			+ "   when 'CLASS' then (c.group_kind <> 'CLASS_PARENTS' or sp.parent_teacher_enabled)"
			+ " and (c.group_kind <> 'CLASS_STUDENTS' or " + AT_LEAST_TWO_STUDENTS + ")\n"
			+ "   when 'GROUP' then c.group_kind <> 'STUDENT_GROUP' or " + AT_LEAST_TWO_STUDENTS + "\n"
			+ "   else true end)";

	private final JdbcClient jdbc;

	private final MessagingSettings settings;

	private final Clock clock;

	Eligibility(JdbcClient jdbc, MessagingSettings settings, Clock clock) {
		this.jdbc = jdbc;
		this.settings = settings;
		this.clock = clock;
	}

	/** The member predicate for the member alias {@code alias} (needs {@code c}, {@code sp} and {@code :el_today}). */
	public static String member(String alias) {
		return MEMBER.formatted(alias);
	}

	/** The conversation predicate (needs {@code c} and {@code sp}). */
	public static String conversation() {
		return CONVERSATION;
	}

	/** A member who may use the conversation now: {@link #member} and {@link #conversation}. */
	public static String accessible(String alias) {
		return "(" + member(alias) + " and " + CONVERSATION + ")";
	}

	/**
	 * True for a safeguarded (adult-student DIRECT) conversation that no eligible observer can read right now: it is
	 * {@code admin_visible} and its reviewers may read it through the audited endpoint. Live, so a restricted guardian counts at once.
	 */
	public static String effectiveAdminVisible() {
		return "(c.safeguarded and not exists (select 1 from conversation_members ob where ob.conversation_id = c.id"
				+ " and ob.member_role = 'OBSERVER' and " + accessible("ob") + "))";
	}

	/** Conversations a reviewer may read: admin-visible ones and those with an open report. */
	public static String reviewable() {
		return "(" + effectiveAdminVisible()
				+ " or exists (select 1 from message_reports rr where rr.conversation_id = c.id and rr.status = 'OPEN'))";
	}

	/** A statement whose SQL gets the {@code sp} (settings of {@code schoolIds}) prefix and the date parameter bound. */
	public JdbcClient.StatementSpec statement(Collection<UUID> schoolIds, String sql) {
		List<SchoolPolicy> policies = this.settings.policies(schoolIds);
		Map<String, Object> params = new LinkedHashMap<>();
		StringBuilder cte = new StringBuilder("with sp(school_id, students_enabled, min_age, parent_teacher_enabled, "
				+ "parent_parent_enabled, teacher_direct_enabled, student_direct_enabled, feature_enabled) as (");
		if (policies.isEmpty()) {
			cte.append("select cast(null as uuid), false, cast(null as integer), false, false, false, false, false where false");
		}
		else {
			cte.append("values ");
			for (int i = 0; i < policies.size(); i++) {
				SchoolPolicy p = policies.get(i);
				if (i > 0) {
					cte.append(", ");
				}
				cte.append("(cast(:sp%d_id as uuid), cast(:sp%d_se as boolean), cast(:sp%d_ma as integer), ".formatted(i, i, i))
					.append("cast(:sp%d_pt as boolean), cast(:sp%d_pp as boolean), cast(:sp%d_td as boolean), cast(:sp%d_sd as boolean), cast(:sp%d_fe as boolean))"
						.formatted(i, i, i, i, i));
				params.put("sp" + i + "_id", p.schoolId());
				params.put("sp" + i + "_se", p.studentsEnabled());
				params.put("sp" + i + "_ma", p.minAge());
				params.put("sp" + i + "_pt", p.parentTeacher());
				params.put("sp" + i + "_pp", p.parentParent());
				params.put("sp" + i + "_td", p.teacherDirect());
				params.put("sp" + i + "_sd", p.studentDirect());
				params.put("sp" + i + "_fe", p.featureEnabled());
			}
		}
		cte.append(") ");
		params.put("el_today", LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC));
		return this.jdbc.sql(cte + sql).params(params);
	}

	/** The schools of the user's live memberships (to build the settings prefix of an inbox query). */
	public List<UUID> schoolsOf(UUID userId) {
		return this.jdbc.sql("select distinct school_id from conversation_members where user_id = :u and left_at is null")
			.param("u", userId)
			.query(UUID.class)
			.list();
	}

	/** Whether {@code userId}'s live membership of the conversation passes the rules now. */
	public boolean isAccessible(UUID conversationId, UUID userId) {
		UUID school = this.jdbc.sql("select school_id from conversations where id = :c")
			.param("c", conversationId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (school == null) {
			return false;
		}
		return statement(List.of(school), """
				select exists (select 1 from conversations c
				               join conversation_members m on m.conversation_id = c.id and m.user_id = :u and m.left_at is null
				               join sp on sp.school_id = c.school_id
				               where c.id = :c and %s)""".formatted(accessible("m")))
			.param("u", userId)
			.param("c", conversationId)
			.query(Boolean.class)
			.single();
	}

}

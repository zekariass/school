package com.nemaz.school.messaging.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.messaging.domain.GroupKind;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the membership of class conversations equal to the class roster (rule book R6.4). {@link #reconcileClass} computes who
 * should be in each class conversation from the roster and the guardian links, then adds the missing and ends the extra
 * members, so it is idempotent by construction: running it twice, or after a redelivered event, changes nothing the second time.
 * <ul>
 * <li>CLASS_STUDENTS: ACTIVE students of the class and its LEAD, CO_TEACHER and SUBJECT_TEACHER teachers;</li>
 * <li>CLASS_PARENTS: guardians (ACTIVE, not custody-restricted, {@code can_message_staff}) of ACTIVE students of the class and the same teachers;</li>
 * <li>STUDENT_GROUP: nobody is added, members who left the class are ended.</li>
 * </ul>
 * LEAD and CO_TEACHER are moderators, the creator is the owner. A member a moderator removed (or who left) is never added back.
 * The rows are a convenience (counts, notifications): what a person may read is decided by the live eligibility check.
 */
@Component
public class RosterSync {

	private final JdbcClient jdbc;

	private final Clock clock;

	RosterSync(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	private record Want(UUID userId, String acting, String role, UUID onBehalf) {
	}

	/** Reconciles every class-bound conversation of the class; returns how many conversations changed. */
	@Transactional
	public int reconcileClass(UUID classId) {
		record Conv(UUID id, UUID schoolId, GroupKind kind, UUID createdBy) {
		}
		List<Conv> convs = this.jdbc.sql("""
				select id, school_id, group_kind, created_by from conversations
				where class_id = :c and group_kind in ('CLASS_STUDENTS', 'CLASS_PARENTS', 'STUDENT_GROUP') and status <> 'REJECTED'
				order by id""")
			.param("c", classId)
			.query((rs, i) -> new Conv(rs.getObject("id", UUID.class), rs.getObject("school_id", UUID.class),
					GroupKind.valueOf(rs.getString("group_kind")), rs.getObject("created_by", UUID.class)))
			.list();
		int changed = 0;
		for (Conv conv : convs) {
			changed += reconcile(conv.id(), conv.schoolId(), classId, conv.kind(), conv.createdBy());
		}
		return changed;
	}

	/** Reconciles the class conversation(s) of every class the student is ACTIVE in (a guardian link of theirs changed). */
	@Transactional
	public int reconcileClassesOfStudent(UUID studentId) {
		List<UUID> classes = this.jdbc.sql("""
				select distinct cs.class_id from class_students cs where cs.student_id = :s and cs.status = 'ACTIVE'
				union
				select distinct c.class_id from conversations c join conversation_members m on m.conversation_id = c.id
				where m.on_behalf_of_student_id = :s and c.class_id is not null""")
			.param("s", studentId)
			.query(UUID.class)
			.list();
		int changed = 0;
		for (UUID classId : classes) {
			changed += reconcileClass(classId);
		}
		return changed;
	}

	/** Fills (or corrects) one class conversation right after its creation. */
	@Transactional
	public int reconcileConversation(UUID conversationId) {
		return this.jdbc.sql("select school_id, class_id, group_kind, created_by from conversations where id = :c and class_id is not null")
			.param("c", conversationId)
			.query((rs, i) -> reconcile(conversationId, rs.getObject("school_id", UUID.class),
					rs.getObject("class_id", UUID.class), GroupKind.valueOf(rs.getString("group_kind")),
					rs.getObject("created_by", UUID.class)))
			.optional()
			.orElse(0);
	}

	private int reconcile(UUID conversationId, UUID schoolId, UUID classId, GroupKind kind, UUID createdBy) {
		Map<UUID, Want> wanted = (kind == GroupKind.STUDENT_GROUP) ? Map.of() : desired(schoolId, classId, kind, createdBy);
		record Existing(UUID id, UUID userId, String role, String acting) {
		}
		List<Existing> existing = this.jdbc.sql("""
				select id, user_id, member_role, acting_as from conversation_members where conversation_id = :c and left_at is null""")
			.param("c", conversationId)
			.query((rs, i) -> new Existing(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
					rs.getString("member_role"), rs.getString("acting_as")))
			.list();
		Map<UUID, Existing> have = new LinkedHashMap<>();
		existing.forEach((e) -> have.put(e.userId(), e));
		Map<UUID, Boolean> stickyLeavers = new LinkedHashMap<>();
		this.jdbc.sql("""
				select distinct user_id from conversation_members
				where conversation_id = :c and left_at is not null and left_reason in ('REMOVED', 'LEFT')""")
			.param("c", conversationId)
			.query(UUID.class)
			.list()
			.forEach((u) -> stickyLeavers.put(u, true));
		var now = Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS));
		int changes = 0;
		for (Want want : wanted.values()) {
			Existing e = have.get(want.userId());
			if (e == null) {
				if (stickyLeavers.containsKey(want.userId())) {
					continue;
				}
				changes += this.jdbc.sql("""
						insert into conversation_members (id, conversation_id, school_id, user_id, member_role, acting_as,
						                                  on_behalf_of_student_id, joined_at)
						values (:id, :c, :s, :u, :role, :acting, :st, :now)
						on conflict (conversation_id, user_id) where left_at is null do nothing""")
					.param("id", Ids.newId())
					.param("c", conversationId)
					.param("s", schoolId)
					.param("u", want.userId())
					.param("role", want.role())
					.param("acting", want.acting())
					.param("st", want.onBehalf())
					.param("now", now)
					.update();
			}
			else if (!e.role().equals(want.role()) && !"OBSERVER".equals(e.role())) {
				changes += this.jdbc.sql("update conversation_members set member_role = :role where id = :id")
					.param("role", want.role())
					.param("id", e.id())
					.update();
			}
		}
		for (Existing e : existing) {
			if (kind == GroupKind.STUDENT_GROUP) {
				if (leftTheClass(e.userId(), e.acting(), schoolId, classId)) {
					changes += end(e.id(), now);
				}
			}
			else if (!wanted.containsKey(e.userId())) {
				changes += end(e.id(), now);
			}
		}
		return changes;
	}

	private int end(UUID memberId, Timestamp now) {
		return this.jdbc.sql("update conversation_members set left_at = :now, left_reason = 'ROSTER' where id = :id and left_at is null")
			.param("now", now)
			.param("id", memberId)
			.update();
	}

	private boolean leftTheClass(UUID userId, String acting, UUID schoolId, UUID classId) {
		String sql = "STUDENT".equals(acting)
				? "select not exists (select 1 from class_students where class_id = :c and student_id = :u and status = 'ACTIVE')"
				: "select not exists (select 1 from class_teachers where class_id = :c and teacher_id = :u and status = 'ACTIVE' and role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER'))";
		return this.jdbc.sql(sql).param("c", classId).param("u", userId).query(Boolean.class).single();
	}

	private Map<UUID, Want> desired(UUID schoolId, UUID classId, GroupKind kind, UUID createdBy) {
		Map<UUID, Want> wanted = new LinkedHashMap<>();
		this.jdbc.sql("""
				select teacher_id, role from class_teachers
				where class_id = :c and status = 'ACTIVE' and role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER')
				order by teacher_id""")
			.param("c", classId)
			.query((rs, i) -> {
				UUID teacher = rs.getObject("teacher_id", UUID.class);
				String role = rs.getString("role");
				String memberRole = teacher.equals(createdBy) ? "OWNER" : ("SUBJECT_TEACHER".equals(role) ? "MEMBER" : "MODERATOR");
				Want previous = wanted.get(teacher);
				if (previous == null || "MEMBER".equals(previous.role())) {
					wanted.put(teacher, new Want(teacher, "TEACHER", memberRole, null));
				}
				return 0;
			})
			.list();
		if (kind == GroupKind.CLASS_STUDENTS) {
			this.jdbc.sql("""
					select cs.student_id from class_students cs where cs.class_id = :c and cs.status = 'ACTIVE' order by cs.student_id""")
				.param("c", classId)
				.query(UUID.class)
				.list()
				.forEach((student) -> wanted.putIfAbsent(student, new Want(student, "STUDENT", "MEMBER", null)));
		}
		else {
			this.jdbc.sql("""
					select distinct on (g.guardian_id) g.guardian_id, g.student_id from student_guardians g
					join class_students cs on cs.student_id = g.student_id and cs.class_id = :c and cs.status = 'ACTIVE'
					join school_students ss on ss.student_id = g.student_id and ss.school_id = :s and ss.status = 'ACTIVE'
					where g.status = 'ACTIVE' and not g.custody_restricted and g.can_message_staff
					order by g.guardian_id, g.is_primary desc, g.student_id""")
				.param("c", classId)
				.param("s", schoolId)
				.query((rs, i) -> {
					UUID guardian = rs.getObject("guardian_id", UUID.class);
					wanted.putIfAbsent(guardian, new Want(guardian, "GUARDIAN", "MEMBER", rs.getObject("student_id", UUID.class)));
					return 0;
				})
				.list();
		}
		return wanted;
	}

}

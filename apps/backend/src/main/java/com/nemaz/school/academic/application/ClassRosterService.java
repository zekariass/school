package com.nemaz.school.academic.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.DataAccessLevel;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMembershipChanged;
import com.nemaz.school.academic.ClassMembershipChanged.Change;
import com.nemaz.school.academic.ClassMembershipChanged.MemberType;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.academic.SchoolPeople.StudentPersonal;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Constraints;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.media.MediaSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class rosters: students (add, remove, move between classes), teachers (lead, co-teachers, subject teachers,
 * assistants) and saved student groups.
 * <ul>
 * <li><b>Students:</b> any teacher of the class or a manager adds, removes and moves students. A student must be
 * an ACTIVE student of the school. History is kept: leaving ends the row (REMOVED, or FORMER with a reason), it is
 * never deleted. A <em>move</em> ends the old membership as FORMER/TRANSFERRED_CLASS and starts the new ACTIVE one
 * in one transaction, both classes locked.</li>
 * <li><b>Teachers:</b> the class's LEAD or a manager adds and removes teachers; any teacher may leave
 * herself unless she is the LEAD. The last LEAD cannot be removed (409 {@code class.last_lead}).</li>
 * <li><b>Privacy:</b> the roster is shown only to the class's ACTIVE students and teachers and to managers
 * ({@link ClassAccess#canViewRoster}); guardians of a student see the class and its teachers but get 404 for the
 * roster. Date of birth, gender and username are shown only to teachers of the class and holders of
 * {@code school.students.manage}; everybody else who can see the roster gets name and avatar.</li>
 * <li>Rosters of archived classes cannot change.</li>
 * </ul>
 */
@Service
@Transactional
public class ClassRosterService {

	static final int MAX_ROSTER = 500;

	private final JdbcClient jdbc;

	private final ClassAccess access;

	private final ClassLookups lookups;

	private final SchoolGate gate;

	private final SchoolPeople people;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final RosterWrites writes;

	private final Clock clock;

	ClassRosterService(JdbcClient jdbc, ClassAccess access, ClassLookups lookups, SchoolGate gate, SchoolPeople people,
			AuditLog audit, ApplicationEventPublisher events, RosterWrites writes, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.lookups = lookups;
		this.gate = gate;
		this.people = people;
		this.audit = audit;
		this.events = events;
		this.writes = writes;
		this.clock = clock;
	}

	// --- students -----------------------------------------------------------------------

	/** ACTIVE students ordered by name (at most {@value #MAX_ROSTER}: a class is small, so there is no paging). */
	@Transactional(readOnly = true)
	public List<RosterStudent> students(CurrentUser user, UUID schoolId, UUID classId) {
		// The roster check, not the view check: guardians may see the class but never the other children (M5).
		ClassRef ref = this.lookups.rosterViewable(user, schoolId, classId);
		boolean full = seesStudentDetails(user, ref);
		List<StudentRow> rows = this.jdbc.sql("""
				select student_id, joined_at from class_students where class_id = :c and status = 'ACTIVE'
				order by joined_at, id limit :max""")
			.param("c", classId)
			.param("max", MAX_ROSTER)
			.query((rs, i) -> new StudentRow(Rows.uuid(rs, "student_id"), Rows.instant(rs, "joined_at")))
			.list();
		List<UUID> ids = rows.stream().map(StudentRow::studentId).toList();
		Map<UUID, PersonCard> cards = this.people.cards(ids);
		Map<UUID, StudentPersonal> personal = full ? this.people.studentPersonal(ids) : Map.of();
		List<RosterStudent> result = new ArrayList<>();
		for (StudentRow row : rows) {
			PersonCard card = cards.get(row.studentId());
			if (card == null) {
				continue;
			}
			StudentPersonal p = personal.get(row.studentId());
			result.add(new RosterStudent(row.studentId(), full ? DataAccessLevel.FULL : DataAccessLevel.BASIC, card.displayName(),
					card.avatar(), card.avatarConfig(), row.joinedAt(), (p != null) ? p.dateOfBirth() : null,
					(p != null) ? p.gender() : null, (p != null) ? p.username() : null));
		}
		result.sort(Comparator.comparing((RosterStudent s) -> s.displayName().toLowerCase(java.util.Locale.ROOT))
			.thenComparing(RosterStudent::studentId));
		return result;
	}

	public List<RosterStudent> addStudents(CurrentUser user, UUID schoolId, UUID classId, List<UUID> studentIds) {
		ClassRef ref = this.lookups.teachable(user, schoolId, classId);
		this.lookups.lockActive(ref);
		Set<UUID> ids = new LinkedHashSet<>(studentIds);
		if (this.people.activeStudents(schoolId, ids).size() != ids.size()) {
			throw ApiException.conflict("class.student_not_enrolled");
		}
		for (UUID studentId : ids) {
			this.writes.addStudent(schoolId, classId, studentId, user.userId());
		}
		this.audit.record(AuditEntry.of("class.students.add", AuditOutcome.SUCCESS)
			.target("class", classId)
			.school(schoolId)
			.metadata("count", ids.size()));
		return students(user, schoolId, classId);
	}

	public void removeStudent(CurrentUser user, UUID schoolId, UUID classId, UUID studentId, LeftReason reason) {
		ClassRef ref = this.lookups.teachable(user, schoolId, classId);
		this.lookups.lockActive(ref);
		LeftReason why = (reason != null) ? reason : LeftReason.REMOVED;
		if (why == LeftReason.TRANSFERRED_CLASS || why == LeftReason.LEFT_SCHOOL) {
			throw ApiException.badRequest("class.invalid_left_reason");
		}
		if (this.writes.endStudent(schoolId, classId, studentId, why.name(), user.userId()) == 0) {
			throw ApiException.notFound("class.member_not_found");
		}
		this.audit.record(AuditEntry.of("class.student.remove", AuditOutcome.SUCCESS)
			.target("class", classId)
			.school(schoolId)
			.metadata("studentId", studentId.toString())
			.metadata("reason", why.name()));
	}

	/** Moves an ACTIVE member to another class of the school; atomic, history kept (spec §12). */
	public void moveStudent(CurrentUser user, UUID schoolId, UUID fromClassId, UUID studentId, UUID toClassId) {
		if (fromClassId.equals(toClassId)) {
			throw ApiException.badRequest("class.move_same_class");
		}
		ClassRef from = this.lookups.teachable(user, schoolId, fromClassId);
		ClassRef to = this.lookups.teachable(user, schoolId, toClassId);
		// Lock in id order so two opposite moves cannot deadlock.
		List<ClassRef> order = new ArrayList<>(List.of(from, to));
		order.sort(Comparator.comparing(ClassRef::id));
		order.forEach(this.lookups::lockActive);
		if (this.writes.endStudent(schoolId, fromClassId, studentId, "TRANSFERRED_CLASS", user.userId()) == 0) {
			throw ApiException.notFound("class.member_not_found");
		}
		this.writes.addStudent(schoolId, toClassId, studentId, user.userId());
		this.audit.record(AuditEntry.of("class.student.move", AuditOutcome.SUCCESS)
			.target("class", fromClassId)
			.school(schoolId)
			.metadata("studentId", studentId.toString())
			.metadata("toClassId", toClassId.toString()));
	}

	// --- teachers -----------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<ClassTeacherView> teachers(CurrentUser user, UUID schoolId, UUID classId) {
		this.lookups.viewable(user, schoolId, classId);
		List<TeacherRow> rows = this.jdbc.sql("""
				select ct.id, ct.teacher_id, ct.role, ct.subject_id, s.name as subject_name, ct.joined_at
				from class_teachers ct left join subjects s on s.id = ct.subject_id
				where ct.class_id = :c and ct.status = 'ACTIVE'
				order by case ct.role when 'LEAD' then 0 when 'CO_TEACHER' then 1 when 'SUBJECT_TEACHER' then 2 else 3 end,
				         ct.joined_at, ct.id""")
			.param("c", classId)
			.query((rs, i) -> new TeacherRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "teacher_id"),
					TeacherRole.valueOf(rs.getString("role")), Rows.uuid(rs, "subject_id"), rs.getString("subject_name"),
					Rows.instant(rs, "joined_at")))
			.list();
		Map<UUID, PersonCard> cards = this.people.cards(rows.stream().map(TeacherRow::teacherId).toList());
		List<ClassTeacherView> result = new ArrayList<>();
		for (TeacherRow row : rows) {
			PersonCard card = cards.get(row.teacherId());
			result.add(new ClassTeacherView(row.id(), row.teacherId(), (card != null) ? card.displayName() : "",
					(card != null) ? card.avatar() : null, row.role(), row.subjectId(), row.subjectName(), row.joinedAt()));
		}
		return result;
	}

	public List<ClassTeacherView> addTeacher(CurrentUser user, UUID schoolId, UUID classId, UUID teacherId,
			TeacherRole role, UUID subjectId) {
		ClassRef ref = this.lookups.leadable(user, schoolId, classId);
		this.lookups.lockActive(ref);
		if (role == TeacherRole.SUBJECT_TEACHER && subjectId == null) {
			throw ApiException.badRequest("class.subject_required");
		}
		if (role != TeacherRole.SUBJECT_TEACHER && subjectId != null) {
			throw ApiException.badRequest("class.subject_not_allowed");
		}
		if (subjectId != null) {
			boolean exists = this.jdbc.sql("select exists (select 1 from subjects where id = :id and school_id = :s)")
				.param("id", subjectId)
				.param("s", schoolId)
				.query(Boolean.class)
				.single();
			if (!exists) {
				throw ApiException.notFound("academic.subject_not_found");
			}
		}
		if (!this.people.isActiveTeacher(schoolId, teacherId)) {
			throw ApiException.conflict("class.teacher_not_staff");
		}
		UUID id = Ids.newId();
		try {
			this.jdbc.sql("""
					insert into class_teachers (id, class_id, school_id, teacher_id, role, subject_id, status, joined_at, added_by)
					values (:id, :c, :s, :t, :role, :subject, 'ACTIVE', :now, :by)""")
				.param("id", id)
				.param("c", classId)
				.param("s", schoolId)
				.param("t", teacherId)
				.param("role", role.name())
				.param("subject", subjectId)
				.param("now", Timestamp.from(this.clock.instant()))
				.param("by", user.userId())
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			if (Constraints.violated(ex, "ux_class_teacher_active")) {
				throw ApiException.conflict("class.teacher_already_member");
			}
			throw ex;
		}
		this.events.publishEvent(new ClassMembershipChanged(classId, schoolId, teacherId, MemberType.TEACHER, Change.JOINED,
				role.name(), null, user.userId()));
		this.audit.record(AuditEntry.of("class.teacher.add", AuditOutcome.SUCCESS)
			.target("class", classId)
			.school(schoolId)
			.metadata("teacherId", teacherId.toString())
			.metadata("role", role.name()));
		return teachers(user, schoolId, classId);
	}

	/** {@code classTeacherId} is the id of the roster row (a teacher may hold several, one per subject). */
	public void removeTeacher(CurrentUser user, UUID schoolId, UUID classId, UUID classTeacherId) {
		ClassRef ref = this.lookups.viewable(user, schoolId, classId);
		this.lookups.lockActive(ref);
		TeacherRow target = this.jdbc.sql("""
				select ct.id, ct.teacher_id, ct.role, ct.subject_id, null as subject_name, ct.joined_at
				from class_teachers ct where ct.id = :id and ct.class_id = :c and ct.status = 'ACTIVE'""")
			.param("id", classTeacherId)
			.param("c", classId)
			.query((rs, i) -> new TeacherRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "teacher_id"),
					TeacherRole.valueOf(rs.getString("role")), Rows.uuid(rs, "subject_id"), null,
					Rows.instant(rs, "joined_at")))
			.optional()
			.orElseThrow(() -> ApiException.notFound("class.member_not_found"));
		boolean leader = this.access.canLead(user, classId);
		boolean herself = target.teacherId().equals(user.userId());
		if (!leader && !(herself && target.role() != TeacherRole.LEAD)) {
			throw ApiException.forbidden("class.access_denied");
		}
		if (target.role() == TeacherRole.LEAD) {
			int leads = this.jdbc.sql("select count(*) from class_teachers where class_id = :c and role = 'LEAD' and status = 'ACTIVE'")
				.param("c", classId)
				.query(Integer.class)
				.single();
			if (leads <= 1) {
				throw ApiException.conflict("class.last_lead");
			}
		}
		this.jdbc.sql("update class_teachers set status = 'REMOVED', left_at = :now, updated_at = now(), version = version + 1 where id = :id")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", classTeacherId)
			.update();
		this.events.publishEvent(new ClassMembershipChanged(classId, schoolId, target.teacherId(), MemberType.TEACHER,
				Change.LEFT, target.role().name(), "REMOVED", user.userId()));
		this.audit.record(AuditEntry.of("class.teacher.remove", AuditOutcome.SUCCESS)
			.target("class", classId)
			.school(schoolId)
			.metadata("teacherId", target.teacherId().toString())
			.metadata("role", target.role().name()));
	}

	// --- groups -------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<StudentGroupView> groups(CurrentUser user, UUID schoolId, UUID classId) {
		this.lookups.teachable(user, schoolId, classId);
		List<StudentGroupView> result = new ArrayList<>();
		this.jdbc.sql("select id, name, created_at from student_groups where class_id = :c order by created_at, id")
			.param("c", classId)
			.query((rs, i) -> new Object[] { Rows.uuid(rs, "id"), rs.getString("name"), Rows.instant(rs, "created_at") })
			.list()
			.forEach((g) -> result.add(new StudentGroupView((UUID) g[0], (String) g[1], groupMembers((UUID) g[0]),
					(Instant) g[2])));
		return result;
	}

	public StudentGroupView createGroup(CurrentUser user, UUID schoolId, UUID classId, String name, List<UUID> studentIds) {
		ClassRef ref = this.lookups.teachable(user, schoolId, classId);
		this.lookups.lockActive(ref);
		Set<UUID> ids = new LinkedHashSet<>(studentIds);
		Set<UUID> members = new LinkedHashSet<>(this.jdbc
			.sql("select student_id from class_students where class_id = :c and status = 'ACTIVE' and student_id in (:ids)")
			.param("c", classId)
			.param("ids", ids)
			.query(UUID.class)
			.list());
		if (members.size() != ids.size()) {
			throw ApiException.conflict("class.group_member_not_in_class");
		}
		UUID id = Ids.newId();
		this.jdbc.sql("insert into student_groups (id, class_id, school_id, name, created_by) values (:id, :c, :s, :n, :by)")
			.param("id", id)
			.param("c", classId)
			.param("s", schoolId)
			.param("n", name.trim())
			.param("by", user.userId())
			.update();
		for (UUID studentId : ids) {
			this.jdbc.sql("insert into student_group_members (group_id, student_id) values (:g, :st)")
				.param("g", id)
				.param("st", studentId)
				.update();
		}
		this.audit.record(AuditEntry.of("class.group.create", AuditOutcome.SUCCESS)
			.target("student_group", id)
			.school(schoolId)
			.metadata("classId", classId.toString())
			.metadata("members", ids.size()));
		return groups(user, schoolId, classId).stream().filter((g) -> g.id().equals(id)).findFirst().orElseThrow();
	}

	public void deleteGroup(CurrentUser user, UUID schoolId, UUID classId, UUID groupId) {
		this.lookups.teachable(user, schoolId, classId);
		this.jdbc.sql("delete from student_group_members where group_id = :g and exists (select 1 from student_groups where id = :g and class_id = :c)")
			.param("g", groupId)
			.param("c", classId)
			.update();
		int deleted = this.jdbc.sql("delete from student_groups where id = :g and class_id = :c")
			.param("g", groupId)
			.param("c", classId)
			.update();
		if (deleted == 0) {
			throw ApiException.notFound("class.group_not_found");
		}
		this.audit.record(AuditEntry.of("class.group.delete", AuditOutcome.SUCCESS)
			.target("student_group", groupId)
			.school(schoolId)
			.metadata("classId", classId.toString()));
	}

	// --- helpers ------------------------------------------------------------------------

	private boolean seesStudentDetails(CurrentUser user, ClassRef ref) {
		return this.gate.can(user, ref.schoolId(), Permissions.SCHOOL_STUDENTS_MANAGE) || this.jdbc
			.sql("select exists (select 1 from class_teachers where class_id = :c and teacher_id = :u and status = 'ACTIVE')")
			.param("c", ref.id())
			.param("u", user.userId())
			.query(Boolean.class)
			.single();
	}

	private List<UUID> groupMembers(UUID groupId) {
		return this.jdbc.sql("select student_id from student_group_members where group_id = :g order by student_id")
			.param("g", groupId)
			.query(UUID.class)
			.list();
	}

	private record StudentRow(UUID studentId, Instant joinedAt) {
	}

	private record TeacherRow(UUID id, UUID teacherId, TeacherRole role, UUID subjectId, String subjectName,
			Instant joinedAt) {
	}

	/** Reasons a membership can be ended by hand (TRANSFERRED_CLASS comes from a move, LEFT_SCHOOL from withdrawal). */
	public enum LeftReason {

		REMOVED, PROMOTED, TRANSFERRED_CLASS, LEFT_SCHOOL

	}

	public record RosterStudent(@NotNull UUID studentId, @NotNull DataAccessLevel accessLevel, @NotNull String displayName,
			MediaSummary avatar, @Schema(description = "Avatar builder configuration (monster/avatar), client-defined") Map<String, Object> avatarConfig,
			@NotNull Instant joinedAt,
			@Schema(description = "Only with accessLevel FULL") LocalDate dateOfBirth,
			@Schema(description = "Only with accessLevel FULL") String gender,
			@Schema(description = "Only with accessLevel FULL") String username) {
	}

	public record ClassTeacherView(@NotNull @Schema(description = "Roster row id, used to remove the teacher") UUID id,
			@NotNull UUID teacherId, @NotNull String displayName, MediaSummary avatar, @NotNull TeacherRole role,
			UUID subjectId, String subjectName, @NotNull Instant joinedAt) {
	}

	public record StudentGroupView(@NotNull UUID id, @NotNull String name, @NotNull List<UUID> studentIds,
			@NotNull Instant createdAt) {
	}

}

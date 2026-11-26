package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The policy matrix (rule book R1-R4, R7): every sender role against every recipient role under a set of settings combinations
 * (an orthogonal array that covers every pair of settings values, the defaults, everything on and everything off), then every
 * link and membership state that must end access. The expectations are written down here as plain rules, independently of the
 * SQL and the code of the policy engine.
 */
@IntegrationTest
class MessagingPolicyMatrixTest extends MessagingTestSupport {

	/** Settings: parent_teacher, parent_parent, teacher_direct, initiator EITHER, student_direct, students enabled. */
	private record Settings(boolean parentTeacher, boolean parentParent, boolean teacherDirect, boolean either, boolean studentDirect,
			boolean studentsEnabled) {

		@Override
		public String toString() {
			return "pt=" + this.parentTeacher + " pp=" + this.parentParent + " td=" + this.teacherDirect + " either=" + this.either
					+ " sd=" + this.studentDirect + " se=" + this.studentsEnabled;
		}

	}

	private static List<Settings> combinations() {
		int[][] l8 = { { 1, 1, 1, 1, 1, 1 }, { 1, 1, 1, 2, 2, 2 }, { 1, 2, 2, 1, 1, 2 }, { 1, 2, 2, 2, 2, 1 }, { 2, 1, 2, 1, 2, 1 },
				{ 2, 1, 2, 2, 1, 2 }, { 2, 2, 1, 1, 2, 2 }, { 2, 2, 1, 2, 1, 1 } };
		List<Settings> all = new ArrayList<>();
		for (int[] row : l8) {
			all.add(new Settings(row[0] == 2, row[1] == 2, row[2] == 2, row[3] == 2, row[4] == 2, row[5] == 2));
		}
		all.add(new Settings(true, false, false, false, false, true)); // the shipped defaults
		all.add(new Settings(true, true, true, true, true, true));
		all.add(new Settings(false, false, false, false, false, false));
		return all;
	}

	private enum R {
		LEAD, CO, SUBJECT, ASSISTANT, OTHER_TEACHER, STAFF, ADMIN, G1, G1B, G2, OTHER_GUARDIAN, S1, S2, OTHER_STUDENT, STRANGER
	}

	private static boolean teacherOfAbel(R r) {
		return r == R.LEAD || r == R.CO || r == R.SUBJECT;
	}

	private static boolean guardianOfAbel(R r) {
		return r == R.G1 || r == R.G1B;
	}

	private static boolean student(R r) {
		return r == R.S1 || r == R.S2 || r == R.OTHER_STUDENT;
	}

	/** The rules of the rule book as a function: may {@code a} start a direct conversation with {@code b} (about Abel)? */
	private static boolean allowed(R a, R b, Settings s) {
		if (a == b) {
			return false;
		}
		if (student(a)) {
			if (!s.studentsEnabled()) {
				return false;
			}
			if (student(b)) {
				// same class 1A: Abel, Bethlehem; Dawit is in 2B
				boolean sameClass = (a != R.OTHER_STUDENT) && (b != R.OTHER_STUDENT);
				return s.studentDirect() && sameClass;
			}
			// a student reaches their own teachers, and only when the school lets students start it
			return teaches(b, a) && s.teacherDirect() && s.either();
		}
		if (student(b)) {
			return teaches(a, b) && s.studentsEnabled() && s.teacherDirect();
		}
		// two adults, about Abel
		if (teacherOfAbel(a) && guardianOfAbel(b) || guardianOfAbel(a) && teacherOfAbel(b)) {
			return s.parentTeacher();
		}
		if (guardianOfAbel(a) && (guardianOfAbel(b) || b == R.G2)) {
			return s.parentParent();
		}
		return false;
	}

	/** Class 1A has Abel and Bethlehem with LEAD, CO and SUBJECT; class 2B has Dawit with the other teacher. */
	private static boolean teaches(R teacher, R student) {
		return (student == R.OTHER_STUDENT) ? teacher == R.OTHER_TEACHER : (student(student) && teacherOfAbel(teacher));
	}

	private Persona persona(Msg m, R r) {
		return switch (r) {
			case LEAD -> m.lead();
			case CO -> m.co();
			case SUBJECT -> m.subject();
			case ASSISTANT -> m.assistant();
			case OTHER_TEACHER -> m.otherTeacher();
			case STAFF -> m.staff();
			case ADMIN -> m.admin();
			case G1 -> m.g1();
			case G1B -> m.g1b();
			case G2 -> m.g2();
			case OTHER_GUARDIAN -> m.otherGuardian();
			case S1 -> m.student1();
			case S2 -> m.student2();
			case OTHER_STUDENT -> m.otherStudent();
			case STRANGER -> m.stranger();
		};
	}

	private void apply(Msg m, Settings s) {
		setting(m.schoolId(), "messaging.parent_teacher.enabled", s.parentTeacher());
		setting(m.schoolId(), "messaging.parent_parent.enabled", s.parentParent());
		setting(m.schoolId(), "messaging.student.teacher_direct.enabled", s.teacherDirect());
		setting(m.schoolId(), "messaging.student.teacher_direct.initiator", s.either() ? "EITHER" : "TEACHER_ONLY");
		setting(m.schoolId(), "messaging.student.student_direct.enabled", s.studentDirect());
		setting(m.schoolId(), "messaging.student.enabled", s.studentsEnabled());
	}

	@Test
	void everyCombinationFollowsTheRules() throws Exception {
		List<String> problems = new ArrayList<>();
		int cases = 0;
		int allowedCases = 0;
		for (Settings s : combinations()) {
			Msg m = school("matrix");
			apply(m, s);
			for (R a : R.values()) {
				for (R b : R.values()) {
					boolean expected = allowed(a, b, s);
					UUID recipient = persona(m, b).userId();
					boolean aboutAbel = !student(a) && !student(b);
					int status = statusOf(HttpMethod.POST, m.api() + "/conversations/direct", persona(m, a),
							body("recipientUserId", recipient, "studentId", aboutAbel ? m.s1() : null));
					boolean got = status == 201 || status == 200;
					cases++;
					if (expected) {
						allowedCases++;
					}
					if (got != expected) {
						problems.add(s + ": " + a + " -> " + b + " expected " + (expected ? "allowed" : "refused") + " got " + status);
					}
					if (!expected && !(status == 403 || (a == R.STRANGER && status == 404))) {
						problems.add(s + ": " + a + " -> " + b + " refused with unexpected status " + status);
					}
				}
			}
			// the same through reading: whatever was created is readable by exactly its two people (and observers), nobody else
			var rows = this.jdbc.queryForList("select id, direct_kind from conversations where school_id = ?", m.schoolId());
			for (var row : rows) {
				UUID id = (UUID) row.get("id");
				Map<R, Integer> reads = new LinkedHashMap<>();
				for (R r : R.values()) {
					reads.put(r, statusOf(HttpMethod.GET, conv(id), persona(m, r), null));
				}
				long readers = reads.values().stream().filter((x) -> x == 200).count();
				long members = this.jdbc.queryForObject("select count(*) from conversation_members where conversation_id = ? and left_at is null",
						Long.class, id);
				if (readers != members) {
					problems.add(s + ": conversation " + row.get("direct_kind") + " readable by " + readers + " people but has " + members + " members");
				}
			}
		}
		assertThat(problems).isEmpty();
		assertThat(cases).isGreaterThan(2000);
		assertThat(allowedCases).isGreaterThan(100);
	}

	/** One change of the world per round: who must lose a conversation at once (before any event is processed) and who must keep it. */
	@Test
	void everyLinkAndMembershipStateEndsAccess() throws Exception {
		record Change(String name, java.util.function.Consumer<Msg> apply, java.util.Set<String> deadParentThread,
				java.util.Set<String> deadStudentThread) {
		}
		var both = java.util.Set.of("g1", "lead");
		var all = java.util.Set.of("lead", "student1", "g1", "g1b");
		var none = java.util.Set.<String>of();
		List<Change> changes = List.of(
				new Change("guardian link restricted", (m) -> restrict(m.g1Link()), both, java.util.Set.of("g1")),
				new Change("guardian link revoked", (m) -> revoke(m.g1Link()), both, java.util.Set.of("g1")),
				new Change("guardian link rejected", (m) -> this.jdbc.update("update student_guardians set status = 'REJECTED' where id = ?", m.g1Link()), both, java.util.Set.of("g1")),
				new Change("guardian link pending again", (m) -> this.jdbc.update("update student_guardians set status = 'PENDING' where id = ?", m.g1Link()), both, java.util.Set.of("g1")),
				new Change("guardian may not message staff", (m) -> this.jdbc.update("update student_guardians set can_message_staff = false where id = ?", m.g1Link()), both, none),
				new Change("guardian account locked", (m) -> this.jdbc.update("update users set status = 'LOCKED' where id = ?", m.g1().userId()), both, java.util.Set.of("g1")),
				new Change("child withdrawn from the school", (m) -> withdraw(m.schoolId(), m.s1()), both, all),
				new Change("child left the class", (m) -> leaveClass(m.classId(), m.s1()), both, all),
				new Change("teacher left the class", (m) -> this.jdbc.update("update class_teachers set status = 'FORMER', left_at = now() where teacher_id = ?", m.lead().userId()), both, all),
				new Change("teacher left the school", (m) -> this.jdbc.update("update school_staff set status = 'FORMER' where user_id = ?", m.lead().userId()), both, all),
				new Change("teacher became an assistant", (m) -> this.jdbc.update("update class_teachers set role = 'ASSISTANT' where teacher_id = ?", m.lead().userId()), both, all),
				new Change("teacher account suspended", (m) -> this.jdbc.update("update users set status = 'SUSPENDED' where id = ?", m.lead().userId()), both, all),
				new Change("teacher account deleted", (m) -> this.jdbc.update("update users set deleted_at = now() where id = ?", m.lead().userId()), both, all),
				new Change("school suspended", (m) -> this.jdbc.update("update schools set status = 'SUSPENDED' where id = ?", m.schoolId()), both, all),
				new Change("school archived", (m) -> this.jdbc.update("update schools set status = 'ARCHIVED' where id = ?", m.schoolId()), both, all),
				new Change("nothing changes", (m) -> { }, none, none));
		List<String> problems = new ArrayList<>();
		for (Change change : changes) {
			Msg m = school("states");
			UUID parentThread = parentTeacherThread(m);
			UUID studentThread = teacherStudentThread(m);
			send(m.lead(), parentThread, "before");
			send(m.lead(), studentThread, "before");
			change.apply().accept(m);
			Map<String, Persona> people = Map.of("g1", m.g1(), "g1b", m.g1b(), "lead", m.lead(), "student1", m.student1());
			for (var entry : people.entrySet()) {
				for (UUID thread : new UUID[] { parentThread, studentThread }) {
					boolean parent = thread.equals(parentThread);
					if (parent && !List.of("g1", "lead").contains(entry.getKey())) {
						continue;
					}
					boolean dead = (parent ? change.deadParentThread() : change.deadStudentThread()).contains(entry.getKey());
					int reading = statusOf(HttpMethod.GET, conv(thread) + "/messages", entry.getValue(), null);
					int writing = statusOf(HttpMethod.POST, conv(thread) + "/messages", entry.getValue(),
							body("clientMessageId", UUID.randomUUID(), "body", "after"));
					boolean observer = !parent && entry.getKey().startsWith("g1");
					String where = change.name() + ": " + entry.getKey() + " in the " + (parent ? "parent-teacher" : "safeguarded") + " thread";
					if (dead && (reading == 200 || writing == 201)) {
						problems.add(where + " can still read (" + reading + ") or write (" + writing + ")");
					}
					if (!dead && reading != 200) {
						problems.add(where + " lost access it should keep (" + reading + ")");
					}
					if (!dead && !observer && writing != 201) {
						problems.add(where + " cannot write although nothing ended it (" + writing + ")");
					}
				}
			}
		}
		assertThat(problems).isEmpty();
	}

}

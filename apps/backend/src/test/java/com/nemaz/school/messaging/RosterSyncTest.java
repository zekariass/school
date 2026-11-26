package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.academic.ClassMembershipChanged;
import com.nemaz.school.guardians.GuardianLinkChanged;
import com.nemaz.school.messaging.application.RosterSync;
import com.nemaz.school.messaging.application.Safeguarding;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/** Rule book R6.4: the membership of class conversations follows the roster, through idempotent listeners. */
@IntegrationTest
class RosterSyncTest extends MessagingTestSupport {

	@Autowired
	ApplicationEventPublisher events;

	@Autowired
	RosterSync roster;

	@Autowired
	Safeguarding safeguarding;

	private Set<UUID> members(UUID conversationId) {
		return Set.copyOf(this.jdbc.queryForList("select user_id from conversation_members where conversation_id = ? and left_at is null",
				UUID.class, conversationId));
	}

	private void publish(Object event) {
		this.tx.executeWithoutResult((s) -> this.events.publishEvent(event));
	}

	private void awaitMember(UUID thread, UUID user, boolean present) {
		await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(
				() -> assertThat(members(thread).contains(user)).isEqualTo(present));
	}

	@Test
	void studentJoiningAndLeavingTheClassUpdatesTheConversations() throws Exception {
		Msg m = school("roster-students");
		UUID classThread = classConversation(m);
		UUID parents = parentGroup(m);
		UUID newcomer = this.personas.newStudent(m.schoolId(), "Newcomer");
		Persona newcomerPersona = this.personas.personaFor("newcomer", newcomer);
		Persona newcomerGuardian = this.personas.newUser("roster-newcomer-guardian");
		this.personas.linkGuardian(newcomer, newcomerGuardian.userId(), "ACTIVE", false);
		this.personas.addStudentToClass(m.schoolId(), m.classId(), newcomer);
		// no event processed yet: the roster is ahead of the conversation, but nothing is granted by it
		assertThat(statusOf(org.springframework.http.HttpMethod.GET, conv(classThread), newcomerPersona, null)).isEqualTo(404);
		publish(new ClassMembershipChanged(m.classId(), m.schoolId(), newcomer, ClassMembershipChanged.MemberType.STUDENT,
				ClassMembershipChanged.Change.JOINED, null, null, null));
		awaitMember(classThread, newcomer, true);
		awaitMember(parents, newcomerGuardian.userId(), true);
		assertThat(statusOf(org.springframework.http.HttpMethod.GET, conv(classThread), newcomerPersona, null)).isEqualTo(200);
		assertThat(statusOf(org.springframework.http.HttpMethod.GET, conv(parents), newcomerGuardian, null)).isEqualTo(200);
		assertThat(members(parents)).doesNotContain(newcomer);
		// leaving
		leaveClass(m.classId(), newcomer);
		publish(new ClassMembershipChanged(m.classId(), m.schoolId(), newcomer, ClassMembershipChanged.MemberType.STUDENT,
				ClassMembershipChanged.Change.LEFT, null, "TRANSFERRED_CLASS", null));
		awaitMember(classThread, newcomer, false);
		awaitMember(parents, newcomerGuardian.userId(), false);
		assertThat(this.jdbc.queryForObject("select left_reason from conversation_members where conversation_id = ? and user_id = ?",
				String.class, classThread, newcomer)).isEqualTo("ROSTER");
		// teachers too
		UUID extra = this.personas.newTeacher(m.schoolId(), "roster-extra");
		this.personas.addTeacherToClass(m.schoolId(), m.classId(), extra, "SUBJECT_TEACHER",
				this.jdbc.queryForObject("select id from subjects where school_id = ? limit 1", UUID.class, m.schoolId()));
		publish(new ClassMembershipChanged(m.classId(), m.schoolId(), extra, ClassMembershipChanged.MemberType.TEACHER,
				ClassMembershipChanged.Change.JOINED, "SUBJECT_TEACHER", null, null));
		awaitMember(classThread, extra, true);
		awaitMember(parents, extra, true);
		this.jdbc.update("update class_teachers set status = 'FORMER', left_at = now() where teacher_id = ?", extra);
		publish(new ClassMembershipChanged(m.classId(), m.schoolId(), extra, ClassMembershipChanged.MemberType.TEACHER,
				ClassMembershipChanged.Change.LEFT, "SUBJECT_TEACHER", "REMOVED", null));
		awaitMember(classThread, extra, false);
	}

	@Test
	void guardianLinkChangesUpdateTheParentGroup() throws Exception {
		Msg m = school("roster-guardians");
		UUID parents = parentGroup(m);
		Persona newGuardian = this.personas.newUser("roster-new-guardian");
		UUID link = this.personas.linkGuardian(m.s2(), newGuardian.userId(), "ACTIVE", false);
		publish(new GuardianLinkChanged(link, m.s2(), newGuardian.userId(), m.schoolId(), GuardianLinkChanged.Change.ACTIVATED, null));
		awaitMember(parents, newGuardian.userId(), true);
		// restricted: out (and out at once for reading)
		restrict(link);
		assertThat(statusOf(org.springframework.http.HttpMethod.GET, conv(parents), newGuardian, null)).isEqualTo(404);
		publish(new GuardianLinkChanged(link, m.s2(), newGuardian.userId(), m.schoolId(), GuardianLinkChanged.Change.UPDATED, null));
		awaitMember(parents, newGuardian.userId(), false);
		// the restriction ends: back
		unrestrict(link);
		publish(new GuardianLinkChanged(link, m.s2(), newGuardian.userId(), m.schoolId(), GuardianLinkChanged.Change.UPDATED, null));
		awaitMember(parents, newGuardian.userId(), true);
		assertThat(statusOf(org.springframework.http.HttpMethod.GET, conv(parents), newGuardian, null)).isEqualTo(200);
		// a guardian with two children in the class has one membership
		UUID second = this.personas.linkGuardian(m.s3(), newGuardian.userId(), "ACTIVE", false);
		publish(new GuardianLinkChanged(second, m.s3(), newGuardian.userId(), m.schoolId(), GuardianLinkChanged.Change.ACTIVATED, null));
		Thread.sleep(500);
		assertThat(this.jdbc.queryForObject("select count(*) from conversation_members where conversation_id = ? and user_id = ? and left_at is null",
				Integer.class, parents, newGuardian.userId())).isEqualTo(1);
		// revoked: out
		revoke(link);
		revoke(second);
		publish(new GuardianLinkChanged(link, m.s2(), newGuardian.userId(), m.schoolId(), GuardianLinkChanged.Change.REVOKED, null));
		awaitMember(parents, newGuardian.userId(), false);
	}

	@Test
	void guardianLinkEventsMaintainTheObservers() throws Exception {
		Msg m = school("roster-observers");
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		UUID thread = conversationId(startDirect(m.lead(), m, m.s3(), null, 201));
		assertThat(adminVisible(thread)).isTrue();
		Persona guardian = this.personas.newUser("roster-observer-guardian");
		UUID link = this.personas.linkGuardian(m.s3(), guardian.userId(), "ACTIVE", false);
		publish(new GuardianLinkChanged(link, m.s3(), guardian.userId(), m.schoolId(), GuardianLinkChanged.Change.ACTIVATED, null));
		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(observers(thread)).containsExactly(guardian.userId()));
		assertThat(adminVisible(thread)).isFalse();
		restrict(link);
		publish(new GuardianLinkChanged(link, m.s3(), guardian.userId(), m.schoolId(), GuardianLinkChanged.Change.UPDATED, null));
		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(observers(thread)).isEmpty());
		assertThat(adminVisible(thread)).isTrue();
	}

	@Test
	void redeliveredEventsChangeNothing() throws Exception {
		Msg m = school("roster-redeliver");
		UUID classThread = classConversation(m);
		UUID parents = parentGroup(m);
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		UUID direct = conversationId(startDirect(m.lead(), m, m.s1(), null, 201));
		Map<String, Object> before = snapshot(classThread, parents, direct);
		for (int i = 0; i < 3; i++) {
			assertThat(this.roster.reconcileClass(m.classId())).isZero();
			assertThat(this.safeguarding.reconcileStudent(m.s1())).isEqualTo(1);
		}
		assertThat(snapshot(classThread, parents, direct)).isEqualTo(before);
		// the events themselves, delivered several times
		for (int i = 0; i < 3; i++) {
			publish(new ClassMembershipChanged(m.classId(), m.schoolId(), m.s1(), ClassMembershipChanged.MemberType.STUDENT,
					ClassMembershipChanged.Change.JOINED, null, null, null));
			publish(new GuardianLinkChanged(m.g1Link(), m.s1(), m.g1().userId(), m.schoolId(), GuardianLinkChanged.Change.ACTIVATED, null));
		}
		Thread.sleep(1500);
		assertThat(snapshot(classThread, parents, direct)).isEqualTo(before);
		// the observers were notified exactly once
		assertThat(notificationsOf(m.g1(), "MESSAGE_OBSERVER_ADDED")).hasSize(1);
		assertThat(List.of(classThread, parents)).hasSize(2);
	}

	private Map<String, Object> snapshot(UUID... conversations) {
		Map<String, Object> snapshot = new java.util.TreeMap<>();
		for (UUID id : conversations) {
			snapshot.put(id.toString(), this.jdbc.queryForList("""
					select user_id, member_role, left_at is null as live, left_reason from conversation_members
					where conversation_id = ? order by user_id, joined_at""", id));
		}
		return snapshot;
	}

}

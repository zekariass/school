package com.nemaz.school.attendance;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.DomainEventRecorder;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The guardian notifications of attendance marks and the perfect-attendance badge hook. */
@IntegrationTest
class AttendanceEventsTest extends AttendanceTestSupport {

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	DomainEventRecorder recorder;

	private int notified(UUID user) {
		return this.jdbc.queryForObject("select count(*) from notifications where type_code = 'ATTENDANCE_MARKED' and user_id = ?",
				Integer.class, user);
	}

	private void awaitNotified(UUID user, int expected) {
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(notified(user)).isEqualTo(expected));
	}

	/** Gives the asynchronous listeners time to (not) act. */
	private void settle() throws InterruptedException {
		Thread.sleep(1500);
	}

	@Test
	void guardiansAreToldOnlyAboutAbsentAndLateByDefaultInTheirLanguageWithoutTheNote() throws Exception {
		Setup s = setup("Notify school");
		this.jdbc.update("update users set preferred_language_code = 'am' where id = ?", s.guardian2().userId());
		String note = "private-note-" + UUID.randomUUID();
		take(s, s.lead(), rollBody(today(), true, mark(s.s1(), "ABSENT", null, note, null), mark(s.s2(), "LATE", 5, null, null)), 200);

		awaitNotified(s.guardian1().userId(), 1);
		awaitNotified(s.guardian2().userId(), 1);
		awaitNotified(s.guardianOfS2().userId(), 1);
		// the child (and the lead who marked) are not notified, and nobody hears about "present"
		assertThat(notified(s.s1())).isZero();
		assertThat(notified(s.leadId())).isZero();
		String english = this.jdbc.queryForObject("select body from notifications where type_code = 'ATTENDANCE_MARKED' and user_id = ?",
				String.class, s.guardian1().userId());
		assertThat(english).isEqualTo("Abel was marked absent on " + today() + ".");
		String amharic = this.jdbc.queryForObject("select body from notifications where type_code = 'ATTENDANCE_MARKED' and user_id = ?",
				String.class, s.guardian2().userId());
		assertThat(amharic).contains("Abel").contains("ቀሪ");
		// the other child's guardian hears only about their own child
		String other = this.jdbc.queryForObject("select body from notifications where type_code = 'ATTENDANCE_MARKED' and user_id = ?",
				String.class, s.guardianOfS2().userId());
		assertThat(other).contains("Bethlehem").contains("late").doesNotContain("Abel");
		// no note anywhere outside the record
		settle();
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where body like ? or title like ? or cast(data as text) like ?",
				Integer.class, "%" + note + "%", "%" + note + "%", "%" + note + "%")).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from event_publication where serialized_event like ?", Integer.class,
				"%" + note + "%")).isZero();
		assertThat(this.jdbc.queryForObject(
				"select count(*) from notifications where type_code = 'ATTENDANCE_MARKED' and school_id = ? and data->>'path' <> '/children'",
				Integer.class, s.schoolId())).isZero();
	}

	@Test
	void theSchoolChoosesWhichMarksNotify() throws Exception {
		Setup s = setup("Choose school");
		setting(s.schoolId(), "attendance.notify_guardians.statuses", List.of("PRESENT", "EXCUSED"));
		take(s, s.lead(), rollBody(today(), false, mark(s.s1(), "ABSENT")), 200);
		take(s, s.lead(), rollBody(today(), false, mark(s.s2(), "PRESENT")), 200);
		awaitNotified(s.guardianOfS2().userId(), 1);
		settle();
		assertThat(notified(s.guardian1().userId())).isZero();

		setting(s.schoolId(), "attendance.notify_guardians.statuses", List.of());
		take(s, s.lead(), rollBody(today(), false, mark(s.s3(), "ABSENT")), 200);
		take(s, s.lead(), rollBody(today(), false, mark(s.s2(), "ABSENT", null, null, 0)), 200);
		settle();
		assertThat(notified(s.guardianOfS2().userId())).isEqualTo(1);
	}

	@Test
	void onlyGuardiansWhoMayReceiveNotificationsAreToldAndRestrictedLinksNever() throws Exception {
		Setup s = setup("Flags school");
		this.jdbc.update("update student_guardians set can_receive_notifications = false where guardian_id = ?",
				s.guardian2().userId());
		Persona restricted = this.personas.newUser("restricted");
		this.personas.linkGuardian(s.s1(), restricted.userId(), "ACTIVE", true);
		Persona pending = this.personas.newUser("pending");
		this.personas.linkGuardian(s.s1(), pending.userId(), "PENDING", false);
		take(s, s.lead(), rollBody(today(), false, mark(s.s1(), "ABSENT")), 200);
		awaitNotified(s.guardian1().userId(), 1);
		settle();
		assertThat(notified(s.guardian2().userId())).isZero();
		assertThat(notified(restricted.userId())).isZero();
		assertThat(notified(pending.userId())).isZero();
	}

	@Test
	void redeliveryNotifiesOnceAndEachChangeOfTheRecordNotifiesOnce() throws Exception {
		Setup s = setup("Idempotent notify school");
		take(s, s.lead(), rollBody(today(), false, mark(s.s1(), "ABSENT")), 200);
		awaitNotified(s.guardian1().userId(), 1);
		AttendanceMarked first = this.recorder.of(AttendanceMarked.class)
			.stream()
			.filter((e) -> e.studentId().equals(s.s1()))
			.findFirst()
			.orElseThrow();
		// the same event again (at-least-once delivery)
		this.tx.executeWithoutResult((status) -> this.publisher.publishEvent(first));
		settle();
		assertThat(notified(s.guardian1().userId())).isEqualTo(1);

		// a correction to LATE is a new change and is announced; the old event is now stale and stays quiet
		take(s, s.lead(), rollBody(today(), false, mark(s.s1(), "LATE", 15, null, 0)), 200);
		awaitNotified(s.guardian1().userId(), 2);
		this.tx.executeWithoutResult((status) -> this.publisher.publishEvent(first));
		settle();
		assertThat(notified(s.guardian1().userId())).isEqualTo(2);
	}

	@Test
	void perfectAttendanceIsGrantedAfterTheBadgesOwnNumberOfPerfectDays() throws Exception {
		Setup s = setup("Streak school");
		setting(s.schoolId(), "attendance.lock_after_hours", 720);
		get(s.api() + "/badges", s.admin()); // makes the school's copy of the default badges
		this.jdbc.update("update badges set criteria = '{\"days\": 3}'::jsonb where school_id = ? and code = 'perfect_attendance'",
				s.schoolId());
		// days nobody took a roll (the weekend) are not days: three days with marks make a streak of three
		LocalDate d1 = today().minusDays(6);
		LocalDate d2 = today().minusDays(4);
		LocalDate d3 = today().minusDays(1);
		take(s, s.lead(), rollBody(d1, true), 200);
		take(s, s.lead(), rollBody(d2, true, mark(s.s2(), "ABSENT")), 200);
		take(s, s.lead(), rollBody(d3, true, mark(s.s3(), "LATE", 3, null, null)), 200);
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(this.jdbc.queryForList(
				"select student_id from student_badges sb join badges b on b.id = sb.badge_id where b.school_id = ? and b.code = 'perfect_attendance'",
				UUID.class, s.schoolId())).containsExactly(s.s1()));
		settle();
		// s2 was absent on the second day, s3 late on the third: no streak yet
		assertThat(this.jdbc.queryForObject("select count(*) from student_badges where student_id in (?, ?)", Integer.class, s.s2(),
				s.s3())).isZero();
		// the student and the guardians hear about the badge once
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from notifications where type_code = 'BADGE_AWARDED' and user_id = ?", Integer.class,
				s.guardian1().userId())).isEqualTo(1));
		// s2 starts a new streak after the absence: -3 and -1 are two days, a roll filled in for -2 makes it three
		take(s, s.lead(), rollBody(today().minusDays(3), false, mark(s.s2(), "PRESENT")), 200);
		settle();
		assertThat(this.jdbc.queryForObject("select count(*) from student_badges where student_id = ?", Integer.class, s.s2()))
			.isZero();
		take(s, s.lead(), rollBody(today().minusDays(2), false, mark(s.s2(), "PRESENT")), 200);
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from student_badges where student_id = ?", Integer.class, s.s2())).isEqualTo(1));
	}

	@Test
	void noBadgeWhileTheSchoolHasSwitchedBadgesOff() throws Exception {
		Setup s = setup("No badges school");
		setting(s.schoolId(), "engagement.badges.enabled", false);
		get(s.api() + "/badges", s.admin());
		this.jdbc.update("update badges set criteria = '{\"days\": 1}'::jsonb where school_id = ? and code = 'perfect_attendance'",
				s.schoolId());
		take(s, s.lead(), rollBody(today(), true), 200);
		settle();
		assertThat(this.jdbc.queryForObject("select count(*) from student_badges where school_id = ?", Integer.class, s.schoolId()))
			.isZero();
		setting(s.schoolId(), "engagement.badges.enabled", true);
		take(s, s.lead(), rollBody(today(), false, mark(s.s1(), "ABSENT", null, null, 0)), 200);
		take(s, s.lead(), rollBody(today(), false, mark(s.s1(), "PRESENT", null, null, 1)), 200);
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from student_badges where school_id = ? and student_id = ?", Integer.class, s.schoolId(), s.s1()))
			.isEqualTo(1));
	}

}

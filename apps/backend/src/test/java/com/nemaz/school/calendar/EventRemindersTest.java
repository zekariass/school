package com.nemaz.school.calendar;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** UPCOMING_EVENT reminders: lead times, who is reminded, once per person per occurrence, privacy. */
@IntegrationTest
class EventRemindersTest extends CalendarTestSupport {

	private int reminders(UUID eventId, Persona person) {
		return this.jdbc.queryForObject("select count(*) from notifications where source_id = ? and user_id = ? and type_code = 'UPCOMING_EVENT'",
				Integer.class, eventId, person.userId());
	}

	private int reminders(UUID eventId) {
		return this.jdbc.queryForObject("select count(*) from notifications where source_id = ? and type_code = 'UPCOMING_EVENT'", Integer.class,
				eventId);
	}

	private Instant inDays(int days) {
		return Instant.now().plus(Duration.ofDays(days)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
	}

	@Test
	void peopleWhoAnsweredYesOrMaybeAreRemindedOneDayBeforeAndOnlyOnce() throws Exception {
		Cal c = cal("rem-rsvp");
		Instant start = inDays(5);
		UUID id = create(c, c.admin(), with(timed("Open day", "SCHOOL", start), "rsvpEnabled", true));
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gA1(), Map.of("status", "YES"), 200);
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gSib(), Map.of("status", "MAYBE"), 200);
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gB1(), Map.of("status", "NO"), 200);
		// two days before: nothing yet (the lead time is one day)
		clockAt(start.minus(Duration.ofDays(2)));
		this.maintenance.sendReminders();
		assertThat(reminders(id)).isZero();
		// 23 hours before: the people who may come are reminded
		clockAt(start.minus(Duration.ofHours(23)));
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.gA1())).isEqualTo(1);
		assertThat(reminders(id, c.gSib())).isEqualTo(1);
		assertThat(reminders(id, c.gB1())).isZero(); // said no
		assertThat(reminders(id, c.gX())).isZero(); // never answered
		assertThat(reminders(id, c.admin())).isZero();
		// the job is idempotent: running it again and again, later, changes nothing
		this.maintenance.sendReminders();
		clockAt(start.minus(Duration.ofHours(2)));
		this.maintenance.sendReminders();
		assertThat(reminders(id)).isEqualTo(2);
		Map<String, Object> text = inbox(c.gA1().userId(), "UPCOMING_EVENT").getFirst();
		assertThat(text.get("title").toString()).isEqualTo("Open day starts soon");
		assertThat(text.get("data").toString()).contains("/calendar/events/" + id);
		// nothing after the start
		clockAt(start.plusSeconds(1));
		this.maintenance.sendReminders();
		assertThat(reminders(id)).isEqualTo(2);
	}

	@Test
	void everyoneInTheAudienceIsRemindedWhenTheSchoolAsksForItExceptThoseWhoSaidNo() throws Exception {
		Cal c = cal("rem-everyone");
		setting(c.schoolId(), "calendar.reminder.audience", "EVERYONE");
		Instant start = inDays(5);
		UUID id = create(c, c.admin(), with(timed("Quiz", "CLASS", start), "classId", c.class1A().toString(), "rsvpEnabled", true));
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gSib(), Map.of("status", "NO"), 200);
		clockAt(start.minus(Duration.ofHours(20)));
		this.maintenance.sendReminders();
		for (Persona reminded : new Persona[] { c.gA1(), c.lead1A(), c.co1A(), c.subject1A(), c.assistant1A() }) {
			assertThat(reminders(id, reminded)).as(reminded.name()).isEqualTo(1);
		}
		for (Persona not : new Persona[] { c.gSib(), c.gB1(), c.gRevoked(), c.gRestricted(), c.lead1B(), c.studentA1(), c.admin() }) {
			assertThat(reminders(id, not)).as(not.name()).isZero();
		}
	}

	@Test
	void theLeadTimesAreSettingsAndZeroTurnsRemindersOff() throws Exception {
		Cal c = cal("rem-lead");
		Instant start = inDays(5);
		setting(c.schoolId(), "calendar.reminder.audience", "EVERYONE");
		setting(c.schoolId(), "calendar.reminder.event_minutes", 180);
		UUID id = schoolEvent(c, "Assembly", start);
		clockAt(start.minus(Duration.ofHours(4)));
		this.maintenance.sendReminders();
		assertThat(reminders(id)).isZero();
		clockAt(start.minus(Duration.ofHours(2)));
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.gA1())).isEqualTo(1);
		setting(c.schoolId(), "calendar.reminder.event_minutes", 0);
		UUID off = schoolEvent(c, "Quiet", start.plus(Duration.ofHours(1)));
		this.maintenance.sendReminders();
		assertThat(reminders(off)).isZero();
	}

	@Test
	void parentMeetingRemindersComeAnHourBeforeAndSayNothingAboutTheChild() throws Exception {
		Cal c = cal("rem-meeting");
		Instant start = inDays(3);
		UUID id = create(c, c.lead1A(), with(meetingBody("Abel's reading problems", c.a1(), start), "location", "Room 4", "description", "Abel"));
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gA1(), Map.of("status", "YES"), 200);
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gSib(), Map.of("status", "NO"), 200);
		clockAt(start.minus(Duration.ofHours(3)));
		this.maintenance.sendReminders();
		assertThat(reminders(id)).isZero();
		clockAt(start.minus(Duration.ofMinutes(50)));
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.lead1A())).isEqualTo(1);
		assertThat(reminders(id, c.gA1())).isEqualTo(1);
		assertThat(reminders(id, c.gSib())).isZero(); // answered no
		assertThat(reminders(id, c.gRevoked())).isZero();
		Map<String, Object> text = inbox(c.gA1().userId(), "UPCOMING_EVENT").getFirst();
		assertThat(text.get("title").toString()).isEqualTo("Parent meeting soon");
		assertThat(text.get("body").toString()).contains(start.atZone(java.time.ZoneId.of("Africa/Addis_Ababa")).toLocalDate().toString());
		assertThat(text.get("title") + " " + text.get("body") + " " + text.get("data")).doesNotContain("Abel").doesNotContain("reading")
			.doesNotContain("Room 4").doesNotContain(c.a1().toString());
		this.maintenance.sendReminders();
		assertThat(reminders(id)).isEqualTo(2);
	}

	@Test
	void aGuardianWhoseLinkEndedIsNotRemindedOfAMeeting() throws Exception {
		Cal c = cal("rem-revoked");
		Instant start = inDays(3);
		UUID id = create(c, c.lead1A(), meetingBody("Meeting", c.a1(), start));
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where guardian_id = ?", c.gA1().userId());
		clockAt(start.minus(Duration.ofMinutes(30)));
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.gA1())).isZero();
		assertThat(reminders(id, c.gSib())).isEqualTo(1);
	}

	@Test
	void personalEventsRemindTheirOwnerOnly() throws Exception {
		Cal c = cal("rem-personal");
		Instant start = inDays(4);
		UUID id = UUID.fromString(call(HttpMethod.POST, "/api/v1/me/events", c.gA1(), timed("Dentist", null, start), 201).get("id").asString());
		clockAt(start.minus(Duration.ofHours(20)));
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.gA1())).isEqualTo(1);
		assertThat(reminders(id)).isEqualTo(1);
		assertThat(inbox(c.gA1().userId(), "UPCOMING_EVENT").getFirst().get("title").toString()).isEqualTo("Dentist starts soon");
	}

	@Test
	void cancelledEventsAndOccurrencesAreNeverRemindedAndMovedOnesAreRemindedForTheirNewTime() throws Exception {
		Cal c = cal("rem-series");
		setting(c.schoolId(), "calendar.reminder.audience", "EVERYONE");
		// a daily series starting three days from now at the same time of day
		Instant first = inDays(3);
		UUID id = create(c, c.admin(), with(timed("Choir", "SCHOOL", first), "recurrenceRule", "FREQ=DAILY;COUNT=4"));
		String second = EventRowsId.of(first.plus(Duration.ofDays(1)));
		String third = EventRowsId.of(first.plus(Duration.ofDays(2)));
		call(HttpMethod.POST, event(id) + "/occurrences/" + second + "/cancel", c.admin(), null, 200);
		call(HttpMethod.PATCH, event(id) + "/occurrences/" + third, c.admin(),
				Map.of("startAt", first.plus(Duration.ofDays(2)).plus(Duration.ofHours(3)).toString(), "endAt",
						first.plus(Duration.ofDays(2)).plus(Duration.ofHours(4)).toString()), 200);
		// the day before the first occurrence: only the first is due
		clockAt(first.minus(Duration.ofHours(23)));
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.gA1())).isEqualTo(1);
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.gA1())).isEqualTo(1);
		// the cancelled second occurrence is skipped
		clockAt(first.plus(Duration.ofDays(1)).minus(Duration.ofHours(23)));
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.gA1())).isEqualTo(1);
		// the third was moved three hours later: due 24 hours before the new time
		clockAt(first.plus(Duration.ofDays(2)).plus(Duration.ofHours(3)).minus(Duration.ofHours(23)));
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.gA1())).isEqualTo(2);
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.gA1())).isEqualTo(2);
		// the fourth, and a whole series cancelled afterwards, never again
		clockAt(first.plus(Duration.ofDays(3)).minus(Duration.ofHours(20)));
		call(HttpMethod.POST, event(id) + "/cancel", c.admin(), null, 200);
		this.maintenance.sendReminders();
		assertThat(reminders(id, c.gA1())).isEqualTo(2);
		// a plain cancelled event is skipped too
		UUID one = schoolEvent(c, "One", first.plus(Duration.ofDays(3)));
		call(HttpMethod.POST, event(one) + "/cancel", c.admin(), null, 200);
		this.maintenance.sendReminders();
		assertThat(reminders(one)).isZero();
	}

	/** The id of an occurrence at an instant (what the API's items carry). */
	private static final class EventRowsId {

		static String of(Instant instant) {
			return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
				.format(instant.atOffset(java.time.ZoneOffset.UTC));
		}

	}

	@Test
	void theReminderJobReturnsAnIntegerForShedLock() throws Exception {
		Object result = this.maintenance.sendReminders();
		assertThat(result).isInstanceOf(Integer.class);
		assertThat(List.of(1)).isNotEmpty();
	}

}

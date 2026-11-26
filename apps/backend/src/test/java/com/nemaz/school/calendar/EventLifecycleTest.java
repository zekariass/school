package com.nemaz.school.calendar;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Changing, cancelling and deleting events, who is told, answers to ordinary events. */
@IntegrationTest
class EventLifecycleTest extends CalendarTestSupport {

	private static final Instant WHEN = Instant.parse("2027-02-10T06:00:00Z");

	private UUID classEventBy(Cal c, Persona by) throws Exception {
		return create(c, by, with(timed("Quiz", "CLASS", WHEN), "classId", c.class1A().toString()));
	}

	// --- who may change ------------------------------------------------------------------------

	@Test
	void classEventsAreChangedByTheClassesLeadsAndManagersAndSchoolEventsByManagers() throws Exception {
		Cal c = cal("life-who");
		UUID cls = classEventBy(c, c.lead1A());
		JsonNode seen = get(event(cls), c.co1A());
		assertThat(seen.get("capabilities").get("canEdit").asBoolean()).isTrue();
		assertThat(get(event(cls), c.subject1A()).get("capabilities").get("canEdit").asBoolean()).isFalse();
		assertThat(get(event(cls), c.gA1()).get("capabilities").get("canEdit").asBoolean()).isFalse();
		call(HttpMethod.PATCH, event(cls), c.co1A(), Map.of("title", "Quiz 2"), 200); // a co-teacher of the class
		call(HttpMethod.PATCH, event(cls), c.admin(), Map.of("title", "Quiz 3"), 200);
		for (Persona nope : new Persona[] { c.subject1A(), c.assistant1A(), c.gA1(), c.studentA1() }) {
			assertThat(failure(HttpMethod.PATCH, event(cls), nope, Map.of("title", "x"), 403)).as(nope.name()).isEqualTo("event.access_denied");
			assertThat(failure(HttpMethod.POST, event(cls) + "/cancel", nope, null, 403)).isEqualTo("event.access_denied");
			assertThat(failure(HttpMethod.DELETE, event(cls), nope, null, 403)).isEqualTo("event.access_denied");
		}
		assertThat(failure(HttpMethod.PATCH, event(cls), c.lead1B(), Map.of("title", "x"), 404)).isEqualTo("event.not_found");
		UUID school = schoolEvent(c, "School day", WHEN);
		for (Persona nope : new Persona[] { c.lead1A(), c.staff(), c.gA1(), c.studentA1() }) {
			assertThat(failure(HttpMethod.PATCH, event(school), nope, Map.of("title", "x"), 403)).as(nope.name()).isEqualTo("event.access_denied");
		}
		// a teacher who is no longer the class's lead cannot change what they made; one the school switched off cannot either
		this.jdbc.update("update class_teachers set role = 'ASSISTANT' where teacher_id = ?", c.lead1A().userId());
		assertThat(failure(HttpMethod.PATCH, event(cls), c.lead1A(), Map.of("title", "x"), 403)).isEqualTo("event.access_denied");
		setting(c.schoolId(), "calendar.teachers_can_create_class_events", false);
		assertThat(failure(HttpMethod.PATCH, event(cls), c.co1A(), Map.of("title", "x"), 403)).isEqualTo("event.access_denied");
		call(HttpMethod.PATCH, event(cls), c.admin(), Map.of("title", "Admin still"), 200);
	}

	@Test
	void changesKeepTheKindOfEventAndValidateTheTimes() throws Exception {
		Cal c = cal("life-edit");
		UUID id = schoolEvent(c, "Fair", WHEN);
		JsonNode moved = call(HttpMethod.PATCH, event(id), c.admin(),
				Map.of("startAt", "2027-02-11T08:00:00Z", "endAt", "2027-02-11T09:00:00Z", "description", "Bring lunch", "location", "Yard"), 200);
		assertThat(moved.get("startAt").asString()).isEqualTo("2027-02-11T08:00:00Z");
		assertThat(moved.get("description").asString()).isEqualTo("Bring lunch");
		// "" removes a description or a place
		JsonNode cleared = call(HttpMethod.PATCH, event(id), c.admin(), Map.of("description", "", "location", ""), 200);
		assertThat(cleared.path("description").isMissingNode() || cleared.get("description").isNull()).isTrue();
		// timed becomes all-day only with dates, and back only with instants
		assertThat(failure(HttpMethod.PATCH, event(id), c.admin(), Map.of("allDay", true), 400)).isEqualTo("event.time_invalid");
		JsonNode allDay = call(HttpMethod.PATCH, event(id), c.admin(), Map.of("allDay", true, "startDate", "2027-02-11", "endDate", "2027-02-12"), 200);
		assertThat(allDay.get("allDay").asBoolean()).isTrue();
		assertThat(allDay.get("endAt").asString()).isEqualTo("2027-02-12T21:00:00Z");
		assertThat(failure(HttpMethod.PATCH, event(id), c.admin(), Map.of("allDay", false), 400)).isEqualTo("event.time_invalid");
		JsonNode timed = call(HttpMethod.PATCH, event(id), c.admin(), Map.of("allDay", false, "startAt", WHEN.toString(), "endAt", WHEN.plusSeconds(60).toString()), 200);
		assertThat(timed.get("allDay").asBoolean()).isFalse();
		assertThat(timed.path("startDate").isMissingNode() || timed.get("startDate").isNull()).isTrue();
		assertThat(failure(HttpMethod.PATCH, event(id), c.admin(), Map.of("startAt", WHEN.toString(), "endAt", WHEN.minusSeconds(1).toString()), 400))
			.isEqualTo("event.time_invalid");
		assertThat(failure(HttpMethod.PATCH, event(id), c.admin(), Map.of("timeZone", "Nowhere/City"), 400)).isEqualTo("event.time_zone_invalid");
		assertThat(failure(HttpMethod.PATCH, event(id), c.admin(), Map.of("title", " "), 400)).isEqualTo("event.title_required");
		// nothing to change is not an error
		call(HttpMethod.PATCH, event(id), c.admin(), Map.of(), 200);
		// the scope and the kind of event cannot be changed: unknown fields are ignored, the event stays what it is
		JsonNode same = call(HttpMethod.PATCH, event(id), c.admin(), Map.of("scope", "CLASS", "classId", c.class1A().toString()), 200);
		assertThat(same.get("scope").asString()).isEqualTo("SCHOOL");
	}

	// --- who is told ---------------------------------------------------------------------------

	@Test
	void cancellingAndMovingTellTheAudienceButAQuietEditDoesNot() throws Exception {
		Cal c = cal("life-notify");
		UUID id = classEventBy(c, c.admin());
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		call(HttpMethod.PATCH, event(id), c.admin(), Map.of("title", "Quiz renamed", "description", "More"), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where source_id = ?", Integer.class, id)).isZero();
		call(HttpMethod.PATCH, event(id), c.admin(), Map.of("startAt", "2027-02-10T07:00:00Z", "endAt", "2027-02-10T08:00:00Z"), 200);
		// the guardians of the class's students and the class's teachers, nobody else, never the actor
		for (Persona told : new Persona[] { c.gA1(), c.gSib(), c.lead1A(), c.co1A(), c.subject1A(), c.assistant1A() }) {
			assertThat(count(told.userId(), "EVENT_UPDATED")).as(told.name()).isEqualTo(1);
		}
		for (Persona not : new Persona[] { c.admin(), c.studentA1(), c.gB1(), c.gX(), c.gPending(), c.gRestricted(), c.gRevoked(), c.lead1B(), c.lead2A(),
				c.staff(), c.outsider() }) {
			assertThat(count(not.userId(), "EVENT_UPDATED")).as(not.name()).isZero();
		}
		Map<String, Object> text = inbox(c.gA1().userId(), "EVENT_UPDATED").getFirst();
		assertThat(text.get("title").toString()).isEqualTo("Changed: Quiz renamed");
		assertThat(text.get("body").toString()).contains("2027-02-10 10:00").doesNotContain("More");
		// a change of place alone counts
		call(HttpMethod.PATCH, event(id), c.admin(), Map.of("location", "Room 9"), 200);
		assertThat(count(c.gA1().userId(), "EVENT_UPDATED")).isEqualTo(2);
		call(HttpMethod.POST, event(id) + "/cancel", c.admin(), Map.of("reason", "Flood"), 200);
		assertThat(count(c.gA1().userId(), "EVENT_CANCELLED")).isEqualTo(1);
		assertThat(count(c.lead1A().userId(), "EVENT_CANCELLED")).isEqualTo(1);
		assertThat(count(c.gB1().userId(), "EVENT_CANCELLED")).isZero();
		assertThat(inbox(c.gA1().userId(), "EVENT_CANCELLED").getFirst().get("title").toString()).isEqualTo("Cancelled: Quiz renamed");
		// again: nothing new
		call(HttpMethod.POST, event(id) + "/cancel", c.admin(), null, 200);
		assertThat(count(c.gA1().userId(), "EVENT_CANCELLED")).isEqualTo(1);
	}

	@Test
	void theSchoolCanSwitchOffTheNotificationsOfChanges() throws Exception {
		Cal c = cal("life-quiet");
		setting(c.schoolId(), "calendar.notify.changes", false);
		UUID id = schoolEvent(c, "Fair", WHEN);
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		call(HttpMethod.PATCH, event(id), c.admin(), Map.of("startAt", "2027-02-10T07:00:00Z", "endAt", "2027-02-10T08:00:00Z"), 200);
		call(HttpMethod.POST, event(id) + "/cancel", c.admin(), null, 200);
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where source_id = ?", Integer.class, id)).isZero();
		// but a parent meeting always tells its people
		UUID meeting = meeting(c, c.lead1A(), c.a1());
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		call(HttpMethod.POST, event(meeting) + "/cancel", c.lead1A(), null, 200);
		assertThat(count(c.gA1().userId(), "EVENT_CANCELLED")).isEqualTo(1);
	}

	@Test
	void ordinaryEventsDoNotPushWhenCreatedUnlessTheSchoolAsksFor() throws Exception {
		Cal c = cal("life-new");
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		UUID quiet = classEventBy(c, c.lead1A());
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where source_id = ?", Integer.class, quiet)).isZero();
		setting(c.schoolId(), "calendar.notify.new_events", true);
		UUID loud = create(c, c.lead1A(), with(timed("Quiz 2", "CLASS", WHEN), "classId", c.class1A().toString(), "location", "Room 1",
				"description", "Chapter 3"));
		for (Persona told : new Persona[] { c.gA1(), c.gSib(), c.co1A(), c.subject1A(), c.assistant1A() }) {
			assertThat(count(told.userId(), "EVENT_INVITATION")).as(told.name()).isEqualTo(1);
		}
		for (Persona not : new Persona[] { c.lead1A(), c.studentA1(), c.gB1(), c.gX(), c.gPending(), c.gRestricted(), c.gRevoked(), c.lead1B() }) {
			assertThat(count(not.userId(), "EVENT_INVITATION")).as(not.name()).isZero();
		}
		Map<String, Object> text = inbox(c.gA1().userId(), "EVENT_INVITATION").getFirst();
		assertThat(text.get("title").toString()).isEqualTo("New event: Quiz 2");
		assertThat(text.get("body").toString()).doesNotContain("Room 1").doesNotContain("Chapter 3");
		assertThat(text.get("data").toString()).contains("/calendar/events/" + loud);
		// a school-wide event reaches every guardian and member of staff of the school; a campus event those of the campus
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		schoolEvent(c, "Founders day", WHEN);
		for (Persona told : new Persona[] { c.gA1(), c.gB1(), c.gX(), c.lead1A(), c.lead1B(), c.lead2A(), c.staff() }) {
			assertThat(count(told.userId(), "EVENT_INVITATION")).as(told.name()).isEqualTo(1);
		}
		assertThat(count(c.gRevoked().userId(), "EVENT_INVITATION")).isZero();
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		create(c, c.admin(), with(timed("Campus 2 fair", "CAMPUS", WHEN), "campusId", c.campus2().toString()));
		for (Persona told : new Persona[] { c.gX(), c.lead2A(), c.staff() }) {
			assertThat(count(told.userId(), "EVENT_INVITATION")).as(told.name()).isEqualTo(1);
		}
		for (Persona not : new Persona[] { c.gA1(), c.gB1(), c.lead1A(), c.lead1B() }) {
			assertThat(count(not.userId(), "EVENT_INVITATION")).as(not.name()).isZero();
		}
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		create(c, c.admin(), with(timed("Grade trip", "GRADE_LEVEL", WHEN), "gradeLevelId", c.grade().toString()));
		for (Persona told : new Persona[] { c.gA1(), c.gB1(), c.lead1A(), c.lead1B() }) {
			assertThat(count(told.userId(), "EVENT_INVITATION")).as(told.name()).isEqualTo(1);
		}
		for (Persona not : new Persona[] { c.gX(), c.lead2A(), c.staff() }) {
			assertThat(count(not.userId(), "EVENT_INVITATION")).as(not.name()).isZero();
		}
		// personal events notify nobody
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		call(HttpMethod.POST, "/api/v1/me/events", c.gA1(), timed("Mine", null, WHEN), 201);
		assertThat(count(c.gA1().userId(), "EVENT_INVITATION") + count(c.gSib().userId(), "EVENT_INVITATION")).isZero();
	}

	// --- cancel and delete ----------------------------------------------------------------------

	@Test
	void anEventIsCancelledBeforeItIsDeletedAndNeverSilently() throws Exception {
		Cal c = cal("life-delete");
		UUID id = schoolEvent(c, "Fair", WHEN);
		assertThat(failure(HttpMethod.DELETE, event(id), c.admin(), null, 409)).isEqualTo("event.cancel_first");
		JsonNode cancelled = call(HttpMethod.POST, event(id) + "/cancel", c.admin(), Map.of("reason", "Rain"), 200);
		assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
		assertThat(cancelled.get("cancelledAt").asString()).isNotBlank();
		assertThat(failure(HttpMethod.PATCH, event(id), c.admin(), Map.of("title", "x"), 409)).isEqualTo("event.cancelled");
		assertThat(get(event(id), c.gA1()).get("capabilities").get("canDelete").asBoolean()).isFalse();
		assertThat(get(event(id), c.admin()).get("capabilities").get("canDelete").asBoolean()).isTrue();
		call(HttpMethod.DELETE, event(id), c.admin(), null, 204);
		assertThat(failure(HttpMethod.GET, event(id), c.admin(), null, 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.DELETE, event(id), c.admin(), null, 404)).isEqualTo("event.not_found");
		// the row is kept (soft delete) and every step is in the audit trail without the title
		assertThat(this.jdbc.queryForObject("select deleted_at is not null from events where id = ?", Boolean.class, id)).isTrue();
		var actions = this.jdbc.queryForList("select action from audit_logs where target_id = ? order by id", String.class, id);
		assertThat(actions).containsSubsequence("event.create", "event.cancel", "event.delete");
		assertThat(this.jdbc.queryForList("select metadata::text from audit_logs where target_id = ?", String.class, id).toString())
			.doesNotContain("Fair").doesNotContain("Rain");
	}

	// --- answers to ordinary events -------------------------------------------------------------

	@Test
	void anyoneWhoSeesAnEventThatAsksCanAnswerAndOnlyManagersSeeTheTotals() throws Exception {
		Cal c = cal("life-rsvp");
		Instant start = Instant.now().plus(Duration.ofDays(5));
		UUID id = create(c, c.admin(), with(timed("Open day", "SCHOOL", start), "rsvpEnabled", true));
		UUID plain = schoolEvent(c, "No answers", start);
		String rsvp = event(id) + "/rsvp";
		assertThat(get(event(id), c.gA1()).get("rsvp").get("enabled").asBoolean()).isTrue();
		assertThat(get(event(id), c.gA1()).get("rsvp").path("mine").isMissingNode()).isTrue();
		call(HttpMethod.PUT, rsvp, c.gA1(), Map.of("status", "YES"), 200);
		call(HttpMethod.PUT, rsvp, c.gSib(), Map.of("status", "MAYBE"), 200);
		call(HttpMethod.PUT, rsvp, c.gB1(), Map.of("status", "NO"), 200);
		call(HttpMethod.PUT, rsvp, c.studentA1(), Map.of("status", "YES"), 200);
		call(HttpMethod.PUT, rsvp, c.lead1A(), Map.of("status", "YES"), 200);
		call(HttpMethod.PUT, rsvp, c.gA1(), Map.of("status", "NO"), 200); // changed their mind
		JsonNode counts = get(event(id), c.admin()).get("rsvp").get("counts");
		assertThat(counts.get("yes").asInt()).isEqualTo(2);
		assertThat(counts.get("no").asInt()).isEqualTo(2);
		assertThat(counts.get("maybe").asInt()).isEqualTo(1);
		// totals only: no names, and nothing at all for people who are not managers
		assertThat(get(event(id), c.admin()).get("rsvp").path("participants").isMissingNode()).isTrue();
		assertThat(get(event(id), c.gA1()).get("rsvp").path("counts").isMissingNode()).isTrue();
		assertThat(get(event(id), c.lead1A()).get("rsvp").path("counts").isMissingNode()).isTrue();
		assertThat(get(event(id), c.gA1()).get("rsvp").get("mine").asString()).isEqualTo("NO");
		// not for people who cannot see the event, not for events that do not ask, not after the start
		assertThat(failure(HttpMethod.PUT, rsvp, c.gRevoked(), Map.of("status", "YES"), 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.PUT, rsvp, c.outsider(), Map.of("status", "YES"), 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.PUT, event(plain) + "/rsvp", c.gA1(), Map.of("status", "YES"), 409)).isEqualTo("event.rsvp_disabled");
		// moving the time asks again
		call(HttpMethod.PATCH, event(id), c.admin(), Map.of("startAt", start.plusSeconds(86400).toString(), "endAt", start.plusSeconds(90000).toString()), 200);
		JsonNode after = get(event(id), c.admin()).get("rsvp").get("counts");
		assertThat(after.get("pending").asInt()).isEqualTo(5);
		assertThat(after.get("yes").asInt()).isZero();
		assertThat(get(event(id), c.gA1()).get("rsvp").get("mine").asString()).isEqualTo("PENDING");
		clockAt(start.plus(Duration.ofDays(2)));
		assertThat(failure(HttpMethod.PUT, rsvp, c.gA1(), Map.of("status", "YES"), 409)).isEqualTo("event.rsvp_closed");
	}

	@Test
	void anEventCannotAskForAnswersAndRepeat() throws Exception {
		Cal c = cal("life-rsvp-rule");
		UUID series = create(c, c.admin(), with(timed("Weekly", "SCHOOL", WHEN), "recurrenceRule", "FREQ=WEEKLY"));
		assertThat(failure(HttpMethod.PATCH, event(series), c.admin(), Map.of("rsvpEnabled", true), 400)).isEqualTo("event.rsvp_unsupported");
		UUID single = create(c, c.admin(), with(timed("Once", "SCHOOL", WHEN), "rsvpEnabled", true));
		assertThat(failure(HttpMethod.PATCH, event(single), c.admin(), Map.of("recurrenceRule", "FREQ=WEEKLY"), 400)).isEqualTo("event.rsvp_unsupported");
		call(HttpMethod.PATCH, event(single), c.admin(), Map.of("rsvpEnabled", false, "recurrenceRule", "FREQ=WEEKLY"), 200);
	}

}

package com.nemaz.school.calendar;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Recurring events through the API: expansion for a window, daylight saving, UNTIL and COUNT, exceptions, rules refused. */
@IntegrationTest
class RecurringEventsApiTest extends CalendarTestSupport {

	private List<String> starts(JsonNode page) {
		return items(page).stream().map((i) -> i.get("startAt").asString()).toList();
	}

	private List<String> statuses(JsonNode page) {
		return items(page).stream().map((i) -> i.get("status").asString()).toList();
	}

	private JsonNode window(Cal c, String from, String to, UUID eventId) throws Exception {
		JsonNode page = calendar(c.admin(), from, to, "schoolId=" + c.schoolId() + "&sources=EVENT");
		List<JsonNode> mine = items(page).stream().filter((i) -> eventId.toString().equals(i.get("eventId").asString())).toList();
		return this.json.createObjectNode().set("items", this.json.valueToTree(mine));
	}

	@Test
	void aWeeklyEventIsExpandedForTheWindowOnly() throws Exception {
		Cal c = cal("rec-weekly");
		// Mondays 09:00 in Addis Ababa (06:00Z) from 7 December 2026
		UUID id = create(c, c.admin(), with(timed("Assembly", "SCHOOL", at("2026-12-07T06:00:00Z")), "recurrenceRule", "FREQ=WEEKLY;BYDAY=MO"));
		JsonNode december = window(c, "2026-12-01T00:00:00Z", "2027-01-01T00:00:00Z", id);
		assertThat(starts(december)).containsExactly("2026-12-07T06:00:00Z", "2026-12-14T06:00:00Z", "2026-12-21T06:00:00Z",
				"2026-12-28T06:00:00Z");
		JsonNode first = items(december).get(0);
		assertThat(first.get("recurring").asBoolean()).isTrue();
		assertThat(first.get("occurrenceId").asString()).isEqualTo("20261207T060000Z");
		assertThat(first.get("id").asString()).isEqualTo(id + "@20261207T060000Z");
		assertThat(first.get("modified").asBoolean()).isFalse();
		// the series is stored once: no expanded rows
		assertThat(this.jdbc.queryForObject("select count(*) from events where school_id = ?", Integer.class, c.schoolId())).isEqualTo(1);
		// a window in the past of the series' start, and one far in the future (endless series)
		assertThat(items(window(c, "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z", id))).isEmpty();
		assertThat(starts(window(c, "2030-06-01T00:00:00Z", "2030-06-20T00:00:00Z", id))).containsExactly("2030-06-03T06:00:00Z",
				"2030-06-10T06:00:00Z", "2030-06-17T06:00:00Z");
		// the single event read shows the rule in canonical form
		JsonNode view = get(event(id), c.admin());
		assertThat(view.get("recurrenceRule").asString()).isEqualTo("FREQ=WEEKLY;BYDAY=MO");
		assertThat(view.get("recurring").asBoolean()).isTrue();
	}

	@Test
	void theWallClockTimeSurvivesADaylightSavingChange() throws Exception {
		Cal c = cal("rec-dst");
		// New York: Mondays 09:00 local; clocks go forward on Sunday 14 March 2027
		Instant first = at("2027-03-08T14:00:00Z"); // 09:00 EST
		UUID id = create(c, c.admin(), with(timed("Staff meeting", "SCHOOL", first), "timeZone", "America/New_York", "recurrenceRule",
				"FREQ=WEEKLY;COUNT=3"));
		JsonNode march = window(c, "2027-03-01T00:00:00Z", "2027-04-15T00:00:00Z", id);
		assertThat(starts(march)).containsExactly("2027-03-08T14:00:00Z", "2027-03-15T13:00:00Z", "2027-03-22T13:00:00Z");
		assertThat(items(march).get(1).get("timeZone").asString()).isEqualTo("America/New_York");
		assertThat(items(march).get(1).get("endAt").asString()).isEqualTo("2027-03-15T14:00:00Z");
		// Addis Ababa has no daylight saving: the instant never moves
		UUID addis = create(c, c.admin(), with(timed("Addis", "SCHOOL", at("2027-03-08T06:00:00Z")), "recurrenceRule", "FREQ=WEEKLY;COUNT=3"));
		assertThat(starts(window(c, "2027-03-01T00:00:00Z", "2027-04-15T00:00:00Z", addis))).containsExactly("2027-03-08T06:00:00Z",
				"2027-03-15T06:00:00Z", "2027-03-22T06:00:00Z");
	}

	@Test
	void untilAndCountEndTheSeries() throws Exception {
		Cal c = cal("rec-end");
		Instant start = at("2026-12-01T06:00:00Z");
		UUID count = create(c, c.admin(), with(timed("Count", "SCHOOL", start), "recurrenceRule", "FREQ=DAILY;COUNT=5"));
		UUID until = create(c, c.admin(), with(timed("Until", "SCHOOL", start), "recurrenceRule", "FREQ=DAILY;UNTIL=20261203"));
		UUID instant = create(c, c.admin(), with(timed("Instant", "SCHOOL", start), "recurrenceRule", "FREQ=DAILY;UNTIL=20261203T055959Z"));
		assertThat(items(window(c, "2026-11-01T00:00:00Z", "2027-02-01T00:00:00Z", count))).hasSize(5);
		assertThat(items(window(c, "2026-11-01T00:00:00Z", "2027-02-01T00:00:00Z", until))).hasSize(3);
		assertThat(items(window(c, "2026-11-01T00:00:00Z", "2027-02-01T00:00:00Z", instant))).hasSize(2);
		// a finite series says when it ends; an endless one does not
		assertThat(get(event(count), c.admin()).get("recurrenceEndsAt").asString()).isEqualTo("2026-12-05T07:00:00Z");
		UUID endless = create(c, c.admin(), with(timed("Endless", "SCHOOL", start), "recurrenceRule", "FREQ=DAILY"));
		assertThat(get(event(endless), c.admin()).path("recurrenceEndsAt").isMissingNode()).isTrue();
		// a series that has ended is not in a later window at all
		assertThat(items(window(c, "2027-06-01T00:00:00Z", "2027-07-01T00:00:00Z", count))).isEmpty();
	}

	@Test
	void anEndlessDailySeriesFillsAYearWindowWithinTheCaps() throws Exception {
		Cal c = cal("rec-caps");
		UUID id = create(c, c.admin(), with(timed("Daily", "SCHOOL", at("2026-12-01T06:00:00Z")), "recurrenceRule", "FREQ=DAILY"));
		JsonNode page = calendar(c.admin(), "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "schoolId=" + c.schoolId()
				+ "&sources=EVENT&limit=500");
		assertThat(items(page)).hasSize(365);
		assertThat(page.get("truncated").asBoolean()).isFalse();
		assertThat(id).isNotNull();
		// windows beyond a year are refused
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=2027-01-01T00:00:00Z&to=2028-01-03T00:00:00Z", c.admin(), null, 400))
			.isEqualTo("calendar.window_too_large");
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=2027-01-02T00:00:00Z&to=2027-01-01T00:00:00Z", c.admin(), null, 400))
			.isEqualTo("calendar.window_invalid");
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=2027-01-01T00:00:00Z&to=2027-01-01T00:00:00Z", c.admin(), null, 400))
			.isEqualTo("calendar.window_invalid");
	}

	@Test
	void rulesOutsideTheSubsetAreRefusedWithStableCodes() throws Exception {
		Cal c = cal("rec-refused");
		Instant start = at("2026-12-01T06:00:00Z"); // a Tuesday
		for (Map.Entry<String, String> entry : Map.of("FREQ=YEARLY", "event.recurrence_unsupported", "FREQ=WEEKLY;BYDAY=2TU",
				"event.recurrence_unsupported", "FREQ=MONTHLY;BYSETPOS=1;BYDAY=TU", "event.recurrence_unsupported", "garbage",
				"event.recurrence_invalid", "FREQ=DAILY;COUNT=5;UNTIL=20270101", "event.recurrence_invalid", "FREQ=DAILY;COUNT=1001",
				"event.recurrence_invalid", "FREQ=WEEKLY;BYDAY=MO", "event.recurrence_start_mismatch", "FREQ=DAILY;UNTIL=20261130",
				"event.recurrence_invalid", "FREQ=DAILY;UNTIL=20400101", "event.recurrence_too_long").entrySet()) {
			assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(),
					with(timed("x", "SCHOOL", start), "recurrenceRule", entry.getKey()), 400)).as(entry.getKey()).isEqualTo(entry.getValue());
		}
		// answers cannot be asked on a repeating event
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(),
				with(timed("x", "SCHOOL", start), "recurrenceRule", "FREQ=DAILY", "rsvpEnabled", true), 400)).isEqualTo("event.rsvp_unsupported");
	}

	@Test
	void allDayMultiDayOccurrencesKeepTheirLength() throws Exception {
		Cal c = cal("rec-allday");
		UUID id = create(c, c.admin(), with(allDay("Exams", "SCHOOL", "2027-03-30", "2027-03-31"), "recurrenceRule", "FREQ=MONTHLY;COUNT=3"));
		JsonNode page = window(c, "2027-03-01T00:00:00Z", "2027-07-01T00:00:00Z", id);
		List<JsonNode> occurrences = items(page);
		// monthly on the 30th: March, April and May all have a 30th
		assertThat(occurrences).hasSize(3);
		assertThat(occurrences.get(0).get("startDate").asString()).isEqualTo("2027-03-30");
		assertThat(occurrences.get(0).get("endDate").asString()).isEqualTo("2027-03-31");
		assertThat(occurrences.get(0).get("allDay").asBoolean()).isTrue();
		assertThat(occurrences.get(1).get("startDate").asString()).isEqualTo("2027-04-30");
		assertThat(occurrences.get(1).get("endDate").asString()).isEqualTo("2027-05-01");
	}

	// --- exceptions ---------------------------------------------------------------------------

	@Test
	void aCancelledOccurrenceStaysVisibleAsCancelledAndCanBePutBack() throws Exception {
		Cal c = cal("rec-cancel");
		UUID id = create(c, c.admin(), with(timed("Choir", "SCHOOL", at("2026-12-07T06:00:00Z")), "recurrenceRule", "FREQ=WEEKLY;COUNT=4"));
		String occurrence = "20261214T060000Z";
		JsonNode view = call(HttpMethod.POST, event(id) + "/occurrences/" + occurrence + "/cancel", c.admin(), Map.of("reason", "Holiday"), 200);
		assertThat(view.get("status").asString()).isEqualTo("SCHEDULED"); // the series goes on
		assertThat(view.get("overrides")).hasSize(1);
		assertThat(view.get("overrides").get(0).get("status").asString()).isEqualTo("CANCELLED");
		JsonNode page = window(c, "2026-12-01T00:00:00Z", "2027-01-15T00:00:00Z", id);
		assertThat(statuses(page)).containsExactly("SCHEDULED", "CANCELLED", "SCHEDULED", "SCHEDULED");
		// COUNT counts the cancelled occurrence too
		assertThat(items(page)).hasSize(4);
		// idempotent; an unknown occurrence is 404, a malformed id 400
		call(HttpMethod.POST, event(id) + "/occurrences/" + occurrence + "/cancel", c.admin(), null, 200);
		assertThat(this.jdbc.queryForObject("select count(*) from event_overrides where event_id = ?", Integer.class, id)).isEqualTo(1);
		assertThat(failure(HttpMethod.POST, event(id) + "/occurrences/20261215T060000Z/cancel", c.admin(), null, 404))
			.isEqualTo("event.occurrence_not_found");
		assertThat(failure(HttpMethod.POST, event(id) + "/occurrences/tomorrow/cancel", c.admin(), null, 400)).isEqualTo("event.occurrence_invalid");
		// put back
		call(HttpMethod.DELETE, event(id) + "/occurrences/" + occurrence, c.admin(), null, 200);
		assertThat(statuses(window(c, "2026-12-01T00:00:00Z", "2027-01-15T00:00:00Z", id))).containsOnly("SCHEDULED");
		// a one-off event has no occurrences
		UUID single = schoolEvent(c, "Once", at("2026-12-07T06:00:00Z"));
		assertThat(failure(HttpMethod.POST, event(single) + "/occurrences/20261207T060000Z/cancel", c.admin(), null, 409))
			.isEqualTo("event.not_recurring");
	}

	@Test
	void aModifiedOccurrenceMovesAloneAndKeepsItsId() throws Exception {
		Cal c = cal("rec-modify");
		UUID id = create(c, c.admin(), with(timed("Choir", "SCHOOL", at("2026-12-07T06:00:00Z")), "recurrenceRule", "FREQ=WEEKLY;COUNT=3"));
		String occurrence = "20261214T060000Z";
		JsonNode view = call(HttpMethod.PATCH, event(id) + "/occurrences/" + occurrence, c.admin(),
				Map.of("title", "Choir (hall)", "location", "Hall", "startAt", "2026-12-14T08:00:00Z", "endAt", "2026-12-14T09:30:00Z"), 200);
		assertThat(view.get("overrides").get(0).get("status").asString()).isEqualTo("MODIFIED");
		JsonNode page = window(c, "2026-12-01T00:00:00Z", "2027-01-15T00:00:00Z", id);
		assertThat(starts(page)).containsExactly("2026-12-07T06:00:00Z", "2026-12-14T08:00:00Z", "2026-12-21T06:00:00Z");
		JsonNode moved = items(page).get(1);
		assertThat(moved.get("modified").asBoolean()).isTrue();
		assertThat(moved.get("title").asString()).isEqualTo("Choir (hall)");
		assertThat(moved.get("location").asString()).isEqualTo("Hall");
		assertThat(moved.get("occurrenceId").asString()).isEqualTo(occurrence);
		assertThat(titles(page)).containsExactly("Choir", "Choir (hall)", "Choir");
		// an occurrence moved into a window from outside it appears there (original 21 December, moved to 25 December?)
		call(HttpMethod.PATCH, event(id) + "/occurrences/20261221T060000Z", c.admin(),
				Map.of("startAt", "2026-12-27T06:00:00Z", "endAt", "2026-12-27T07:00:00Z"), 200);
		assertThat(starts(window(c, "2026-12-26T00:00:00Z", "2026-12-29T00:00:00Z", id))).containsExactly("2026-12-27T06:00:00Z");
		assertThat(items(window(c, "2026-12-20T00:00:00Z", "2026-12-23T00:00:00Z", id))).isEmpty();
		// at most 7 days away, and in the same kind
		assertThat(failure(HttpMethod.PATCH, event(id) + "/occurrences/20261207T060000Z", c.admin(),
				Map.of("startAt", "2026-12-20T06:00:00Z", "endAt", "2026-12-20T07:00:00Z"), 400)).isEqualTo("event.occurrence_shift_too_large");
		assertThat(failure(HttpMethod.PATCH, event(id) + "/occurrences/20261207T060000Z", c.admin(),
				Map.of("startDate", "2026-12-08", "endDate", "2026-12-08"), 400)).isEqualTo("event.time_invalid");
		assertThat(failure(HttpMethod.PATCH, event(id) + "/occurrences/20261207T060000Z", c.admin(),
				Map.of("startAt", "2026-12-08T06:00:00Z"), 400)).isEqualTo("event.time_invalid");
		// changing a cancelled occurrence brings it back
		call(HttpMethod.POST, event(id) + "/occurrences/20261207T060000Z/cancel", c.admin(), null, 200);
		call(HttpMethod.PATCH, event(id) + "/occurrences/20261207T060000Z", c.admin(), Map.of("title", "Back"), 200);
		assertThat(titles(window(c, "2026-12-01T00:00:00Z", "2026-12-10T00:00:00Z", id))).containsExactly("Back");
	}

	@Test
	void changingTheSeriesClearsItsExceptions() throws Exception {
		Cal c = cal("rec-series");
		UUID id = create(c, c.admin(), with(timed("Choir", "SCHOOL", at("2026-12-07T06:00:00Z")), "recurrenceRule", "FREQ=WEEKLY;COUNT=4"));
		call(HttpMethod.POST, event(id) + "/occurrences/20261214T060000Z/cancel", c.admin(), null, 200);
		// a new title keeps them; a new time does not
		call(HttpMethod.PATCH, event(id), c.admin(), Map.of("title", "Choir 2"), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from event_overrides where event_id = ?", Integer.class, id)).isEqualTo(1);
		JsonNode moved = call(HttpMethod.PATCH, event(id), c.admin(), Map.of("startAt", "2026-12-07T07:00:00Z", "endAt", "2026-12-07T08:00:00Z"), 200);
		assertThat(moved.get("overrides")).isEmpty();
		assertThat(starts(window(c, "2026-12-01T00:00:00Z", "2027-01-15T00:00:00Z", id))).containsExactly("2026-12-07T07:00:00Z",
				"2026-12-14T07:00:00Z", "2026-12-21T07:00:00Z", "2026-12-28T07:00:00Z");
		// the rule can be changed or removed ("" turns a series into a single event)
		JsonNode single = call(HttpMethod.PATCH, event(id), c.admin(), Map.of("recurrenceRule", ""), 200);
		assertThat(single.get("recurring").asBoolean()).isFalse();
		assertThat(starts(window(c, "2026-12-01T00:00:00Z", "2027-01-15T00:00:00Z", id))).containsExactly("2026-12-07T07:00:00Z");
		// a rule must still match the first day
		assertThat(failure(HttpMethod.PATCH, event(id), c.admin(), Map.of("recurrenceRule", "FREQ=WEEKLY;BYDAY=TU"), 400))
			.isEqualTo("event.recurrence_start_mismatch");
	}

	@Test
	void whoMayChangeTheExceptionsOfAClassSeries() throws Exception {
		Cal c = cal("rec-class");
		UUID id = create(c, c.lead1A(), with(timed("Weekly quiz", "CLASS", at("2026-12-07T06:00:00Z")), "classId", c.class1A().toString(),
				"recurrenceRule", "FREQ=WEEKLY;COUNT=3"));
		String cancel = event(id) + "/occurrences/20261214T060000Z/cancel";
		assertThat(failure(HttpMethod.POST, cancel, c.subject1A(), null, 403)).isEqualTo("event.access_denied");
		assertThat(failure(HttpMethod.POST, cancel, c.gA1(), null, 403)).isEqualTo("event.access_denied");
		assertThat(failure(HttpMethod.POST, cancel, c.studentA1(), null, 403)).isEqualTo("event.access_denied");
		assertThat(failure(HttpMethod.POST, cancel, c.lead1B(), null, 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.POST, cancel, c.outsider(), null, 404)).isEqualTo("event.not_found");
		call(HttpMethod.POST, cancel, c.co1A(), null, 200);
		call(HttpMethod.POST, event(id) + "/occurrences/20261221T060000Z/cancel", c.lead1A(), null, 200);
		call(HttpMethod.POST, event(id) + "/occurrences/20261207T060000Z/cancel", c.admin(), null, 200);
	}

}

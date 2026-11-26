package com.nemaz.school.calendar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** The union calendar: native events with holidays and academic boundaries, source tags, paging and caps. */
@IntegrationTest
class CalendarUnionTest extends CalendarTestSupport {

	private void holiday(String country, UUID school, String en, String am, String type, String from, String to) {
		this.jdbc.update("""
				insert into holidays (id, country_code, school_id, name_i18n, holiday_type, starts_on, ends_on)
				values (?, ?, ?, cast(? as jsonb), ?, cast(? as date), cast(? as date))""", Ids.newId(), country, school,
				"{\"en\": \"" + en + "\", \"am\": \"" + am + "\"}", type, from, to);
	}

	private List<JsonNode> bySource(JsonNode page, String source) {
		return items(page).stream().filter((i) -> source.equals(i.get("source").asString())).toList();
	}

	@Test
	void holidaysComeFromTheCountryAndTheSchoolAndAreAllDayItems() throws Exception {
		Cal c = cal("union-holidays");
		// the school is in Kenya (only this test uses Kenyan national holidays): its country's holidays and its own breaks
		// count; another country's, another school's and those outside the window do not
		this.jdbc.update("update schools set country_code = 'KE' where id = ?", c.schoolId());
		holiday("KE", null, "Meskel", "መስቀል", "RELIGIOUS", "2026-09-27", "2026-09-28");
		holiday("ET", null, "Madaraka Day", "ማዳራካ", "NATIONAL", "2026-09-27", "2026-09-27");
		holiday(null, c.schoolId(), "Mid-term break", "የመካከለኛ እረፍት", "SCHOOL_BREAK", "2026-09-30", "2026-10-02");
		holiday(null, this.personas.fixture().schoolA(), "Other school break", "ሌላ", "SCHOOL_BREAK", "2026-09-29", "2026-09-29");
		holiday("KE", null, "October holiday", "ጥቅምት", "NATIONAL", "2026-10-20", "2026-10-20");
		JsonNode page = calendar(c.gA1(), "2026-09-01T00:00:00Z", "2026-10-10T00:00:00Z", null);
		List<JsonNode> holidays = bySource(page, "HOLIDAY");
		assertThat(holidays).hasSize(2);
		JsonNode meskel = holidays.get(0);
		assertThat(meskel.get("kind").asString()).isEqualTo("RELIGIOUS");
		assertThat(meskel.get("allDay").asBoolean()).isTrue();
		assertThat(meskel.get("startDate").asString()).isEqualTo("2026-09-27");
		assertThat(meskel.get("endDate").asString()).isEqualTo("2026-09-28");
		assertThat(meskel.get("timeZone").asString()).isEqualTo("Africa/Addis_Ababa");
		assertThat(meskel.get("startAt").asString()).isEqualTo("2026-09-26T21:00:00Z");
		assertThat(meskel.get("endAt").asString()).isEqualTo("2026-09-28T21:00:00Z");
		assertThat(meskel.get("schoolId").asString()).isEqualTo(c.schoolId().toString());
		assertThat(meskel.get("status").asString()).isEqualTo("SCHEDULED");
		// the name is in every language, and the title in the caller's (else the school's: Amharic here)
		assertThat(meskel.get("titleI18n").get("en").asString()).isEqualTo("Meskel");
		assertThat(meskel.get("title").asString()).isEqualTo("መስቀል");
		assertThat(holidays.get(1).get("title").asString()).isEqualTo("የመካከለኛ እረፍት");
		// a window that only touches the last day of a multi-day holiday still shows it
		assertThat(bySource(calendar(c.gA1(), "2026-09-28T12:00:00Z", "2026-09-29T00:00:00Z", null), "HOLIDAY")).hasSize(1);
		assertThat(bySource(calendar(c.gA1(), "2026-09-28T21:00:00Z", "2026-09-29T00:00:00Z", null), "HOLIDAY")).isEmpty();
		// everybody who can see the school gets them, nobody else
		for (Persona in : new Persona[] { c.admin(), c.lead1A(), c.studentA1(), c.staff() }) {
			assertThat(bySource(calendar(in, "2026-09-01T00:00:00Z", "2026-10-10T00:00:00Z", null), "HOLIDAY")).as(in.name()).hasSize(2);
		}
		for (Persona out : new Persona[] { c.outsider(), c.gRevoked(), c.gPending(), c.gRestricted() }) {
			assertThat(bySource(calendar(out, "2026-09-01T00:00:00Z", "2026-10-10T00:00:00Z", null), "HOLIDAY")).as(out.name()).isEmpty();
		}
		// another school's administrator sees their own school's data only
		assertThat(bySource(calendar(c.otherAdmin(), "2026-09-01T00:00:00Z", "2026-10-10T00:00:00Z", null), "HOLIDAY").stream()
			.map((i) -> i.get("schoolId").asString())).doesNotContain(c.schoolId().toString());
	}

	@Test
	void academicYearsAndPeriodsShowTheirBoundaries() throws Exception {
		Cal c = cal("union-periods");
		UUID year = this.personas.currentYear(c.schoolId()); // 2025-09-01 to 2026-07-31
		UUID period = Ids.newId();
		this.jdbc.update("""
				insert into academic_periods (id, academic_year_id, school_id, name, period_type, sequence, starts_on, ends_on)
				values (?, ?, ?, 'Term 2', 'TERM', 2, date '2026-02-01', date '2026-06-30')""", period, year, c.schoolId());
		JsonNode page = calendar(c.gA1(), "2026-01-15T00:00:00Z", "2026-08-15T00:00:00Z", null);
		List<JsonNode> periods = bySource(page, "ACADEMIC_PERIOD");
		assertThat(periods.stream().map((i) -> i.get("kind").asString())).containsExactly("PERIOD_START", "PERIOD_END", "YEAR_END");
		assertThat(periods.get(0).get("title").asString()).isEqualTo("Term 2");
		assertThat(periods.get(0).get("startDate").asString()).isEqualTo("2026-02-01");
		assertThat(periods.get(0).get("endDate").asString()).isEqualTo("2026-02-01");
		assertThat(periods.get(0).get("allDay").asBoolean()).isTrue();
		assertThat(periods.get(1).get("startDate").asString()).isEqualTo("2026-06-30");
		assertThat(periods.get(2).get("startDate").asString()).isEqualTo("2026-07-31");
		assertThat(periods.get(2).get("title").asString()).isEqualTo("Persona year");
		assertThat(periods.get(0).get("id").asString()).isEqualTo("PERIOD:" + period + ":START");
		// the year's start is in another window
		assertThat(bySource(calendar(c.gA1(), "2025-08-15T00:00:00Z", "2025-09-15T00:00:00Z", null), "ACADEMIC_PERIOD").stream()
			.map((i) -> i.get("kind").asString())).containsExactly("YEAR_START");
		// strangers see none
		assertThat(bySource(calendar(c.outsider(), "2026-01-15T00:00:00Z", "2026-08-15T00:00:00Z", null), "ACADEMIC_PERIOD")).isEmpty();
	}

	@Test
	void everyItemIsTaggedBySourceAndTheCallerCanChooseSources() throws Exception {
		Cal c = cal("union-sources");
		holiday(null, c.schoolId(), "Holiday", "በዓል", "SCHOOL_BREAK", "2027-02-11", "2027-02-11");
		UUID event = schoolEvent(c, "Event", at("2027-02-10T06:00:00Z"));
		call(HttpMethod.POST, "/api/v1/me/events", c.gA1(), timed("Mine", null, at("2027-02-12T06:00:00Z")), 201);
		String from = "2027-02-01T00:00:00Z";
		String to = "2027-03-01T00:00:00Z";
		JsonNode all = calendar(c.gA1(), from, to, null);
		assertThat(titles(all)).containsExactly("Event", "በዓል", "Mine");
		assertThat(items(all).stream().map((i) -> i.get("source").asString())).containsExactly("EVENT", "HOLIDAY", "PERSONAL_EVENT");
		assertThat(titles(c.gA1(), from, to, "sources=HOLIDAY")).containsExactly("በዓል");
		assertThat(titles(c.gA1(), from, to, "sources=event,personal_event")).containsExactly("Event", "Mine");
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=" + from + "&to=" + to + "&sources=EVENT,ASSIGNMENT", c.gA1(), null, 400))
			.isEqualTo("calendar.source_invalid");
		// naming a school or a child leaves the caller's private events out unless they ask for them
		assertThat(titles(c.gA1(), from, to, "schoolId=" + c.schoolId())).containsExactly("Event", "በዓል");
		assertThat(titles(c.gA1(), from, to, "schoolId=" + c.schoolId() + "&sources=PERSONAL_EVENT")).containsExactly("Mine");
		assertThat(titles(c.gA1(), from, to, "studentId=" + c.a1())).containsExactly("Event", "በዓል");
		// the union items carry the ids a client needs to open the event
		JsonNode item = items(all).get(0);
		assertThat(item.get("eventId").asString()).isEqualTo(event.toString());
		assertThat(item.get("id").asString()).isEqualTo(event.toString());
		assertThat(item.get("scope").asString()).isEqualTo("SCHOOL");
		assertThat(item.get("kind").asString()).isEqualTo("SCHOOL_EVENT");
		assertThat(item.path("description").isMissingNode() || item.get("description").isNull()).isTrue();
		// nobody's personal event leaks into somebody else's calendar
		assertThat(titles(c.gSib(), from, to, null)).containsExactly("Event", "በዓል");
	}

	@Test
	void cancelledEventsStayAndDeletedOnesGo() throws Exception {
		Cal c = cal("union-cancel");
		UUID cancelled = schoolEvent(c, "Cancelled one", at("2027-02-10T06:00:00Z"));
		UUID deleted = schoolEvent(c, "Deleted one", at("2027-02-11T06:00:00Z"));
		schoolEvent(c, "Kept", at("2027-02-12T06:00:00Z"));
		call(HttpMethod.POST, event(cancelled) + "/cancel", c.admin(), null, 200);
		call(HttpMethod.POST, event(deleted) + "/cancel", c.admin(), null, 200);
		call(HttpMethod.DELETE, event(deleted), c.admin(), null, 204);
		JsonNode page = calendar(c.gA1(), "2027-02-01T00:00:00Z", "2027-03-01T00:00:00Z", "sources=EVENT");
		assertThat(titles(page)).containsExactly("Cancelled one", "Kept");
		assertThat(items(page).get(0).get("status").asString()).isEqualTo("CANCELLED");
		assertThat(items(page).get(1).get("status").asString()).isEqualTo("SCHEDULED");
	}

	@Test
	void anEventThatStartsBeforeTheWindowButEndsInsideIsInIt() throws Exception {
		Cal c = cal("union-overlap");
		create(c, c.admin(), with(timed("Late night", "SCHOOL", at("2027-02-10T22:30:00Z")), "endAt", "2027-02-11T01:30:00Z"));
		assertThat(titles(c.gA1(), "2027-02-11T00:00:00Z", "2027-02-12T00:00:00Z", "sources=EVENT")).containsExactly("Late night");
		assertThat(titles(c.gA1(), "2027-02-11T01:30:00Z", "2027-02-12T00:00:00Z", "sources=EVENT")).isEmpty();
		assertThat(titles(c.gA1(), "2027-02-09T00:00:00Z", "2027-02-10T22:30:00Z", "sources=EVENT")).isEmpty();
	}

	@Test
	void pagesAreKeysetPagesOverStartAndId() throws Exception {
		Cal c = cal("union-paging");
		// three events at the same instant (ties are broken by id) and four later ones
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			expected.add(schoolEvent(c, "Tie " + i, at("2027-02-10T06:00:00Z")).toString());
		}
		for (int i = 0; i < 4; i++) {
			expected.add(schoolEvent(c, "Later " + i, at("2027-02-1" + (i + 1) + "T09:00:00Z")).toString());
		}
		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			JsonNode page = calendar(c.gA1(), "2027-02-01T00:00:00Z", "2027-03-01T00:00:00Z",
					"sources=EVENT&limit=3" + ((cursor != null) ? "&cursor=" + cursor : ""));
			items(page).forEach((i) -> seen.add(i.get("id").asString()));
			cursor = (page.path("nextCursor").isMissingNode() || page.get("nextCursor").isNull()) ? null : page.get("nextCursor").asString();
			pages++;
		}
		while (cursor != null && pages < 10);
		assertThat(pages).isEqualTo(3);
		assertThat(seen).hasSize(7).doesNotHaveDuplicates();
		assertThat(seen.subList(0, 3)).containsExactlyInAnyOrderElementsOf(expected.subList(0, 3));
		assertThat(seen.subList(3, 7)).containsExactlyElementsOf(expected.subList(3, 7));
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=2027-02-01T00:00:00Z&to=2027-03-01T00:00:00Z&cursor=garbage", c.gA1(), null, 400))
			.isEqualTo("invalid_cursor");
	}

	@Test
	void aWindowWithMoreThanTheServerReturnsSaysSo() throws Exception {
		Cal c = cal("union-truncated");
		this.jdbc.update("""
				insert into events (id, scope_type, school_id, event_type, title, start_at, end_at, timezone, created_by)
				select uuidv7(), 'SCHOOL', ?, 'SCHOOL_EVENT', 'bulk ' || g, timestamptz '2027-05-01 00:00:00+00' + g * interval '1 minute',
				       timestamptz '2027-05-01 00:00:00+00' + g * interval '1 minute' + interval '30 seconds', 'UTC', ?
				from generate_series(1, 2100) g""", c.schoolId(), c.admin().userId());
		JsonNode page = calendar(c.gA1(), "2027-05-01T00:00:00Z", "2027-06-01T00:00:00Z", "sources=EVENT&limit=500");
		assertThat(items(page)).hasSize(500);
		assertThat(page.get("truncated").asBoolean()).isTrue();
		assertThat(page.get("nextCursor").asString()).isNotBlank();
		// a smaller window is complete
		JsonNode small = calendar(c.gA1(), "2027-05-01T00:00:00Z", "2027-05-01T10:00:00Z", "sources=EVENT&limit=500");
		assertThat(small.get("truncated").asBoolean()).isFalse();
		assertThat(items(small)).hasSize(500);
		// limits are clamped, not refused
		assertThat(items(calendar(c.gA1(), "2027-05-01T00:00:00Z", "2027-06-01T00:00:00Z", "sources=EVENT&limit=100000"))).hasSize(500);
		assertThat(items(calendar(c.gA1(), "2027-05-01T00:00:00Z", "2027-06-01T00:00:00Z", "sources=EVENT&limit=0"))).hasSize(1);
	}

	@Test
	void personalEventsAppearInTheOwnersTimeAndZone() throws Exception {
		Cal c = cal("union-personal");
		JsonNode mine = call(HttpMethod.POST, "/api/v1/me/events", c.gA1(),
				with(allDay("Birthday", null, "2027-02-14", "2027-02-14"), "timeZone", "Africa/Nairobi"), 201);
		JsonNode page = calendar(c.gA1(), "2027-02-01T00:00:00Z", "2027-03-01T00:00:00Z", null);
		JsonNode item = items(page).getFirst();
		assertThat(item.get("source").asString()).isEqualTo("PERSONAL_EVENT");
		assertThat(item.get("startDate").asString()).isEqualTo("2027-02-14");
		assertThat(item.get("timeZone").asString()).isEqualTo("Africa/Nairobi");
		assertThat(mine.get("id").asString()).isEqualTo(item.get("eventId").asString());
		assertThat(items(calendar(c.gSib(), "2027-02-01T00:00:00Z", "2027-03-01T00:00:00Z", null))).isEmpty();
		assertThat(items(calendar(c.admin(), "2027-02-01T00:00:00Z", "2027-03-01T00:00:00Z", null))).isEmpty();
		// a person with no school at all still has a calendar
		assertThat(items(calendar(c.outsider(), "2027-02-01T00:00:00Z", "2027-03-01T00:00:00Z", null))).isEmpty();
	}

}

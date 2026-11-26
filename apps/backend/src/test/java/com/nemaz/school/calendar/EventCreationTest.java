package com.nemaz.school.calendar;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may create which events, how they are represented, and what is refused. */
@IntegrationTest
class EventCreationTest extends CalendarTestSupport {

	private static final Instant WHEN = Instant.parse("2027-02-10T06:00:00Z");

	@org.springframework.beans.factory.annotation.Autowired
	org.springframework.cache.CacheManager cacheManager;

	@Test
	void schoolCampusAndGradeEventsNeedTheCalendarPermission() throws Exception {
		Cal c = cal("create-wide");
		JsonNode school = call(HttpMethod.POST, c.schoolEvents(), c.admin(), timed("Founders day", "SCHOOL", WHEN), 201);
		assertThat(school.get("scope").asString()).isEqualTo("SCHOOL");
		assertThat(school.get("eventType").asString()).isEqualTo("SCHOOL_EVENT");
		assertThat(school.get("status").asString()).isEqualTo("SCHEDULED");
		assertThat(school.get("timeZone").asString()).isEqualTo("Africa/Addis_Ababa");
		assertThat(school.get("capabilities").get("canEdit").asBoolean()).isTrue();
		call(HttpMethod.POST, c.schoolEvents(), c.admin(),
				with(timed("Campus", "CAMPUS", WHEN), "campusId", c.campus1().toString(), "eventType", "EXAM"), 201);
		call(HttpMethod.POST, c.schoolEvents(), c.admin(), with(timed("Grade", "GRADE_LEVEL", WHEN), "gradeLevelId", c.grade().toString()),
				201);
		// teachers (even the lead of every class), students, guardians and staff cannot
		for (Persona nobody : new Persona[] { c.lead1A(), c.staff(), c.studentA1(), c.gA1() }) {
			assertThat(failure(HttpMethod.POST, c.schoolEvents(), nobody, timed("No", "SCHOOL", WHEN), 403))
				.as(nobody.name()).isEqualTo("event.create_denied");
		}
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), with(timed("No", "CAMPUS", WHEN), "campusId",
				c.campus1().toString()), 403)).isEqualTo("event.create_denied");
		// people who cannot see the school do not learn that it exists
		for (Persona stranger : new Persona[] { c.outsider(), c.otherAdmin(), c.gRevoked(), c.gPending(), c.gRestricted() }) {
			assertThat(failure(HttpMethod.POST, c.schoolEvents(), stranger, timed("No", "SCHOOL", WHEN), 404))
				.as(stranger.name()).isEqualTo("school.not_found");
		}
		call(HttpMethod.POST, c.schoolEvents(), null, timed("No", "SCHOOL", WHEN), 401);
	}

	@Test
	void classEventsAreForLeadsCoTeachersAndManagers() throws Exception {
		Cal c = cal("create-class");
		var body = with(timed("Quiz", "CLASS", WHEN), "classId", c.class1A().toString());
		JsonNode created = call(HttpMethod.POST, c.schoolEvents(), c.lead1A(), body, 201);
		assertThat(created.get("eventType").asString()).isEqualTo("CLASS_EVENT");
		assertThat(created.get("classId").asString()).isEqualTo(c.class1A().toString());
		call(HttpMethod.POST, c.schoolEvents(), c.co1A(), body, 201);
		call(HttpMethod.POST, c.schoolEvents(), c.admin(), with(body, "eventType", "EXAM"), 201);
		// a subject teacher and an assistant see the class but may not create its events
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.subject1A(), body, 403)).isEqualTo("event.create_denied");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.assistant1A(), body, 403)).isEqualTo("event.create_denied");
		// a guardian sees the class (their child is in it) and a student too: neither may create
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.gA1(), body, 403)).isEqualTo("event.create_denied");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.studentA1(), body, 403)).isEqualTo("event.create_denied");
		// the lead of another class does not even see class 1A
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead2A(), body, 404)).isEqualTo("class.not_found");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1B(), body, 404)).isEqualTo("class.not_found");
		// the setting can take the right away from teachers; the school's calendar managers keep it
		setting(c.schoolId(), "calendar.teachers_can_create_class_events", false);
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), body, 403)).isEqualTo("event.create_denied");
		call(HttpMethod.POST, c.schoolEvents(), c.admin(), body, 201);
		// an archived class takes no events; another school's class is unknown
		this.jdbc.update("update classes set status = 'ARCHIVED', archived_at = now() where id = ?", c.class2A());
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), with(body, "classId", c.class2A().toString()), 409))
			.isEqualTo("class.archived");
		UUID foreign = this.personas.fixture().class9B();
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), with(body, "classId", foreign.toString()), 404))
			.isEqualTo("class.not_found");
	}

	@Test
	void scopeTypeAndIdsMustAgree() throws Exception {
		Cal c = cal("create-scope");
		String url = c.schoolEvents();
		assertThat(failure(HttpMethod.POST, url, c.admin(), timed("x", null, WHEN), 400)).isEqualTo("event.scope_invalid");
		assertThat(failure(HttpMethod.POST, url, c.admin(), timed("x", "CAMPUS", WHEN), 400)).isEqualTo("event.scope_invalid");
		assertThat(failure(HttpMethod.POST, url, c.admin(), with(timed("x", "SCHOOL", WHEN), "classId", c.class1A().toString()), 400))
			.isEqualTo("event.scope_invalid");
		assertThat(failure(HttpMethod.POST, url, c.admin(), timed("x", "PERSONAL", WHEN), 400)).isEqualTo("event.scope_invalid");
		assertThat(failure(HttpMethod.POST, url, c.admin(), timed("x", "INVITEES", WHEN), 400)).isEqualTo("event.scope_invalid");
		// a campus or grade of another school is not accepted (and says nothing about whether it exists)
		UUID otherCampus = this.personas.fixture().campusA();
		assertThat(failure(HttpMethod.POST, url, c.admin(), with(timed("x", "CAMPUS", WHEN), "campusId", otherCampus.toString()), 400))
			.isEqualTo("event.audience_invalid");
		assertThat(failure(HttpMethod.POST, url, c.admin(),
				with(timed("x", "GRADE_LEVEL", WHEN), "gradeLevelId", this.personas.fixture().gradeA().toString()), 400))
			.isEqualTo("event.audience_invalid");
		// types follow the scope
		assertThat(failure(HttpMethod.POST, url, c.admin(), with(timed("x", "SCHOOL", WHEN), "eventType", "CLASS_EVENT"), 400))
			.isEqualTo("event.type_invalid");
		assertThat(failure(HttpMethod.POST, url, c.admin(),
				with(timed("x", "CLASS", WHEN), "classId", c.class1A().toString(), "eventType", "SCHOOL_EVENT"), 400))
			.isEqualTo("event.type_invalid");
		call(HttpMethod.POST, url, c.admin(), with(timed("x", "SCHOOL", WHEN), "eventType", "OTHER"), 201);
	}

	@Test
	void timedEventsKeepTheirInstantsAndZone() throws Exception {
		Cal c = cal("create-timed");
		JsonNode event = call(HttpMethod.POST, c.schoolEvents(), c.admin(),
				with(timed("Concert", "SCHOOL", WHEN), "timeZone", "America/New_York", "location", " Hall A "), 201);
		assertThat(event.get("allDay").asBoolean()).isFalse();
		assertThat(Instant.parse(event.get("startAt").asString())).isEqualTo(WHEN);
		assertThat(Instant.parse(event.get("endAt").asString())).isEqualTo(WHEN.plusSeconds(3600));
		assertThat(event.get("timeZone").asString()).isEqualTo("America/New_York");
		assertThat(event.get("location").asString()).isEqualTo("Hall A");
		assertThat(event.path("startDate").isMissingNode() || event.get("startDate").isNull()).isTrue();
		// end before start, a missing end, dates on a timed event and unknown zones are refused
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(),
				with(timed("x", "SCHOOL", WHEN), "endAt", WHEN.minusSeconds(1).toString()), 400)).isEqualTo("event.time_invalid");
		var noEnd = timed("x", "SCHOOL", WHEN);
		noEnd.remove("endAt");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), noEnd, 400)).isEqualTo("event.time_invalid");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), with(timed("x", "SCHOOL", WHEN), "startDate", "2027-02-10"), 400))
			.isEqualTo("event.time_invalid");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), with(timed("x", "SCHOOL", WHEN), "timeZone", "Mars/Base"), 400))
			.isEqualTo("event.time_zone_invalid");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), with(timed("x", "SCHOOL", WHEN), "timeZone", "+03:00"), 400))
			.isEqualTo("event.time_zone_invalid");
		// a campus event takes the campus's own zone when it has one
		this.jdbc.update("update campuses set timezone = 'Europe/Berlin' where id = ?", c.campus2());
		JsonNode campus = call(HttpMethod.POST, c.schoolEvents(), c.admin(),
				with(timed("Berlin", "CAMPUS", WHEN), "campusId", c.campus2().toString()), 201);
		assertThat(campus.get("timeZone").asString()).isEqualTo("Europe/Berlin");
	}

	@Test
	void allDayEventsAreCalendarDatesInTheEventsZone() throws Exception {
		Cal c = cal("create-allday");
		JsonNode one = call(HttpMethod.POST, c.schoolEvents(), c.admin(), allDay("Holiday", "SCHOOL", "2026-12-01", "2026-12-01"), 201);
		assertThat(one.get("allDay").asBoolean()).isTrue();
		assertThat(one.get("startDate").asString()).isEqualTo("2026-12-01");
		assertThat(one.get("endDate").asString()).isEqualTo("2026-12-01");
		// Addis Ababa is UTC+3 all year: the day starts at 21:00Z the evening before and ends exclusive at the next midnight
		assertThat(one.get("startAt").asString()).isEqualTo("2026-11-30T21:00:00Z");
		assertThat(one.get("endAt").asString()).isEqualTo("2026-12-01T21:00:00Z");
		JsonNode multi = call(HttpMethod.POST, c.schoolEvents(), c.admin(), allDay("Camp", "SCHOOL", "2026-12-01", "2026-12-03"), 201);
		assertThat(multi.get("startDate").asString()).isEqualTo("2026-12-01");
		assertThat(multi.get("endDate").asString()).isEqualTo("2026-12-03");
		assertThat(multi.get("endAt").asString()).isEqualTo("2026-12-03T21:00:00Z");
		// in a zone with daylight saving the calendar days do not change, the instants do (23-hour day, 11 March 2027 is not it:
		// New York springs forward on 14 March)
		JsonNode ny = call(HttpMethod.POST, c.schoolEvents(), c.admin(),
				with(allDay("Trip", "SCHOOL", "2027-03-13", "2027-03-14"), "timeZone", "America/New_York"), 201);
		assertThat(ny.get("startAt").asString()).isEqualTo("2027-03-13T05:00:00Z");
		assertThat(ny.get("endAt").asString()).isEqualTo("2027-03-15T04:00:00Z");
		assertThat(ny.get("endDate").asString()).isEqualTo("2027-03-14");
		// instants on an all-day event, a missing date and an end before the start are refused
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), with(allDay("x", "SCHOOL", "2026-12-01", "2026-12-01"),
				"startAt", WHEN.toString()), 400)).isEqualTo("event.time_invalid");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), allDay("x", "SCHOOL", "2026-12-03", "2026-12-01"), 400))
			.isEqualTo("event.time_invalid");
		var noEnd = allDay("x", "SCHOOL", "2026-12-03", "2026-12-03");
		noEnd.remove("endDate");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), noEnd, 400)).isEqualTo("event.time_invalid");
		// a year is the longest
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), allDay("x", "SCHOOL", "2026-12-01", "2028-01-01"), 400))
			.isEqualTo("event.time_invalid");
	}

	@Test
	void textIsPlainAndCleaned() throws Exception {
		Cal c = cal("create-text");
		JsonNode event = call(HttpMethod.POST, c.schoolEvents(), c.admin(),
				with(timed("<b>Fee‮sdrawkcab​ due</b>\u0007", "SCHOOL", WHEN), "description", "one\r\ntwo\rthree\u0000 <script>x</script>",
						"location", "Room​ 5"), 201);
		assertThat(event.get("title").asString()).isEqualTo("<b>Feesdrawkcab due</b>");
		assertThat(event.get("description").asString()).isEqualTo("one\ntwo\nthree <script>x</script>");
		assertThat(event.get("location").asString()).isEqualTo("Room 5");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), timed("  ​ ", "SCHOOL", WHEN), 400))
			.isEqualTo("event.title_required");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), timed("x".repeat(201), "SCHOOL", WHEN), 400))
			.isEqualTo("event.title_too_long");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(),
				with(timed("x", "SCHOOL", WHEN), "description", "d".repeat(5001)), 400)).isEqualTo("event.description_too_long");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), with(timed("x", "SCHOOL", WHEN), "location", "l".repeat(201)),
				400)).isEqualTo("event.location_too_long");
	}

	@Test
	void personalEventsBelongToTheirOwnerOnly() throws Exception {
		Cal c = cal("create-personal");
		Persona me = c.gA1();
		JsonNode mine = call(HttpMethod.POST, "/api/v1/me/events", me, with(timed("Dentist", null, WHEN), "timeZone", "Africa/Nairobi"), 201);
		assertThat(mine.get("scope").asString()).isEqualTo("PERSONAL");
		assertThat(mine.get("eventType").asString()).isEqualTo("OTHER");
		assertThat(mine.path("schoolId").isMissingNode() || mine.get("schoolId").isNull()).isTrue();
		UUID id = UUID.fromString(mine.get("id").asString());
		assertThat(get(event(id), me).get("title").asString()).isEqualTo("Dentist");
		// nobody else sees it: not the school's admin, not a teacher, not another guardian
		for (Persona other : new Persona[] { c.admin(), c.lead1A(), c.gSib(), c.outsider(), this.personas.platformAdmin() }) {
			assertThat(failure(HttpMethod.GET, event(id), other, null, 404)).as(other.name()).isEqualTo("event.not_found");
			assertThat(failure(HttpMethod.PATCH, event(id), other, java.util.Map.of("title", "x"), 404)).isEqualTo("event.not_found");
		}
		// no school, no scope, no answers; a personal event cannot pose as a school one
		assertThat(failure(HttpMethod.POST, "/api/v1/me/events", me, timed("x", "SCHOOL", WHEN), 400)).isEqualTo("event.scope_invalid");
		assertThat(failure(HttpMethod.POST, "/api/v1/me/events", me, with(timed("x", null, WHEN), "classId", c.class1A().toString()), 400))
			.isEqualTo("event.scope_invalid");
		assertThat(failure(HttpMethod.POST, "/api/v1/me/events", me, with(timed("x", null, WHEN), "rsvpEnabled", true), 400))
			.isEqualTo("event.rsvp_unsupported");
		assertThat(failure(HttpMethod.POST, "/api/v1/me/events", me, with(timed("x", null, WHEN), "eventType", "EXAM"), 400))
			.isEqualTo("event.type_invalid");
		// the owner may change and delete it at any time
		call(HttpMethod.PATCH, event(id), me, java.util.Map.of("title", "Dentist 2"), 200);
		call(HttpMethod.DELETE, event(id), me, null, 204);
		assertThat(failure(HttpMethod.GET, event(id), me, null, 404)).isEqualTo("event.not_found");
	}

	@Test
	void theCalendarFeatureSwitchIsHonoured() throws Exception {
		Cal c = cal("create-flag");
		UUID id = schoolEvent(c, "Before", WHEN);
		this.jdbc.update("insert into feature_overrides (feature_key, scope_type, school_id, enabled) values ('calendar', 'SCHOOL', ?, false)",
				c.schoolId());
		this.cacheManager.getCache("school-configuration").clear();
		try {
			assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.admin(), timed("x", "SCHOOL", WHEN), 403))
				.isEqualTo("calendar.feature_disabled");
			assertThat(failure(HttpMethod.GET, event(id), c.admin(), null, 403)).isEqualTo("calendar.feature_disabled");
			assertThat(failure(HttpMethod.GET, c.schoolEvents() + "/capabilities", c.admin(), null, 403))
				.isEqualTo("calendar.feature_disabled");
			assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=2027-01-01T00:00:00Z&to=2027-03-01T00:00:00Z&schoolId="
					+ c.schoolId(), c.admin(), null, 403)).isEqualTo("calendar.feature_disabled");
			// without a school named, the school is simply left out
			assertThat(titles(c.gA1(), "2027-01-01T00:00:00Z", "2027-03-01T00:00:00Z", null)).isEmpty();
			// a stranger still gets 404, not a hint about the switch
			assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.outsider(), timed("x", "SCHOOL", WHEN), 404)).isEqualTo("school.not_found");
		}
		finally {
			this.jdbc.update("delete from feature_overrides where feature_key = 'calendar' and school_id = ?", c.schoolId());
			this.cacheManager.getCache("school-configuration").clear();
		}
		assertThat(titles(c.gA1(), "2027-01-01T00:00:00Z", "2027-03-01T00:00:00Z", null)).contains("Before");
	}

	@Test
	void capabilitiesTellMenusWhatToOffer() throws Exception {
		Cal c = cal("create-caps");
		JsonNode admin = get(c.schoolEvents() + "/capabilities", c.admin());
		assertThat(admin.get("canManage").asBoolean()).isTrue();
		assertThat(admin.get("canCreateAnyClassEvent").asBoolean()).isTrue();
		JsonNode lead = get(c.schoolEvents() + "/capabilities", c.lead1A());
		assertThat(lead.get("canManage").asBoolean()).isFalse();
		assertThat(lead.get("classes")).hasSize(1);
		assertThat(lead.get("classes").get(0).get("id").asString()).isEqualTo(c.class1A().toString());
		assertThat(lead.get("canCreateParentMeetings").asBoolean()).isTrue();
		JsonNode guardian = get(c.schoolEvents() + "/capabilities", c.gA1());
		assertThat(guardian.get("canManage").asBoolean()).isFalse();
		assertThat(guardian.get("canCreateAnyClassEvent").asBoolean()).isFalse();
		assertThat(guardian.get("canCreateParentMeetings").asBoolean()).isFalse();
		assertThat(failure(HttpMethod.GET, c.schoolEvents() + "/capabilities", c.outsider(), null, 404)).isEqualTo("school.not_found");
	}

}

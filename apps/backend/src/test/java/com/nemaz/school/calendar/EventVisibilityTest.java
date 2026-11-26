package com.nemaz.school.calendar;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who sees which native event: every scope against every kind of person (teacher of the class, teacher of another class,
 * guardian of a child in the class, guardian of another child, custody-restricted, pending and revoked guardians, students,
 * a former student, an outsider, another school's administrator). The same rule answers the single event and the union.
 */
@IntegrationTest
class EventVisibilityTest extends CalendarTestSupport {

	private static final Instant WHEN = Instant.parse("2027-02-10T06:00:00Z");

	private static final String FROM = "2027-02-01T00:00:00Z";

	private static final String TO = "2027-03-01T00:00:00Z";

	/** Everybody who could matter, by name. */
	private Map<String, Persona> everyone(Cal c) {
		Map<String, Persona> all = new LinkedHashMap<>();
		all.put("admin", c.admin());
		all.put("lead1A", c.lead1A());
		all.put("co1A", c.co1A());
		all.put("subject1A", c.subject1A());
		all.put("assistant1A", c.assistant1A());
		all.put("lead1B", c.lead1B());
		all.put("lead2A", c.lead2A());
		all.put("staff", c.staff());
		all.put("studentA1", c.studentA1());
		all.put("studentA2", c.studentA2());
		all.put("studentFormer", c.studentFormer());
		all.put("studentB1", c.studentB1());
		all.put("studentX1", c.studentX1());
		all.put("gA1", c.gA1());
		all.put("gSib", c.gSib());
		all.put("gB1", c.gB1());
		all.put("gX", c.gX());
		all.put("gPending", c.gPending());
		all.put("gRestricted", c.gRestricted());
		all.put("gRevoked", c.gRevoked());
		all.put("outsider", c.outsider());
		all.put("otherAdmin", c.otherAdmin());
		return all;
	}

	/** Who opens the event, and who finds it in the school's calendar: both must equal {@code expected}. */
	private void assertSeenBy(Cal c, UUID eventId, String... expected) throws Exception {
		Set<String> opens = new LinkedHashSet<>();
		Set<String> lists = new LinkedHashSet<>();
		for (Map.Entry<String, Persona> entry : everyone(c).entrySet()) {
			int status = statusOf(HttpMethod.GET, event(eventId), entry.getValue(), null);
			assertThat(status).as(entry.getKey() + " opening the event").isIn(200, 404);
			if (status == 200) {
				opens.add(entry.getKey());
			}
			// a person who cannot see the school gets 404 for the school's calendar, nothing else; everybody else lists
			int listStatus = statusOf(HttpMethod.GET, "/api/v1/calendar?from=" + FROM + "&to=" + TO + "&schoolId=" + c.schoolId(),
					entry.getValue(), null);
			if (listStatus == 200) {
				JsonNode page = get("/api/v1/calendar?from=" + FROM + "&to=" + TO + "&schoolId=" + c.schoolId() + "&sources=EVENT",
						entry.getValue());
				boolean found = items(page).stream().anyMatch((i) -> eventId.toString().equals(i.get("eventId").asString()));
				if (found) {
					lists.add(entry.getKey());
				}
			}
			else {
				assertThat(listStatus).as(entry.getKey() + " listing").isEqualTo(404);
			}
		}
		assertThat(opens).as("who opens the event").containsExactlyInAnyOrder(expected);
		assertThat(lists).as("who finds the event in the calendar").containsExactlyInAnyOrder(expected);
	}

	@Test
	void schoolWideEventsAreForEveryoneOfTheSchool() throws Exception {
		Cal c = cal("vis-school");
		assertSeenBy(c, schoolEvent(c, "School day", WHEN), "admin", "lead1A", "co1A", "subject1A", "assistant1A", "lead1B", "lead2A",
				"staff", "studentA1", "studentA2", "studentFormer", "studentB1", "studentX1", "gA1", "gSib", "gB1", "gX");
	}

	@Test
	void campusEventsAreForThePeopleOfThatCampus() throws Exception {
		Cal c = cal("vis-campus");
		UUID first = create(c, c.admin(), with(timed("Campus 1", "CAMPUS", WHEN), "campusId", c.campus1().toString()));
		assertSeenBy(c, first, "admin", "lead1A", "co1A", "subject1A", "assistant1A", "lead1B", "staff", "studentA1", "studentA2",
				"studentB1", "gA1", "gSib", "gB1");
		UUID second = create(c, c.admin(), with(timed("Campus 2", "CAMPUS", WHEN), "campusId", c.campus2().toString()));
		assertSeenBy(c, second, "admin", "lead2A", "staff", "studentX1", "gX");
	}

	@Test
	void gradeEventsAreForTheClassesOfTheGrade() throws Exception {
		Cal c = cal("vis-grade");
		UUID id = create(c, c.admin(), with(timed("Grade 1 trip", "GRADE_LEVEL", WHEN), "gradeLevelId", c.grade().toString()));
		assertSeenBy(c, id, "admin", "lead1A", "co1A", "subject1A", "assistant1A", "lead1B", "studentA1", "studentA2", "studentB1",
				"gA1", "gSib", "gB1");
	}

	@Test
	void classEventsFollowTheClassRule() throws Exception {
		Cal c = cal("vis-class");
		UUID id = classEvent(c, c.class1A(), "Class 1A test", WHEN);
		// teachers of the class in any role, its students and their guardians; the former student, the other classes and
		// every guardian whose link does not hold see nothing
		assertSeenBy(c, id, "admin", "lead1A", "co1A", "subject1A", "assistant1A", "studentA1", "studentA2", "gA1", "gSib");
		// the class's own lead made it too: same audience
		UUID byLead = create(c, c.lead1A(), with(timed("By lead", "CLASS", WHEN), "classId", c.class1A().toString()));
		assertSeenBy(c, byLead, "admin", "lead1A", "co1A", "subject1A", "assistant1A", "studentA1", "studentA2", "gA1", "gSib");
	}

	@Test
	void aGuardianWhoMayNotSeeAcademicsDoesNotSeeClassEvents() throws Exception {
		Cal c = cal("vis-academics");
		UUID id = classEvent(c, c.class1A(), "Class test", WHEN);
		UUID school = schoolEvent(c, "School day", WHEN);
		this.jdbc.update("update student_guardians set can_view_academics = false where guardian_id = ?", c.gA1().userId());
		assertThat(statusOf(HttpMethod.GET, event(id), c.gA1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, event(school), c.gA1(), null)).isEqualTo(200);
	}

	@Test
	void aLinkThatEndsTakesTheEventsWithIt() throws Exception {
		Cal c = cal("vis-revoked");
		UUID school = schoolEvent(c, "School day", WHEN);
		UUID cls = classEvent(c, c.class1A(), "Class test", WHEN);
		assertThat(statusOf(HttpMethod.GET, event(school), c.gA1(), null)).isEqualTo(200);
		assertThat(statusOf(HttpMethod.GET, event(cls), c.gA1(), null)).isEqualTo(200);
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where guardian_id = ?", c.gA1().userId());
		assertThat(statusOf(HttpMethod.GET, event(school), c.gA1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, event(cls), c.gA1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, "/api/v1/calendar?from=" + FROM + "&to=" + TO, c.gA1(), null)).isEqualTo(200);
		assertThat(titles(c.gA1(), FROM, TO, null)).isEmpty();
	}

	@Test
	void aStudentWhoLeftTheSchoolSeesNothing() throws Exception {
		Cal c = cal("vis-left");
		UUID school = schoolEvent(c, "School day", WHEN);
		this.jdbc.update("update school_students set status = 'WITHDRAWN', left_on = current_date where student_id = ?", c.a1());
		assertThat(statusOf(HttpMethod.GET, event(school), c.studentA1(), null)).isEqualTo(404);
		// and neither does the guardian through that child
		assertThat(statusOf(HttpMethod.GET, event(school), c.gA1(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, event(school), c.gSib(), null)).isEqualTo(200); // still has a2
	}

	@Test
	void theChildFilterNarrowsAGuardiansCalendarToOneChild() throws Exception {
		Cal c = cal("vis-child");
		UUID school = schoolEvent(c, "School day", WHEN);
		UUID campus2 = create(c, c.admin(), with(timed("Campus 2 fair", "CAMPUS", WHEN), "campusId", c.campus2().toString()));
		UUID cls1A = classEvent(c, c.class1A(), "Class 1A", WHEN);
		UUID cls1B = classEvent(c, c.class1B(), "Class 1B", WHEN);
		// a guardian of two children in the same class: the filter changes nothing for class events of that class,
		// but a guardian whose second child is in another class sees the difference
		Persona parent = this.personas.newUser("two-children");
		this.personas.linkGuardian(c.a1(), parent.userId(), "ACTIVE", false);
		this.personas.linkGuardian(c.b1(), parent.userId(), "ACTIVE", false);
		assertThat(titles(parent, FROM, TO, "schoolId=" + c.schoolId())).containsExactlyInAnyOrder("School day", "Class 1A", "Class 1B");
		assertThat(titles(parent, FROM, TO, "schoolId=" + c.schoolId() + "&studentId=" + c.a1()))
			.containsExactlyInAnyOrder("School day", "Class 1A");
		assertThat(titles(parent, FROM, TO, "studentId=" + c.b1())).containsExactlyInAnyOrder("School day", "Class 1B");
		assertThat(campus2).isNotNull();
		assertThat(cls1A).isNotEqualTo(cls1B);
		// the filter needs an ACTIVE link of the caller to that child: a stranger's child, or a revoked link, is 404
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=" + FROM + "&to=" + TO + "&studentId=" + c.x1(), parent, null, 404))
			.isEqualTo("calendar.student_not_found");
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=" + FROM + "&to=" + TO + "&studentId=" + c.a1(), c.gRevoked(), null,
				404)).isEqualTo("calendar.student_not_found");
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=" + FROM + "&to=" + TO + "&studentId=" + c.a1(), c.gRestricted(), null,
				404)).isEqualTo("calendar.student_not_found");
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=" + FROM + "&to=" + TO + "&studentId=" + c.a1(), c.lead1A(), null, 404))
			.isEqualTo("calendar.student_not_found");
		// the child's school, if named, must be the child's
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=" + FROM + "&to=" + TO + "&studentId=" + c.a1() + "&schoolId="
				+ this.personas.fixture().schoolA(), parent, null, 404)).isEqualTo("calendar.student_not_found");
		assertThat(school).isNotNull();
	}

	@Test
	void anAdministratorOfAnotherSchoolSeesNothingHere() throws Exception {
		Cal c = cal("vis-other");
		UUID school = schoolEvent(c, "School day", WHEN);
		UUID cls = classEvent(c, c.class1A(), "Class", WHEN);
		assertThat(failure(HttpMethod.GET, event(school), c.otherAdmin(), null, 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.GET, event(cls), c.otherAdmin(), null, 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.PATCH, event(school), c.otherAdmin(), Map.of("title", "x"), 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.POST, event(school) + "/cancel", c.otherAdmin(), null, 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.DELETE, event(school), c.otherAdmin(), null, 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.GET, "/api/v1/calendar?from=" + FROM + "&to=" + TO + "&schoolId=" + c.schoolId(), c.otherAdmin(),
				null, 404)).isEqualTo("school.not_found");
		// no school named: their own calendar, without this school
		assertThat(titles(c.otherAdmin(), FROM, TO, null)).doesNotContain("School day", "Class");
		// a platform administrator sees the school's wide events and, as a class manager, its class events
		assertThat(statusOf(HttpMethod.GET, event(school), this.personas.platformAdmin(), null)).isEqualTo(200);
		// administrators of a whole country, organization or the platform reach the school's calendar when they name it
		assertThat(titles(this.personas.platformAdmin(), FROM, TO, "schoolId=" + c.schoolId() + "&sources=EVENT"))
			.containsExactlyInAnyOrder("School day", "Class");
		assertThat(titles(this.personas.get(com.nemaz.school.support.Personas.Name.COUNTRY_ADMIN_ET), FROM, TO,
				"schoolId=" + c.schoolId() + "&sources=EVENT")).containsExactlyInAnyOrder("School day", "Class");
		// without a school named, only the schools they belong to count: a platform administrator has no "own" schools
		assertThat(titles(this.personas.platformAdmin(), FROM, TO, "sources=EVENT")).doesNotContain("School day");
	}

}

package com.nemaz.school.calendar;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parent meetings: only the teacher and the child's ACTIVE, unrestricted guardians know about them; the invitation is made for
 * the guardians of the moment and re-checked at every read; answers are private to the guardian and the teacher.
 */
@IntegrationTest
class ParentMeetingTest extends CalendarTestSupport {

	private static final String TITLE = "Abel struggles with reading";

	private UUID abelMeeting(Cal c) throws Exception {
		return create(c, c.lead1A(), meetingBody(TITLE, c.a1(), Instant.now().plus(Duration.ofDays(2))));
	}

	private Set<String> whoOpens(Cal c, UUID id) throws Exception {
		Map<String, Persona> people = new LinkedHashMap<>();
		people.put("admin", c.admin());
		people.put("lead1A", c.lead1A());
		people.put("co1A", c.co1A());
		people.put("subject1A", c.subject1A());
		people.put("assistant1A", c.assistant1A());
		people.put("lead1B", c.lead1B());
		people.put("staff", c.staff());
		people.put("studentA1", c.studentA1());
		people.put("studentA2", c.studentA2());
		people.put("gA1", c.gA1());
		people.put("gSib", c.gSib());
		people.put("gPending", c.gPending());
		people.put("gRestricted", c.gRestricted());
		people.put("gRevoked", c.gRevoked());
		people.put("gB1", c.gB1());
		people.put("outsider", c.outsider());
		people.put("otherAdmin", c.otherAdmin());
		people.put("platform", this.personas.platformAdmin());
		Set<String> opens = new LinkedHashSet<>();
		for (var entry : people.entrySet()) {
			int status = statusOf(HttpMethod.GET, event(id), entry.getValue(), null);
			assertThat(status).as(entry.getKey()).isIn(200, 404);
			if (status == 200) {
				opens.add(entry.getKey());
			}
		}
		return opens;
	}

	// --- creating ---------------------------------------------------------------------------

	@Test
	void aTeacherInvitesTheGuardiansOfOneOfTheirStudents() throws Exception {
		Cal c = cal("meet-create");
		JsonNode created = call(HttpMethod.POST, c.schoolEvents(), c.lead1A(), meetingBody(TITLE, c.a1(), Instant.now().plus(Duration.ofDays(2))), 201);
		assertThat(created.get("eventType").asString()).isEqualTo("PARENT_MEETING");
		assertThat(created.get("scope").asString()).isEqualTo("INVITEES");
		assertThat(created.get("rsvpEnabled").asBoolean()).isTrue();
		assertThat(created.get("regardingStudentId").asString()).isEqualTo(c.a1().toString());
		UUID id = UUID.fromString(created.get("id").asString());
		// the organiser and the guardians with an ACTIVE, unrestricted link that allows academics are the participants
		List<String> participants = this.jdbc.queryForList("select user_id || ':' || role || ':' || coalesce(rsvp_status, '-') from event_participants where event_id = ?", String.class,
				id);
		assertThat(participants).containsExactlyInAnyOrder(c.lead1A().userId() + ":ORGANIZER:-", c.gA1().userId() + ":ATTENDEE:PENDING",
				c.gSib().userId() + ":ATTENDEE:PENDING");
		// the other teachers of the student may invite too (lead, co-teacher, subject teacher), assistants may not
		call(HttpMethod.POST, c.schoolEvents(), c.co1A(), meetingBody("m", c.a1(), Instant.now().plus(Duration.ofDays(2))), 201);
		call(HttpMethod.POST, c.schoolEvents(), c.subject1A(), meetingBody("m", c.a1(), Instant.now().plus(Duration.ofDays(2))), 201);
		for (Persona nope : new Persona[] { c.assistant1A(), c.lead1B(), c.lead2A(), c.staff(), c.admin(), c.gA1(), c.studentA1() }) {
			assertThat(failure(HttpMethod.POST, c.schoolEvents(), nope, meetingBody("m", c.a1(), Instant.now().plus(Duration.ofDays(2))), 403))
				.as(nope.name()).isEqualTo("event.meeting_not_allowed");
		}
		for (Persona stranger : new Persona[] { c.outsider(), c.otherAdmin() }) {
			assertThat(failure(HttpMethod.POST, c.schoolEvents(), stranger, meetingBody("m", c.a1(), Instant.now().plus(Duration.ofDays(2))), 404))
				.isEqualTo("school.not_found");
		}
	}

	@Test
	void theStudentMustBeOfTheSchoolAndHaveGuardiansToInvite() throws Exception {
		Cal c = cal("meet-student");
		Instant start = Instant.now().plus(Duration.ofDays(2));
		UUID elsewhere = this.personas.newStudent(this.personas.fixture().schoolB(), "elsewhere");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), meetingBody("m", elsewhere, start), 404))
			.isEqualTo("event.student_not_found");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), meetingBody("m", Ids.newId(), start), 404))
			.isEqualTo("event.student_not_found");
		var noStudent = meetingBody("m", c.a1(), start);
		noStudent.remove("regardingStudentId");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), noStudent, 404)).isEqualTo("event.student_not_found");
		// a student nobody may be invited for: no guardian at all, or none that allows academics
		UUID alone = this.personas.newStudent(c.schoolId(), "alone");
		this.personas.addStudentToClass(c.schoolId(), c.class1A(), alone);
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), meetingBody("m", alone, start), 422)).isEqualTo("event.no_participants");
		Persona noAcademics = this.personas.newUser("no-academics");
		this.personas.linkGuardian(alone, noAcademics.userId(), "ACTIVE", false);
		this.jdbc.update("update student_guardians set can_view_academics = false where guardian_id = ?", noAcademics.userId());
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), meetingBody("m", alone, start), 422)).isEqualTo("event.no_participants");
	}

	@Test
	void aMeetingHasOneTimeInTheFutureAndNoRepeat() throws Exception {
		Cal c = cal("meet-shape");
		Instant start = Instant.now().plus(Duration.ofDays(2));
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), meetingBody("m", c.a1(), Instant.now().minus(Duration.ofHours(2))), 400))
			.isEqualTo("event.time_in_past");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), with(meetingBody("m", c.a1(), start), "recurrenceRule", "FREQ=DAILY"), 400))
			.isEqualTo("event.recurrence_not_allowed");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), with(meetingBody("m", c.a1(), start), "scope", "SCHOOL"), 400))
			.isEqualTo("event.scope_invalid");
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), with(meetingBody("m", c.a1(), start), "classId", c.class1A().toString()), 400))
			.isEqualTo("event.scope_invalid");
		Map<String, Object> allDay = new LinkedHashMap<>(allDay("m", null, "2027-02-10", "2027-02-10"));
		allDay.put("eventType", "PARENT_MEETING");
		allDay.put("regardingStudentId", c.a1().toString());
		assertThat(failure(HttpMethod.POST, c.schoolEvents(), c.lead1A(), allDay, 400)).isEqualTo("event.time_invalid");
	}

	// --- who sees it ------------------------------------------------------------------------

	@Test
	void onlyTheOrganiserAndTheInvitedGuardiansSeeTheMeeting() throws Exception {
		Cal c = cal("meet-vis");
		UUID id = abelMeeting(c);
		assertThat(whoOpens(c, id)).containsExactlyInAnyOrder("lead1A", "gA1", "gSib");
		// the union agrees
		String window = "from=" + Instant.now().toString() + "&to=" + Instant.now().plus(Duration.ofDays(5)) + "&schoolId=" + c.schoolId();
		for (Persona in : new Persona[] { c.lead1A(), c.gA1(), c.gSib() }) {
			JsonNode page = get("/api/v1/calendar?" + window, in);
			assertThat(items(page).stream().map((i) -> i.get("kind").asString())).as(in.name()).contains("PARENT_MEETING");
		}
		for (Persona out : new Persona[] { c.admin(), c.co1A(), c.studentA1(), c.gPending(), c.gRestricted(), c.gRevoked(), c.gB1() }) {
			if (statusOf(HttpMethod.GET, "/api/v1/calendar?" + window, out, null) == 200) {
				assertThat(items(get("/api/v1/calendar?" + window, out)).stream().map((i) -> i.get("kind").asString()))
					.as(out.name()).doesNotContain("PARENT_MEETING");
			}
		}
		// the child filter keeps it for the child it is about and hides it from the sibling's view
		assertThat(titles(c.gSib(), Instant.now().toString(), Instant.now().plus(Duration.ofDays(5)).toString(), "studentId=" + c.a1()))
			.contains(TITLE);
		assertThat(titles(c.gSib(), Instant.now().toString(), Instant.now().plus(Duration.ofDays(5)).toString(), "studentId=" + c.a2()))
			.doesNotContain(TITLE);
		// managers and other people cannot touch it either
		assertThat(failure(HttpMethod.PATCH, event(id), c.admin(), Map.of("title", "x"), 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.POST, event(id) + "/cancel", c.co1A(), null, 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.DELETE, event(id), this.personas.platformAdmin(), null, 404)).isEqualTo("event.not_found");
		// guardians may read it but not change or cancel it
		assertThat(failure(HttpMethod.PATCH, event(id), c.gA1(), Map.of("title", "x"), 403)).isEqualTo("event.access_denied");
		assertThat(failure(HttpMethod.POST, event(id) + "/cancel", c.gA1(), null, 403)).isEqualTo("event.access_denied");
	}

	@Test
	void aGuardianWhoseLinkEndsStopsSeeingTheMeetingAtOnceAndSeesItAgainWhenItHolds() throws Exception {
		Cal c = cal("meet-live");
		UUID id = abelMeeting(c);
		assertThat(statusOf(HttpMethod.GET, event(id), c.gA1(), null)).isEqualTo(200);
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where guardian_id = ?", c.gA1().userId());
		assertThat(failure(HttpMethod.GET, event(id), c.gA1(), null, 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.PUT, event(id) + "/rsvp", c.gA1(), Map.of("status", "YES"), 404)).isNotNull();
		this.jdbc.update("update student_guardians set status = 'ACTIVE', revoked_at = null where guardian_id = ?", c.gA1().userId());
		assertThat(statusOf(HttpMethod.GET, event(id), c.gA1(), null)).isEqualTo(200);
		// a custody restriction hides it, lifting the restriction brings it back
		this.jdbc.update("update student_guardians set custody_restricted = true, restriction_note = 'court' where guardian_id = ?",
				c.gA1().userId());
		assertThat(statusOf(HttpMethod.GET, event(id), c.gA1(), null)).isEqualTo(404);
		this.jdbc.update("update student_guardians set custody_restricted = false, restriction_note = null where guardian_id = ?",
				c.gA1().userId());
		// so does taking away the academics flag
		this.jdbc.update("update student_guardians set can_view_academics = false where guardian_id = ?", c.gA1().userId());
		assertThat(statusOf(HttpMethod.GET, event(id), c.gA1(), null)).isEqualTo(404);
		this.jdbc.update("update student_guardians set can_view_academics = true where guardian_id = ?", c.gA1().userId());
		assertThat(statusOf(HttpMethod.GET, event(id), c.gA1(), null)).isEqualTo(200);
		// a guardian linked after the invitation was made is not invited (the invitation is for the guardians of the moment)
		Persona late = this.personas.newUser("late-guardian");
		this.personas.linkGuardian(c.a1(), late.userId(), "ACTIVE", false);
		assertThat(statusOf(HttpMethod.GET, event(id), late, null)).isEqualTo(404);
		// the organiser's view lists only guardians whose link holds now
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where guardian_id = ?", c.gSib().userId());
		JsonNode organiser = get(event(id), c.lead1A());
		assertThat(organiser.get("rsvp").get("participants")).hasSize(1);
		assertThat(organiser.get("rsvp").get("participants").get(0).get("userId").asString()).isEqualTo(c.gA1().userId().toString());
	}

	// --- RSVP -------------------------------------------------------------------------------

	@Test
	void guardiansAnswerAndOnlyTheTeacherSeesWhoAnsweredWhat() throws Exception {
		Cal c = cal("meet-rsvp");
		UUID id = abelMeeting(c);
		String rsvp = event(id) + "/rsvp";
		JsonNode before = get(event(id), c.gA1());
		assertThat(before.get("rsvp").get("mine").asString()).isEqualTo("PENDING");
		assertThat(before.get("rsvp").get("canAnswer").asBoolean()).isTrue();
		JsonNode yes = call(HttpMethod.PUT, rsvp, c.gA1(), Map.of("status", "YES"), 200);
		assertThat(yes.get("rsvp").get("mine").asString()).isEqualTo("YES");
		// the same answer again changes nothing (the time of the first stays), another one replaces it
		Instant first = this.jdbc.queryForObject("select responded_at from event_participants where event_id = ? and user_id = ?",
				java.sql.Timestamp.class, id, c.gA1().userId()).toInstant();
		call(HttpMethod.PUT, rsvp, c.gA1(), Map.of("status", "YES"), 200);
		assertThat(this.jdbc.queryForObject("select responded_at from event_participants where event_id = ? and user_id = ?",
				java.sql.Timestamp.class, id, c.gA1().userId()).toInstant()).isEqualTo(first);
		call(HttpMethod.PUT, rsvp, c.gSib(), Map.of("status", "NO"), 200);
		call(HttpMethod.PUT, rsvp, c.gA1(), Map.of("status", "MAYBE"), 200);
		// the teacher sees each guardian's answer and the totals
		JsonNode teacher = get(event(id), c.lead1A()).get("rsvp");
		assertThat(teacher.get("participants")).hasSize(2);
		Map<String, String> answers = new LinkedHashMap<>();
		teacher.get("participants").forEach((p) -> answers.put(p.get("userId").asString(), p.get("status").asString()));
		assertThat(answers).containsEntry(c.gA1().userId().toString(), "MAYBE").containsEntry(c.gSib().userId().toString(), "NO");
		assertThat(teacher.get("counts").get("maybe").asInt()).isEqualTo(1);
		assertThat(teacher.get("counts").get("no").asInt()).isEqualTo(1);
		assertThat(teacher.get("counts").get("yes").asInt()).isZero();
		assertThat(teacher.get("canAnswer").asBoolean()).isFalse();
		// a guardian sees their own answer and nothing about the other guardian
		JsonNode guardian = get(event(id), c.gA1()).get("rsvp");
		assertThat(guardian.get("mine").asString()).isEqualTo("MAYBE");
		assertThat(guardian.path("participants").isMissingNode() || guardian.get("participants").isNull()).isTrue();
		assertThat(guardian.path("counts").isMissingNode() || guardian.get("counts").isNull()).isTrue();
		assertThat(get(event(id), c.gSib()).toString()).doesNotContain(c.gA1().userId().toString());
		// the organiser is not a guest; nobody who is not invited can answer
		assertThat(failure(HttpMethod.PUT, rsvp, c.lead1A(), Map.of("status", "YES"), 403)).isEqualTo("event.access_denied");
		assertThat(failure(HttpMethod.PUT, rsvp, c.co1A(), Map.of("status", "YES"), 404)).isEqualTo("event.not_found");
		assertThat(failure(HttpMethod.PUT, rsvp, c.gRestricted(), Map.of("status", "YES"), 404)).isEqualTo("event.not_found");
		assertThat(statusOf(HttpMethod.PUT, rsvp, c.gA1(), Map.of("status", "SURE"))).isEqualTo(400);
		assertThat(statusOf(HttpMethod.PUT, rsvp, c.gA1(), null)).isEqualTo(400);
	}

	@Test
	void theAuditTrailOfAnAnswerHoldsCountsNotNamesOrText() throws Exception {
		Cal c = cal("meet-audit");
		UUID id = abelMeeting(c);
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gA1(), Map.of("status", "YES"), 200);
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gSib(), Map.of("status", "NO"), 200);
		List<Map<String, Object>> rows = this.jdbc.queryForList(
				"select action, metadata::text as metadata, target_id from audit_logs where target_id = ? order by id", id);
		assertThat(rows).extracting((r) -> r.get("action")).contains("event.create", "event.rsvp");
		String all = rows.toString();
		assertThat(all).doesNotContain(TITLE).doesNotContain("Abel").doesNotContain(c.a1().toString());
		Map<String, Object> last = rows.stream().filter((r) -> "event.rsvp".equals(r.get("action"))).reduce((a, b) -> b).orElseThrow();
		assertThat(last.get("metadata").toString()).contains("\"answer\"").contains("\"yes\": 1").contains("\"no\": 1");
	}

	@Test
	void answersCloseWhenTheMeetingStartsOrIsCancelled() throws Exception {
		Cal c = cal("meet-closed");
		Instant start = Instant.now().plus(Duration.ofDays(2));
		UUID id = create(c, c.lead1A(), meetingBody("m", c.a1(), start));
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gA1(), Map.of("status", "YES"), 200);
		clockAt(start.plusSeconds(60));
		assertThat(failure(HttpMethod.PUT, event(id) + "/rsvp", c.gA1(), Map.of("status", "NO"), 409)).isEqualTo("event.rsvp_closed");
		assertThat(get(event(id), c.gA1()).get("rsvp").get("canAnswer").asBoolean()).isFalse();
		assertThat(get(event(id), c.gA1()).get("rsvp").get("mine").asString()).isEqualTo("YES");
		this.clock.reset();
		call(HttpMethod.POST, event(id) + "/cancel", c.lead1A(), null, 200);
		assertThat(failure(HttpMethod.PUT, event(id) + "/rsvp", c.gA1(), Map.of("status", "NO"), 409)).isEqualTo("event.rsvp_closed");
	}

	// --- changes ----------------------------------------------------------------------------

	@Test
	void aRescheduleAsksAgainAndTellsTheGuardiansWithoutNamingTheChild() throws Exception {
		Cal c = cal("meet-move");
		UUID id = abelMeeting(c);
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gA1(), Map.of("status", "YES"), 200);
		call(HttpMethod.PUT, event(id) + "/rsvp", c.gSib(), Map.of("status", "NO"), 200);
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		Instant moved = Instant.now().plus(Duration.ofDays(3));
		JsonNode view = call(HttpMethod.PATCH, event(id), c.lead1A(),
				Map.of("startAt", moved.toString(), "endAt", moved.plusSeconds(1800).toString(), "location", "Room 12"), 200);
		assertThat(Instant.parse(view.get("startAt").asString())).isEqualTo(moved);
		// the answers were for the old time
		assertThat(get(event(id), c.gA1()).get("rsvp").get("mine").asString()).isEqualTo("PENDING");
		assertThat(get(event(id), c.gSib()).get("rsvp").get("mine").asString()).isEqualTo("PENDING");
		assertThat(get(event(id), c.lead1A()).get("rsvp").get("counts").get("pending").asInt()).isEqualTo(2);
		// both guardians are told (once), the teacher who did it is not, a revoked or restricted guardian never is
		assertThat(count(c.gA1().userId(), "EVENT_UPDATED")).isEqualTo(1);
		assertThat(count(c.gSib().userId(), "EVENT_UPDATED")).isEqualTo(1);
		assertThat(count(c.lead1A().userId(), "EVENT_UPDATED")).isZero();
		assertThat(count(c.gRevoked().userId(), "EVENT_UPDATED")).isZero();
		assertThat(count(c.gRestricted().userId(), "EVENT_UPDATED")).isZero();
		assertThat(count(c.gB1().userId(), "EVENT_UPDATED")).isZero();
		// a title-only change is silent
		call(HttpMethod.PATCH, event(id), c.lead1A(), Map.of("title", "Reading follow-up"), 200);
		assertThat(count(c.gA1().userId(), "EVENT_UPDATED")).isEqualTo(1);
		assertNoPrivateText(c, id);
		// a past time is refused
		assertThat(failure(HttpMethod.PATCH, event(id), c.lead1A(), Map.of("startAt", Instant.now().minusSeconds(3600).toString(),
				"endAt", Instant.now().minusSeconds(60).toString()), 400)).isEqualTo("event.time_in_past");
	}

	@Test
	void cancellingKeepsTheMeetingAsCancelledAndTellsTheGuardians() throws Exception {
		Cal c = cal("meet-cancel");
		UUID id = abelMeeting(c);
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		JsonNode cancelled = call(HttpMethod.POST, event(id) + "/cancel", c.lead1A(), Map.of("reason", "Sick"), 200);
		assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
		assertThat(cancelled.get("cancellationReason").asString()).isEqualTo("Sick");
		assertThat(count(c.gA1().userId(), "EVENT_CANCELLED")).isEqualTo(1);
		assertThat(count(c.gSib().userId(), "EVENT_CANCELLED")).isEqualTo(1);
		assertThat(count(c.gRevoked().userId(), "EVENT_CANCELLED")).isZero();
		assertNoPrivateText(c, id);
		// still there, shown as cancelled, never deleted silently; cancelling again changes nothing
		assertThat(get(event(id), c.gA1()).get("status").asString()).isEqualTo("CANCELLED");
		call(HttpMethod.POST, event(id) + "/cancel", c.lead1A(), null, 200);
		assertThat(count(c.gA1().userId(), "EVENT_CANCELLED")).isEqualTo(1);
		assertThat(failure(HttpMethod.PATCH, event(id), c.lead1A(), Map.of("title", "x"), 409)).isEqualTo("event.cancelled");
		// only a cancelled event can be deleted, and only by its organiser
		UUID other = abelMeeting(c);
		assertThat(failure(HttpMethod.DELETE, event(other), c.lead1A(), null, 409)).isEqualTo("event.cancel_first");
		assertThat(failure(HttpMethod.DELETE, event(id), c.gA1(), null, 403)).isEqualTo("event.access_denied");
		call(HttpMethod.DELETE, event(id), c.lead1A(), null, 204);
		assertThat(failure(HttpMethod.GET, event(id), c.lead1A(), null, 404)).isEqualTo("event.not_found");
	}

	@Test
	void theInvitationTellsTheGuardiansWithoutTheTitleTheChildOrThePlace() throws Exception {
		Cal c = cal("meet-invite");
		this.jdbc.update("delete from notifications where school_id = ?", c.schoolId());
		UUID id = create(c, c.lead1A(), with(meetingBody(TITLE, c.a1(), Instant.now().plus(Duration.ofDays(2))), "location", "Room 3",
				"description", "Abel cannot read"));
		assertThat(count(c.gA1().userId(), "EVENT_INVITATION")).isEqualTo(1);
		assertThat(count(c.gSib().userId(), "EVENT_INVITATION")).isEqualTo(1);
		assertThat(count(c.lead1A().userId(), "EVENT_INVITATION")).isZero();
		for (Persona none : new Persona[] { c.gPending(), c.gRestricted(), c.gRevoked(), c.gB1(), c.co1A(), c.admin() }) {
			assertThat(count(none.userId(), "EVENT_INVITATION")).as(none.name()).isZero();
		}
		Map<String, Object> row = inbox(c.gA1().userId(), "EVENT_INVITATION").getFirst();
		assertThat(row.get("title").toString()).isEqualTo("Parent meeting invitation");
		assertThat(row.get("body").toString()).contains(this.jdbc.queryForObject("select display_name from users where id = ?", String.class,
				c.lead1A().userId()));
		assertThat(row.get("data").toString()).contains("/calendar/events/" + id);
		assertNoPrivateText(c, id);
	}

	/** No notification of the meeting carries its title, description, place or the child's name. */
	private void assertNoPrivateText(Cal c, UUID id) {
		List<Map<String, Object>> all = this.jdbc.queryForList("select title, body, data::text as data from notifications where source_id = ?", id);
		assertThat(all).isNotEmpty();
		for (Map<String, Object> n : all) {
			String text = n.get("title") + " " + n.get("body") + " " + n.get("data");
			assertThat(text).doesNotContain("Abel").doesNotContain("reading").doesNotContain("Reading").doesNotContain("Room")
				.doesNotContain(c.a1().toString());
		}
	}

}

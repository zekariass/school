package com.nemaz.school.attendance;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Absence reports: who files, who decides, what the roll shows, and who may read the health-related note. */
@IntegrationTest
class AbsenceReportApiTest extends AttendanceTestSupport {

	private static final String NOTE = "fever and a cough since Sunday";

	private JsonNode file(Setup s, Persona as, UUID student, LocalDate from, LocalDate to, String reason, String note, int status)
			throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("fromDate", from.toString());
		body.put("toDate", to.toString());
		body.put("reasonCode", reason);
		if (note != null) {
			body.put("note", note);
		}
		return call(HttpMethod.POST, s.api() + "/students/" + student + "/absence-reports", as, body, status);
	}

	private JsonNode decide(Setup s, Persona as, UUID report, String verb, int status) throws Exception {
		return call(HttpMethod.POST, s.api() + "/absence-reports/" + report + "/" + verb, as, null, status);
	}

	@Test
	void aGuardianReportsAheadAndTheRollShowsItToTheTeachersWhoMaySeeTheDetails() throws Exception {
		Setup s = setup("Absence school");
		JsonNode report = file(s, s.guardian1(), s.s1(), today().plusDays(1), today().plusDays(2), "ILLNESS", NOTE, 201);
		UUID id = uuid(report, "id");
		assertThat(report.get("status").asString()).isEqualTo("SUBMITTED");
		assertThat(report.get("note").asString()).isEqualTo(NOTE);
		assertThat(report.get("reasonCode").asString()).isEqualTo("ILLNESS");
		LocalDate day = today().plusDays(1);

		// the roll of that day carries the annotation; the lead and the subject teacher read the details, the assistant does not
		for (Persona teacher : List.of(s.lead(), s.subject(), s.admin())) {
			JsonNode annotation = student(roll(s, teacher, day), s.s1()).get("absence");
			assertThat(uuid(annotation, "reportId")).isEqualTo(id);
			assertThat(annotation.get("status").asString()).isEqualTo("SUBMITTED");
			assertThat(annotation.get("reasonCode").asString()).isEqualTo("ILLNESS");
			assertThat(annotation.get("note").asString()).isEqualTo(NOTE);
		}
		JsonNode assistantView = student(roll(s, s.assistant(), day), s.s1()).get("absence");
		assertThat(assistantView.get("status").asString()).isEqualTo("SUBMITTED");
		assertThat(absent(assistantView, "note")).isTrue();
		assertThat(absent(assistantView, "reasonCode")).isTrue();
		// other students and other days have no annotation; nothing is pre-filled before an acknowledgement
		assertThat(absent(student(roll(s, s.lead(), day), s.s2()), "absence")).isTrue();
		assertThat(absent(student(roll(s, s.lead(), today()), s.s1()), "absence")).isTrue();
		assertThat(absent(student(roll(s, s.lead(), day), s.s1()), "suggestedStatus")).isTrue();
	}

	@Test
	void theNoteIsReadOnlyByTheReporterTheStudentsTeachersAndAttendanceManagers() throws Exception {
		Setup s = setup("Privacy school");
		JsonNode report = file(s, s.guardian1(), s.s1(), today(), today(), "ILLNESS", NOTE, 201);
		UUID id = uuid(report, "id");
		String url = s.api() + "/students/" + s.s1() + "/absence-reports";

		// the reporter, the lead, the subject teacher and an attendance manager read it
		for (Persona reader : List.of(s.guardian1(), s.lead(), s.subject(), s.admin())) {
			JsonNode items = get(url, reader).get("items");
			assertThat(items).hasSize(1);
			assertThat(items.get(0).get("note").asString()).as(reader.name()).isEqualTo(NOTE);
		}
		// another guardian of the same child sees nothing of it
		assertThat(get(url, s.guardian2()).get("items")).isEmpty();
		// the child, an assistant, another school member and an adult without a link are refused
		call(HttpMethod.GET, url, s.student1(), null, 404);
		call(HttpMethod.GET, url, s.assistant(), null, 403);
		call(HttpMethod.GET, url, s.otherTeacher(), null, 404);
		call(HttpMethod.GET, url, s.guardianOfS2(), null, 404);
		call(HttpMethod.GET, url, this.personas.newUser("nobody"), null, 404);

		// the class list: details only for those who may read them
		String classUrl = s.api() + "/classes/" + s.classId() + "/absence-reports";
		assertThat(get(classUrl, s.lead()).get("items").get(0).get("note").asString()).isEqualTo(NOTE);
		JsonNode forAssistant = get(classUrl, s.assistant()).get("items").get(0);
		assertThat(absent(forAssistant, "note")).isTrue();
		assertThat(absent(forAssistant, "reasonCode")).isTrue();
		call(HttpMethod.GET, classUrl, s.student1(), null, 403);
		call(HttpMethod.GET, classUrl, s.guardian1(), null, 403);
		call(HttpMethod.GET, classUrl, s.otherTeacher(), null, 404);

		// the note is nowhere else: notifications, events, the audit log
		await().untilAsserted(() -> assertThat(this.jdbc.queryForObject(
				"select count(*) from notifications where type_code = 'ABSENCE_REPORTED' and user_id = ?", Integer.class,
				s.leadId())).isEqualTo(1));
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where body like ? or title like ? or cast(data as text) like ?",
				Integer.class, "%fever%", "%fever%", "%fever%")).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where type_code = 'ABSENCE_REPORTED' and (body like '%ILLNESS%' or body like '%illness%')",
				Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where cast(metadata as text) like ? or cast(changes as text) like ?",
				Integer.class, "%fever%", "%fever%")).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from event_publication where serialized_event like ?", Integer.class,
				"%fever%")).isZero();
		assertThat(id).isNotNull();
	}

	@Test
	void teachersOfTheStudentAreToldWithoutTheReasonAndAssistantsAreNot() throws Exception {
		Setup s = setup("Notify absence school");
		file(s, s.guardian1(), s.s1(), today().plusDays(1), today().plusDays(3), "FAMILY", "wedding", 201);
		await().untilAsserted(() -> assertThat(this.jdbc.queryForList(
				"select user_id from notifications where type_code = 'ABSENCE_REPORTED' and school_id = ?", UUID.class, s.schoolId()))
			.containsExactlyInAnyOrder(s.leadId(), s.subjectId()));
		String body = this.jdbc.queryForObject("select body from notifications where type_code = 'ABSENCE_REPORTED' and user_id = ?",
				String.class, s.leadId());
		assertThat(body).contains("Abel").contains(today().plusDays(1).toString()).contains(today().plusDays(3).toString())
			.doesNotContain("wedding");
		String path = this.jdbc.queryForObject(
				"select data->>'path' from notifications where type_code = 'ABSENCE_REPORTED' and user_id = ?", String.class,
				s.leadId());
		assertThat(path).isEqualTo("/classes/" + s.classId() + "/attendance");
	}

	@Test
	void anAcknowledgedReportPreFillsExcusedButOnlyAsASuggestionAndForMarkAllPresent() throws Exception {
		Setup s = setup("Prefill school");
		LocalDate day = today();
		UUID report = uuid(file(s, s.guardian1(), s.s1(), day, day, "APPOINTMENT", "dentist", 201), "id");
		// the assistant cannot decide, nor can the reporter or another class's teacher
		assertThat(errorCode(decide(s, s.assistant(), report, "acknowledge", 403))).isEqualTo("absence.access_denied");
		decide(s, s.guardian1(), report, "acknowledge", 403);
		decide(s, s.otherTeacher(), report, "acknowledge", 404);
		decide(s, s.guardianOfS2(), report, "acknowledge", 404);
		decide(s, s.student1(), report, "acknowledge", 404);

		JsonNode acknowledged = decide(s, s.lead(), report, "acknowledge", 200);
		assertThat(acknowledged.get("status").asString()).isEqualTo("ACKNOWLEDGED");
		assertThat(uuid(acknowledged, "decidedBy")).isEqualTo(s.leadId());
		assertThat(errorCode(decide(s, s.subject(), report, "decline", 409))).isEqualTo("absence.already_decided");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'absence.report.acknowledged' and school_id = ?",
				Integer.class, s.schoolId())).isEqualTo(1);

		JsonNode view = roll(s, s.lead(), day);
		assertThat(student(view, s.s1()).get("suggestedStatus").asString()).isEqualTo("EXCUSED");
		assertThat(student(view, s.s1()).get("absence").get("status").asString()).isEqualTo("ACKNOWLEDGED");
		assertThat(absent(student(view, s.s1()), "record")).isTrue();
		assertThat(countRecords(s.classId())).isZero();

		JsonNode written = take(s, s.lead(), rollBody(day, true), 200);
		JsonNode excused = outcome(written, s.s1()).get("record");
		assertThat(excused.get("status").asString()).isEqualTo("EXCUSED");
		assertThat(uuid(excused, "absenceReportId")).isEqualTo(report);
		assertThat(outcome(written, s.s2()).get("record").get("status").asString()).isEqualTo("PRESENT");
		// an explicit mark is the teacher's word: ABSENT stays ABSENT even for an acknowledged report
		JsonNode explicit = take(s, s.lead(), rollBody(day, false, mark(s.s1(), "ABSENT", null, null, 0)), 200);
		assertThat(outcome(explicit, s.s1()).get("record").get("status").asString()).isEqualTo("ABSENT");
	}

	@Test
	void aDeclinedOrCancelledReportIsNotShownOnTheRoll() throws Exception {
		Setup s = setup("Decline school");
		LocalDate day = today().plusDays(2);
		UUID declined = uuid(file(s, s.guardian1(), s.s1(), day, day, "OTHER", null, 201), "id");
		JsonNode result = decide(s, s.subject(), declined, "decline", 200);
		assertThat(result.get("status").asString()).isEqualTo("DECLINED");
		assertThat(absent(student(roll(s, s.lead(), day), s.s1()), "absence")).isTrue();
		UUID cancelled = uuid(file(s, s.guardian1(), s.s1(), day, day, "OTHER", null, 201), "id");
		call(HttpMethod.POST, s.api() + "/absence-reports/" + cancelled + "/cancel", s.guardian2(), null, 404);
		call(HttpMethod.POST, s.api() + "/absence-reports/" + cancelled + "/cancel", s.guardian1(), null, 200);
		assertThat(absent(student(roll(s, s.lead(), day), s.s1()), "absence")).isTrue();
		assertThat(errorCode(call(HttpMethod.POST, s.api() + "/absence-reports/" + cancelled + "/cancel", s.guardian1(), null, 409)))
			.isEqualTo("absence.already_decided");
	}

	@Test
	void onlyAnActiveUnrestrictedGuardianOfAnActiveStudentMayReport() throws Exception {
		Setup s = setup("Who reports school");
		LocalDate day = today().plusDays(1);
		assertThat(errorCode(file(s, s.guardianOfS2(), s.s1(), day, day, "ILLNESS", null, 404))).isEqualTo("student.not_found");
		file(s, s.lead(), s.s1(), day, day, "ILLNESS", null, 404);
		file(s, s.student1(), s.s1(), day, day, "ILLNESS", null, 404);
		file(s, s.admin(), s.s1(), day, day, "ILLNESS", null, 404);
		for (String status : List.of("PENDING", "REVOKED")) {
			Persona p = this.personas.newUser("parent-" + status);
			this.personas.linkGuardian(s.s1(), p.userId(), status, false);
			file(s, p, s.s1(), day, day, "ILLNESS", null, 404);
		}
		Persona restricted = this.personas.newUser("restricted-parent");
		this.personas.linkGuardian(s.s1(), restricted.userId(), "ACTIVE", true);
		file(s, restricted, s.s1(), day, day, "ILLNESS", null, 404);
		call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/absence-reports", null,
				Map.of("fromDate", day.toString(), "toDate", day.toString(), "reasonCode", "ILLNESS"), 401);
		assertThat(this.jdbc.queryForObject("select count(*) from absence_reports where school_id = ?", Integer.class, s.schoolId()))
			.isZero();
	}

	@Test
	void datesAndReasonsAreChecked() throws Exception {
		Setup s = setup("Dates school");
		Persona g = s.guardian1();
		assertThat(errorCode(file(s, g, s.s1(), today().plusDays(2), today().plusDays(1), "ILLNESS", null, 400)))
			.isEqualTo("absence.invalid_dates");
		assertThat(errorCode(file(s, g, s.s1(), today().minusDays(8), today(), "ILLNESS", null, 400)))
			.isEqualTo("absence.invalid_dates");
		assertThat(errorCode(file(s, g, s.s1(), today(), today().plusDays(91), "ILLNESS", null, 400)))
			.isEqualTo("absence.invalid_dates");
		assertThat(errorCode(file(s, g, s.s1(), today(), today().plusDays(61), "ILLNESS", null, 400)))
			.isEqualTo("absence.invalid_dates");
		assertThat(errorCode(file(s, g, s.s1(), today(), today(), "BORED", null, 400))).isEqualTo("absence.invalid_reason");
		file(s, g, s.s1(), today().minusDays(7), today().minusDays(7), "ILLNESS", null, 201);
		file(s, g, s.s1(), today(), today().plusDays(60), "FAMILY", null, 201);
		// a note is short
		file(s, g, s.s1(), today(), today(), "OTHER", "x".repeat(501), 400);
	}

	@Test
	void theFilingDateIsTheSchoolLocalDate() throws Exception {
		Setup s = setup("Local filing school");
		this.jdbc.update("update schools set timezone = 'Pacific/Kiritimati' where id = ?", s.schoolId());
		moveClockTo("2026-11-25T10:30:00Z"); // already the 26th at UTC+14
		file(s, s.guardian1(), s.s1(), LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 19), "ILLNESS", null, 201);
		file(s, s.guardian1(), s.s1(), LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 18), "ILLNESS", null, 400);
	}

}

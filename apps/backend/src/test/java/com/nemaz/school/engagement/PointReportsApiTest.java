package com.nemaz.school.engagement;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.engagement.application.PointReports;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Reports and their CSV export: who may read what, what is left out, and a file spreadsheets cannot abuse. */
@IntegrationTest
class PointReportsApiTest extends PointsTestSupport {

	private Setup withPoints(String label) throws Exception {
		Setup s = setup(label);
		UUID kindness = category(s, "kindness");
		award(s, s.lead(), kindness, List.of(s.s1(), s.s2()), 201);
		award(s, s.lead(), kindness, List.of(s.s1()), 201);
		award(s, s.lead(), category(s, "persistence"), List.of(s.s1()), 201);
		award(s, s.lead(), category(s, "disruptive"), List.of(s.s2()), 201);
		// home points exist but must never appear
		call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/home-points", s.guardian(),
				Map.of("categoryId", category(s, "home_homework"), "points", 9), 201);
		return s;
	}

	private MvcResult csv(Setup s, Persona as, String query, String language) throws Exception {
		var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(s.api() + "/points/report.csv" + query).with(as.bearer());
		if (language != null) {
			request = request.header("Accept-Language", language);
		}
		return this.mvc.perform(request).andReturn();
	}

	@Test
	void teachersReportTheirClassAndAdministratorsTheWholeSchool() throws Exception {
		Setup s = withPoints("Report school");
		JsonNode report = get(s.api() + "/points/report?classId=" + s.classId(), s.lead());
		JsonNode items = report.get("items");
		assertThat(items).hasSize(4);
		int abel = 0;
		for (JsonNode row : items) {
			if (uuid(row, "studentId").equals(s.s1())) {
				abel += row.get("points").asInt();
			}
			assertThat(row.propertyNames()).doesNotContain("dateOfBirth", "gender", "username");
		}
		assertThat(abel).isEqualTo(1 + 1 + 2);
		// home points are not in the numbers (9 would have made it 13)
		assertThat(items.findValuesAsString("categoryName")).doesNotContain("Homework done");
		for (Persona p : List.of(s.assistant(), s.admin())) {
			assertThat(get(s.api() + "/points/report?classId=" + s.classId(), p).get("items")).hasSize(4);
		}
		assertThat(get(s.api() + "/points/report", s.admin()).get("items")).hasSize(4);
		assertThat(get(s.api() + "/points/report?classId=" + s.classId() + "&categoryId=" + category(s, "kindness"), s.lead())
			.get("items")).hasSize(2);
	}

	@Test
	void reportsNetOutReversals() throws Exception {
		Setup s = setup("Net report school");
		JsonNode award = award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		call(HttpMethod.POST, s.api() + "/points/transactions/" + transactionId(award, 0) + "/reverse", s.lead(), null, 201);
		JsonNode row = get(s.api() + "/points/report?classId=" + s.classId(), s.lead()).get("items").get(0);
		assertThat(row.get("points").asInt()).isZero();
		assertThat(row.get("awards").asInt()).isEqualTo(1);
		assertThat(row.get("reversals").asInt()).isEqualTo(1);
	}

	@Test
	void everyoneElseIsRefusedOrSeesNothing() throws Exception {
		Setup s = withPoints("Report deny school");
		String classReport = s.api() + "/points/report?classId=" + s.classId();
		// students and guardians see the class but not its report; other classes' teachers and other schools see no class
		assertThat(errorCode(call(HttpMethod.GET, classReport, s.student1(), null, 403))).isEqualTo("class.access_denied");
		call(HttpMethod.GET, classReport, s.guardian(), null, 403);
		call(HttpMethod.GET, classReport, s.otherTeacher(), null, 404);
		call(HttpMethod.GET, classReport, setup("Report elsewhere").admin(), null, 404);
		// the school-wide report needs school.points.read
		String schoolReport = s.api() + "/points/report";
		for (Persona p : List.of(s.lead(), s.assistant(), s.otherTeacher(), s.student1())) {
			assertThat(errorCode(call(HttpMethod.GET, schoolReport, p, null, 403))).isEqualTo("points.access_denied");
		}
		call(HttpMethod.GET, schoolReport, s.guardian(), null, 404);
		call(HttpMethod.GET, schoolReport, setup("Report elsewhere 2").admin(), null, 404);
		for (Persona p : List.of(s.lead(), s.student1(), s.guardian())) {
			assertThat(csv(s, p, "", null).getResponse().getStatus()).isIn(403, 404);
		}
		assertThat(csv(s, s.otherTeacher(), "?classId=" + s.classId(), null).getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void dateRangesAreSchoolLocalAndInclusive() throws Exception {
		Setup s = withPoints("Range school");
		String today = LocalDate.now(java.time.ZoneId.of("Africa/Addis_Ababa")).toString();
		String base = s.api() + "/points/report?classId=" + s.classId();
		assertThat(get(base + "&from=" + today + "&to=" + today, s.lead()).get("items")).hasSize(4);
		assertThat(get(base + "&from=2000-01-01&to=2000-01-31", s.lead()).get("items")).isEmpty();
		assertThat(get(base + "&from=" + today, s.lead()).get("items")).hasSize(4);
		assertThat(errorCode(call(HttpMethod.GET, base + "&from=2026-02-01&to=2026-01-01", s.lead(), null, 400))).isEqualTo("points.invalid_range");
		call(HttpMethod.GET, base + "&from=not-a-date", s.lead(), null, 400);
	}

	@Test
	void reportsAreKeysetPaged() throws Exception {
		Setup s = withPoints("Paged report school");
		String base = s.api() + "/points/report?classId=" + s.classId() + "&limit=3";
		JsonNode first = get(base, s.lead());
		assertThat(first.get("items")).hasSize(3);
		JsonNode second = get(base + "&cursor=" + first.get("nextCursor").asString(), s.lead());
		assertThat(second.get("items")).hasSize(1);
		assertThat(absent(second, "nextCursor")).isTrue();
		List<String> keys = new ArrayList<>();
		for (JsonNode page : List.of(first, second)) {
			for (JsonNode row : page.get("items")) {
				keys.add(row.get("studentId").asString() + "/" + row.get("categoryId").asString());
			}
		}
		assertThat(keys).doesNotHaveDuplicates().hasSize(4);
		call(HttpMethod.GET, base + "&cursor=zzz", s.lead(), null, 400);
	}

	@Test
	void theCsvIsAudited() throws Exception {
		Setup s = withPoints("Csv school");
		MvcResult result = csv(s, s.lead(), "?classId=" + s.classId(), null);
		var response = result.getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentType()).startsWith("text/csv");
		assertThat(response.getHeader("Content-Disposition")).contains("attachment").contains("points-report.csv");
		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		byte[] bytes = response.getContentAsByteArray();
		// UTF-8 byte order mark, CRLF line ends, English headers by default, one line per row
		assertThat(bytes[0]).isEqualTo((byte) 0xEF);
		assertThat(bytes[1]).isEqualTo((byte) 0xBB);
		assertThat(bytes[2]).isEqualTo((byte) 0xBF);
		String text = new String(bytes, StandardCharsets.UTF_8).substring(1);
		assertThat(text).startsWith("Student,Skill,Type,Points,Awards,Undone\r\n");
		String[] lines = text.split("\r\n");
		assertThat(lines).hasSize(1 + 4);
		assertThat(text).doesNotContain("Homework done");
		assertThat(text).contains("Abel,Kind words,Positive,2,2,0").contains("Bethlehem,Unkind words".replace("Unkind words", "Disturbing others"));
		// audited without the names
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_logs where action = 'points.report.export' and actor_user_id = ?
				  and (metadata ->> 'rows')::int = 4 and cast(metadata as text) not like '%Abel%'""", Integer.class, s.leadId()))
			.isEqualTo(1);
		// headers in the caller's language
		String amharic = new String(csv(s, s.lead(), "?classId=" + s.classId(), "am").getResponse().getContentAsByteArray(),
				StandardCharsets.UTF_8);
		assertThat(amharic).contains("ተማሪ,ክህሎት").contains("አዎንታዊ");
	}

	@Test
	void spreadsheetFormulasInNamesAreNeutralized() throws Exception {
		Setup s = setup("Formula school");
		this.jdbc.update("update users set display_name = ? where id = ?", "=HYPERLINK(\"http://evil.test\",\"x\")", s.s1());
		this.jdbc.update("update users set display_name = ? where id = ?", "@SUM(1+1),\"quoted\"", s.s2());
		call(HttpMethod.POST, s.classApi() + "/point-categories", s.lead(), Map.of("names", Map.of("en", "-2+3 skill")), 201);
		UUID skill = this.jdbc.queryForObject("select id from point_categories where class_id = ?", UUID.class, s.classId());
		award(s, s.lead(), skill, List.of(s.s1(), s.s2()), 201);
		String text = new String(csv(s, s.lead(), "?classId=" + s.classId(), null).getResponse().getContentAsByteArray(),
				StandardCharsets.UTF_8);
		assertThat(text).contains("'=HYPERLINK(").contains("'@SUM(1+1)").contains("'-2+3 skill");
		// no cell may start with a formula character (outside quotes): the numbers are numbers
		for (String line : text.substring(1).split("\r\n")) {
			assertThat(line).doesNotStartWith("=").doesNotStartWith("@").doesNotStartWith("+").doesNotStartWith("-");
		}
		assertThat(PointReports.safe("\tcmd")).startsWith("'");
		assertThat(PointReports.safe("plain")).isEqualTo("plain");
		assertThat(PointReports.safe(null)).isEmpty();
	}

	@Test
	void exportsAreRateLimited() throws Exception {
		Setup s = withPoints("Rate limit school");
		Persona admin = this.personas.schoolAdmin(s.schoolId(), "rate-limited-admin");
		int ok = 0;
		int limited = 0;
		for (int i = 0; i < 12; i++) {
			int status = csv(s, admin, "", null).getResponse().getStatus();
			if (status == 200) {
				ok++;
			}
			else if (status == 429) {
				limited++;
			}
		}
		assertThat(ok).isEqualTo(10);
		assertThat(limited).isEqualTo(2);
	}

}

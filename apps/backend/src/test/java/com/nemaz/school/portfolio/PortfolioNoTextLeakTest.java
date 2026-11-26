package com.nemaz.school.portfolio;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule book "Never in logs, events, audit or notifications": the text of a portfolio (titles, descriptions, reflections, text,
 * notes, comments, reasons) exists in the portfolio tables and nowhere else.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PortfolioNoTextLeakTest extends PortfolioTestSupport {

	private static final List<String> CANARIES = List.of("CANARY-TITLE", "CANARY-DESC", "CANARY-REFLECT", "CANARY-TEXT", "CANARY-NOTE",
			"CANARY-COMMENT", "CANARY-REASON", "CANARY-EDIT", "CANARY-CAPTURE");

	@Test
	void portfolioTextIsNeverInAuditEventsNotificationsOrLogs(CapturedOutput output) throws Exception {
		Pf p = pf("leak");
		cap(p, "CLASS");
		grant(p.guardian1(), p.s1());
		grant(p.guardian2(), p.s2());
		// every kind of write, each with its own canary
		UUID media = photo(p.student1(), p.schoolId());
		UUID id = id(create(p.student1(), p, photoBody("CANARY-TITLE one", List.of(media), "description", "CANARY-DESC one",
				"reflection", "CANARY-REFLECT one", "visibility", "CLASS", "taggedStudentIds", List.of(p.s2()),
				"confirmNoUnconsentedStudents", true), 201));
		call(HttpMethod.PATCH, p.item(id), p.student1(), body("title", "CANARY-EDIT title", "reflection", "CANARY-REFLECT two"), 200);
		post(p.item(id) + "/submit", p.student1(), null, 200);
		post(p.item(id) + "/review", p.lead(), body("decision", "REQUEST_CHANGES", "note", "CANARY-NOTE one"), 200);
		post(p.item(id) + "/submit", p.student1(), null, 200);
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE", "note", "CANARY-NOTE two"), 200);
		UUID comment = id(post(p.item(id) + "/comments", p.guardian1(), body("body", "CANARY-COMMENT parent"), 201));
		post(p.item(id) + "/comments", p.lead(), body("body", "CANARY-COMMENT teacher"), 201);
		post(p.item(id) + "/comments", p.student1(), body("body", "CANARY-COMMENT student"), 201);
		post(p.item(id) + "/comments/" + comment + "/hide", p.lead(), body("reason", "CANARY-REASON hidden"), 200);
		call(HttpMethod.PUT, p.item(id) + "/featured", p.lead(), null, 200);
		post(p.item(id) + "/archive", p.lead(), body("note", "CANARY-NOTE archive"), 200);
		post(p.item(id) + "/unarchive", p.lead(), body("note", "CANARY-NOTE unarchive"), 200);
		get(p.item(id), p.admin());
		post(p.item(id) + "/share-to-story", p.lead(), null, 422);
		UUID captured = id(post(p.classApi() + "/portfolio/quick-capture", p.lead(), body("studentId", p.s1(), "itemType", "TEXT",
				"title", "CANARY-CAPTURE title", "textContent", "CANARY-TEXT captured", "description", "CANARY-DESC captured"), 201));
		UUID deleted = id(create(p.student1(), p, textBody("CANARY-TITLE deleted", "CANARY-TEXT deleted", "mode", "SUBMIT"), 201));
		call(HttpMethod.DELETE, p.item(deleted), p.student1(), null, 204);
		awaitNotifications(p.guardian1().userId(), "PORTFOLIO_NEW_ITEM", 2);
		awaitNotifications(p.student1().userId(), "PORTFOLIO_COMMENT", 2);
		awaitNotifications(p.lead().userId(), "PORTFOLIO_SUBMITTED", 3);
		awaitNotifications(p.student1().userId(), "PORTFOLIO_REVIEWED", 3);
		// sanity: the canaries do exist where they belong
		assertThat(this.jdbc.queryForObject("select title from portfolio_items where id = ?", String.class, captured)).contains("CANARY-CAPTURE");
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_comments where body like '%CANARY-COMMENT%'", Integer.class)).isEqualTo(3);
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_approvals where note like '%CANARY-NOTE%'", Integer.class)).isGreaterThanOrEqualTo(4);

		// audit rows of the school: ids, statuses, counts
		List<String> audit = this.jdbc.queryForList("select coalesce(metadata::text, '') || coalesce(changes::text, '') from audit_logs where school_id = ?",
				String.class, p.schoolId());
		assertThat(audit).isNotEmpty();
		assertThat(String.join("\n", audit)).doesNotContain("CANARY");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where school_id = ? and action like 'portfolio.%'", Integer.class, p.schoolId()))
			.isGreaterThan(15);
		// stored events
		List<String> events = this.jdbc.queryForList("select serialized_event from event_publication where event_type like 'com.nemaz.school.portfolio.%'",
				String.class);
		assertThat(events).isNotEmpty();
		assertThat(String.join("\n", events)).doesNotContain("CANARY");
		// notifications of everybody involved
		List<String> notes = this.jdbc.queryForList("select title || ' ' || coalesce(body, '') || ' ' || coalesce(data::text, '') from notifications where school_id = ?",
				String.class, p.schoolId());
		assertThat(notes).isNotEmpty();
		assertThat(String.join("\n", notes)).doesNotContain("CANARY");
		// the application log
		for (String canary : CANARIES) {
			assertThat(output.getAll()).as("log contains " + canary).doesNotContain(canary);
		}
	}

}

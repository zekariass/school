package com.nemaz.school.communications;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The delivery report (who may see it, what it shows), its CSV, and the reminder to the unacknowledged. */
@IntegrationTest
class AnnouncementReportTest extends CommunicationsTestSupport {

	private UUID askingForAcknowledgement(Comms c, String title) throws Exception {
		UUID id = UUID.fromString(call(HttpMethod.POST, c.api(), c.lead1A(), with(announcement(title, "Body of " + title,
				classAudience(c.class1A())), "mode", "PUBLISH", "type", "IMPORTANT", "requiresAcknowledgement", true), 201)
			.get("id")
			.asString());
		awaitDelivered(id);
		return id;
	}

	@Test
	void theReportIsForTheAuthorAndTheManagersAndNamesNobodyElse() throws Exception {
		Comms c = comms("report-privacy");
		UUID id = publishNow(c, c.lead1A(), "Report me", classAudience(c.class1A()));
		awaitDelivered(id);
		String report = c.api() + "/" + id + "/report";
		for (String suffix : List.of("", "/recipients")) {
			call(HttpMethod.GET, report + suffix, c.lead1A(), null, 200);
			call(HttpMethod.GET, report + suffix, c.admin(), null, 200);
			call(HttpMethod.GET, report + suffix, c.gA1(), null, 403);
			call(HttpMethod.GET, report + suffix, c.co1A(), null, 404);
			call(HttpMethod.GET, report + suffix, c.student1(), null, 404);
			call(HttpMethod.GET, report + suffix, c.gB1(), null, 404);
			call(HttpMethod.GET, report + suffix, c.gRestricted(), null, 404);
		}
		assertThat(statusOf(HttpMethod.GET, c.api() + "/" + id + "/report.csv", c.gA1(), null)).isEqualTo(403);
		assertThat(statusOf(HttpMethod.GET, c.api() + "/" + id + "/report.csv", c.lead1B(), null)).isEqualTo(404);
		call(HttpMethod.POST, c.api() + "/" + id + "/remind", c.gA1(), null, 403);
		Comms other = comms("report-privacy-other");
		assertThat(code(call(HttpMethod.GET, report, other.admin(), null, 404))).isEqualTo("school.not_found");
		// a draft has no report yet
		JsonNode draft = call(HttpMethod.POST, c.api(), c.lead1A(), announcement("d", "b", classAudience(c.class1A())), 201);
		assertThat(code(call(HttpMethod.GET, c.api() + "/" + draft.get("id").asString() + "/report", c.lead1A(), null, 409)))
			.isEqualTo("announcement.not_published");

		// who is on the list: names, the role, the child a parent was reached through, masked contact details only
		JsonNode list = get(report + "/recipients", c.lead1A());
		assertThat(list.get("items").size()).isEqualTo(2);
		String text = list.toString();
		assertThat(text).doesNotContain(phoneOf(c.gA1())).doesNotContain(phoneOf(c.gSib()));
		JsonNode first = list.get("items").get(0);
		assertThat(first.get("role").asString()).isEqualTo("GUARDIAN");
		assertThat(first.get("displayName").asString()).startsWith("report-privacy-g");
		assertThat(first.get("maskedPhone").asString()).contains("*");
		assertThat(first.get("viaStudentName").asString()).isIn("Abel", "Bethlehem");
		assertThat(first.get("push").asString()).isNotBlank();
		assertThat(first.get("email").asString()).isEqualTo("NOT_REQUESTED");
		// the listing is audited without the names
		List<String> audit = this.jdbc.queryForList("select metadata::text from audit_logs where target_id = ? and action = 'announcement.report.read'",
				String.class, id);
		assertThat(audit).isNotEmpty().allSatisfy((m) -> assertThat(m).doesNotContain("report-privacy-g").doesNotContain("Abel"));
	}

	@Test
	void theListIsPagedFilteredAndTheCountsAddUp() throws Exception {
		Comms c = comms("report-paging");
		UUID id = askingForAcknowledgement(c, "Count me");
		String url = c.api() + "/" + id;
		JsonNode page = get(url + "/report/recipients?limit=1", c.admin());
		assertThat(page.get("items").size()).isEqualTo(1);
		assertThat(absent(page, "nextCursor")).isFalse();
		JsonNode next = get(url + "/report/recipients?limit=1&cursor=" + page.get("nextCursor").asString(), c.admin());
		assertThat(next.get("items").size()).isEqualTo(1);
		assertThat(next.get("nextCursor") == null || next.get("nextCursor").isNull()).isTrue();
		assertThat(next.get("items").get(0).get("userId").asString()).isNotEqualTo(page.get("items").get(0).get("userId").asString());
		call(HttpMethod.GET, url + "/report/recipients?cursor=garbage", c.admin(), null, 400);

		call(HttpMethod.POST, url + "/read", c.gA1(), null, 204);
		call(HttpMethod.POST, url + "/acknowledge", c.gSib(), null, 200);
		JsonNode unread = get(url + "/report/recipients?filter=UNREAD", c.admin());
		assertThat(unread.get("items").size()).isZero();
		JsonNode unacknowledged = get(url + "/report/recipients?filter=UNACKNOWLEDGED", c.admin());
		assertThat(unacknowledged.get("items").size()).isEqualTo(1);
		assertThat(unacknowledged.get("items").get(0).get("userId").asString()).isEqualTo(c.gA1().userId().toString());
		assertThat(absent(unacknowledged.get("items").get(0), "readAt")).isFalse();
		assertThat(get(url + "/report/recipients?filter=ACKNOWLEDGED", c.admin()).get("items").size()).isEqualTo(1);
		JsonNode report = get(url + "/report", c.admin());
		assertThat(report.get("recipients").asInt()).isEqualTo(2);
		assertThat(report.get("delivered").asInt()).isEqualTo(2);
		assertThat(report.get("read").asInt()).isEqualTo(2);
		assertThat(report.get("acknowledged").asInt()).isEqualTo(1);
		assertThat(report.get("requiresAcknowledgement").asBoolean()).isTrue();
	}

	@Test
	void theCsvIsSafeAuditedAndRateLimited() throws Exception {
		Comms c = comms("report-csv");
		UUID id = publishNow(c, c.admin(), "Export me", classAudience(c.class1A()));
		awaitDelivered(id);
		this.jdbc.update("update users set display_name = '=HYPERLINK(\"http://evil\",\"x\")' where id = ?", c.gA1().userId());
		MockHttpServletResponse response = perform(HttpMethod.GET, c.api() + "/" + id + "/report.csv", c.admin(), null).andReturn()
			.getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentType()).startsWith("text/csv");
		assertThat(response.getHeader("Content-Disposition")).contains("attachment").contains("announcement-report.csv");
		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		byte[] bytes = response.getContentAsByteArray();
		assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
		assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
		assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);
		String csv = new String(bytes, StandardCharsets.UTF_8);
		assertThat(csv).startsWith("﻿Name,Role,Delivered,Push,Email,SMS,Read,Acknowledged,Acknowledged version\r\n");
		assertThat(csv.split("\r\n")).hasSize(3);
		// a name that looks like a formula is turned into text, and no phone number is in the file
		assertThat(csv).contains("'=HYPERLINK").doesNotContain(phoneOf(c.gA1())).doesNotContain(phoneOf(c.gSib()));
		assertThat(csv).contains("GUARDIAN");
		// the list is audited, with its size and without names
		List<String> audit = this.jdbc.queryForList("select metadata::text from audit_logs where target_id = ? and action = 'announcement.report.export'",
				String.class, id);
		assertThat(audit).hasSize(1);
		assertThat(audit.getFirst()).contains("\"rows\": 2").doesNotContain("HYPERLINK");
		// headers follow the caller's language
		MockHttpServletResponse amharic = this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.get(c.api() + "/" + id + "/report.csv").with(c.admin().bearer()).header("Accept-Language", "am")).andReturn().getResponse();
		assertThat(new String(amharic.getContentAsByteArray(), StandardCharsets.UTF_8)).contains("ስም");
		// ten exports in ten minutes, then 429
		for (int i = 0; i < 8; i++) {
			assertThat(statusOf(HttpMethod.GET, c.api() + "/" + id + "/report.csv", c.admin(), null)).isEqualTo(200);
		}
		assertThat(code(call(HttpMethod.GET, c.api() + "/" + id + "/report.csv", c.admin(), null, 429)))
			.isEqualTo("announcement.export_rate_limited");
	}

	@Test
	void reminderReachesOnlyTheUnacknowledgedAndIsLimited() throws Exception {
		Comms c = comms("report-remind");
		UUID id = askingForAcknowledgement(c, "Please confirm");
		String url = c.api() + "/" + id;
		call(HttpMethod.POST, url + "/acknowledge", c.gA1(), null, 200);
		JsonNode reminder = call(HttpMethod.POST, url + "/remind", c.lead1A(), null, 200);
		assertThat(reminder.get("targeted").asInt()).isEqualTo(1);
		await().atMost(Duration.ofSeconds(10)).until(() -> inboxOf(c.gSib().userId(), "ANNOUNCEMENT_REMINDER").size() == 1);
		assertThat(inboxOf(c.gA1().userId(), "ANNOUNCEMENT_REMINDER")).isEmpty();
		assertThat(String.valueOf(inboxOf(c.gSib().userId(), "ANNOUNCEMENT_REMINDER").getFirst().get("title"))).startsWith("Reminder:");
		// the same notice delivered again (a redelivered event, the resume job) adds nothing
		this.delivery.deliverNotice(UUID.fromString(reminder.get("noticeId").asString()));
		this.maintenance.resumeDeliveries();
		assertThat(inboxOf(c.gSib().userId(), "ANNOUNCEMENT_REMINDER")).hasSize(1);
		// once every N hours
		var tooSoon = perform(HttpMethod.POST, url + "/remind", c.lead1A(), null).andReturn().getResponse();
		assertThat(tooSoon.getStatus()).isEqualTo(429);
		assertThat(tooSoon.getHeader("Retry-After")).isNotBlank();
		assertThat(this.json.readTree(tooSoon.getContentAsString()).get("code").asString()).isEqualTo("announcement.reminder_too_soon");
		this.clock.advance(Duration.ofHours(25));
		call(HttpMethod.POST, url + "/remind", c.admin(), null, 200);
		await().atMost(Duration.ofSeconds(10)).until(() -> inboxOf(c.gSib().userId(), "ANNOUNCEMENT_REMINDER").size() == 2);
		// a person who acknowledges is skipped, and when nobody is left there is nothing to remind
		call(HttpMethod.POST, url + "/acknowledge", c.gSib(), null, 200);
		this.clock.advance(Duration.ofHours(25));
		assertThat(code(call(HttpMethod.POST, url + "/remind", c.lead1A(), null, 409))).isEqualTo("announcement.nobody_to_remind");
		// at most five reminders
		UUID another = askingForAcknowledgement(c, "Another");
		for (int i = 0; i < 5; i++) {
			this.clock.advance(Duration.ofHours(25));
			call(HttpMethod.POST, c.api() + "/" + another + "/remind", c.lead1A(), null, 200);
		}
		this.clock.advance(Duration.ofHours(25));
		assertThat(code(call(HttpMethod.POST, c.api() + "/" + another + "/remind", c.lead1A(), null, 409)))
			.isEqualTo("announcement.reminder_limit_reached");
		this.clock.reset();
		// nothing to remind about when no acknowledgement was asked for; readers and outsiders cannot ask
		UUID plain = publishNow(c, c.lead1A(), "Plain", classAudience(c.class1A()));
		assertThat(code(call(HttpMethod.POST, c.api() + "/" + plain + "/remind", c.lead1A(), null, 409))).isEqualTo("announcement.ack_not_required");
		call(HttpMethod.POST, url + "/remind", c.gSib(), null, 403);
		call(HttpMethod.POST, url + "/remind", c.gB1(), null, 404);
		// reminders are audited
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and action = 'announcement.remind'",
				Integer.class, id)).isEqualTo(2);
		// the report counts them
		JsonNode report = get(url + "/report", c.admin());
		assertThat(report.get("reminders").asInt()).isEqualTo(2);
		assertThat(absent(report, "lastReminderAt")).isFalse();
	}

	@Test
	void remindersOfUrgentAnnouncementsArePushedAsUrgent() throws Exception {
		Comms c = comms("report-remind-urgent");
		UUID id = UUID.fromString(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("Drill", "Body", classAudience(c.class1A())),
				"mode", "PUBLISH", "type", "URGENT", "requiresAcknowledgement", true), 201).get("id").asString());
		awaitDelivered(id);
		call(HttpMethod.POST, c.api() + "/" + id + "/remind", c.admin(), Map.of(), 200);
		await().atMost(Duration.ofSeconds(10)).until(() -> inboxOf(c.gA1().userId(), "ANNOUNCEMENT_REMINDER").size() == 1);
		assertThat(inboxOf(c.gA1().userId(), "ANNOUNCEMENT_REMINDER").getFirst().get("priority")).isEqualTo("URGENT");
		assertThat(inboxOf(c.gA1().userId(), "ACK_REQUESTED").getFirst().get("priority")).isEqualTo("URGENT");
	}

}

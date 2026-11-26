package com.nemaz.school.communications;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Draft, schedule, publish, edit with revisions, withdraw: the state machine, its audit trail and its validation. */
@IntegrationTest
class AnnouncementLifecycleTest extends CommunicationsTestSupport {

	@Test
	void draftEditPublishAndTheAuditTrailCarriesNoText() throws Exception {
		Comms c = comms("life-publish");
		String secretTitle = "Zebra-Title-Marker";
		String secretBody = "Zebra-Body-Marker about Abel";
		JsonNode draft = call(HttpMethod.POST, c.api(), c.lead1A(),
				announcement(secretTitle, secretBody, classAudience(c.class1A())), 201);
		UUID id = UUID.fromString(draft.get("id").asString());
		assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
		assertThat(draft.get("type").asString()).isEqualTo("NORMAL");
		assertThat(draft.get("bodyFormat").asString()).isEqualTo("PLAIN_TEXT");
		assertThat(draft.get("can").get("publish").asBoolean()).isTrue();
		// a draft is the author's and the managers' alone
		call(HttpMethod.GET, c.api() + "/" + id, c.gA1(), null, 404);
		call(HttpMethod.GET, c.api() + "/" + id, c.admin(), null, 200);
		assertThat(idsOf(get(c.api() + "/authored", c.lead1A()))).contains(id.toString());
		assertThat(idsOf(get(c.api() + "/authored", c.admin()))).contains(id.toString());
		assertThat(idsOf(get(c.api() + "/authored", c.lead1B()))).doesNotContain(id.toString());
		assertThat(idsOf(get(c.api(), c.gA1()))).doesNotContain(id.toString());
		JsonNode edited = call(HttpMethod.PATCH, c.api() + "/" + id, c.lead1A(), Map.of("title", secretTitle + " v2"), 200);
		assertThat(edited.get("status").asString()).isEqualTo("DRAFT");
		assertThat(edited.get("revision").asInt()).isEqualTo(1);
		assertThat(get(c.api() + "/" + id + "/revisions", c.lead1A())).isEmpty();
		assertThat(get(c.api() + "/" + id + "/preview", c.lead1A()).get("guardians").asInt()).isEqualTo(2);
		// somebody else's teacher cannot publish it
		call(HttpMethod.POST, c.api() + "/" + id + "/publish", c.lead1B(), null, 404);

		JsonNode published = call(HttpMethod.POST, c.api() + "/" + id + "/publish", c.lead1A(), null, 200);
		assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(published.get("recipientCount").asInt()).isEqualTo(2);
		// publishing again changes nothing
		call(HttpMethod.POST, c.api() + "/" + id + "/publish", c.lead1A(), null, 200);
		awaitDelivered(id);
		assertThat(recipients(id)).isEqualTo(ids(c.gA1(), c.gSib()));
		assertThat(inboxOf(c.gA1().userId(), "ANNOUNCEMENT_PUBLISHED")).hasSize(1);
		Map<String, Object> notification = inboxOf(c.gA1().userId(), "ANNOUNCEMENT_PUBLISHED").getFirst();
		assertThat(notification.get("title")).isEqualTo(secretTitle + " v2");
		assertThat(String.valueOf(notification.get("body"))).doesNotContain("Zebra").contains("life-publish");
		assertThat(String.valueOf(notification.get("data"))).contains(id.toString()).contains("/announcements/" + id);
		// the recipients read it with its text
		JsonNode seen = get(c.api() + "/" + id, c.gA1());
		assertThat(seen.get("body").asString()).isEqualTo(secretBody);
		assertThat(absent(seen, "recipientCount")).isTrue();
		assertThat(absent(seen, "audiences")).isTrue();
		assertThat(seen.get("can").get("edit").asBoolean()).isFalse();
		assertThat(seen.get("mine").get("recipient").asBoolean()).isTrue();

		// every step was audited, with ids and counts and never with the text
		List<Map<String, Object>> audit = this.jdbc.queryForList(
				"select action, metadata::text as metadata, changes::text as changes from audit_logs where target_id = ? order by id", id);
		assertThat(audit).extracting((row) -> row.get("action")).contains("announcement.create", "announcement.update",
				"announcement.publish");
		assertThat(audit).allSatisfy((row) -> assertThat(row.get("metadata") + " " + row.get("changes")).doesNotContain("Zebra"));
		Map<String, Object> publish = audit.stream().filter((r) -> "announcement.publish".equals(r.get("action"))).findFirst().orElseThrow();
		assertThat((String) publish.get("metadata")).contains("\"recipients\": 2").contains("CLASS");
	}

	@Test
	void schedulingPublishesOnceAtItsTimeAndUnschedulingStopsIt() throws Exception {
		Comms c = comms("life-schedule");
		Instant at = this.clock.instant().plus(Duration.ofHours(2));
		JsonNode scheduled = call(HttpMethod.POST, c.api(), c.lead1A(),
				with(announcement("Later", "Body", classAudience(c.class1A())), "mode", "SCHEDULE", "scheduledFor", at.toString()),
				201);
		UUID id = UUID.fromString(scheduled.get("id").asString());
		assertThat(scheduled.get("status").asString()).isEqualTo("SCHEDULED");
		// validation of the time
		assertThat(code(call(HttpMethod.POST, c.api(), c.lead1A(), with(announcement("t", "b", classAudience(c.class1A())), "mode",
				"SCHEDULE", "scheduledFor", this.clock.instant().minusSeconds(60).toString()), 400))).isEqualTo("announcement.schedule_in_past");
		assertThat(code(call(HttpMethod.POST, c.api(), c.lead1A(), with(announcement("t", "b", classAudience(c.class1A())), "mode",
				"SCHEDULE", "scheduledFor", this.clock.instant().plus(Duration.ofDays(400)).toString()), 400)))
			.isEqualTo("announcement.schedule_too_far");
		assertThat(code(call(HttpMethod.POST, c.api(), c.lead1A(), with(announcement("t", "b", classAudience(c.class1A())), "mode",
				"SCHEDULE"), 400))).isEqualTo("announcement.schedule_required");

		// not due yet: the job leaves it
		assertThat(this.maintenance.publishScheduled()).isZero();
		assertThat(statusOf(id)).isEqualTo("SCHEDULED");
		this.clock.advance(Duration.ofHours(3));
		// the job is idempotent: two runs (two instances) publish it once
		assertThat(this.maintenance.publishScheduled()).isEqualTo(1);
		assertThat(this.maintenance.publishScheduled()).isZero();
		assertThat(statusOf(id)).isEqualTo("PUBLISHED");
		awaitDelivered(id);
		assertThat(inboxOf(c.gA1().userId(), "ANNOUNCEMENT_PUBLISHED")).hasSize(1);
		assertThat(inboxOf(c.gSib().userId(), "ANNOUNCEMENT_PUBLISHED")).hasSize(1);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and action = 'announcement.publish'",
				Integer.class, id)).isEqualTo(1);
		this.clock.reset();

		// unscheduling returns it to a draft the job ignores
		JsonNode second = call(HttpMethod.POST, c.api(), c.lead1A(),
				with(announcement("Never", "Body", classAudience(c.class1A())), "mode", "SCHEDULE", "scheduledFor",
						this.clock.instant().plus(Duration.ofHours(1)).toString()), 201);
		UUID secondId = UUID.fromString(second.get("id").asString());
		assertThat(call(HttpMethod.POST, c.api() + "/" + secondId + "/unschedule", c.lead1A(), null, 200).get("status").asString())
			.isEqualTo("DRAFT");
		this.clock.advance(Duration.ofHours(2));
		assertThat(this.maintenance.publishScheduled()).isZero();
		assertThat(statusOf(secondId)).isEqualTo("DRAFT");
	}

	@Test
	void aScheduledAnnouncementTheAuthorMayNoLongerSendGoesBackToDraftAndTheAuthorIsTold() throws Exception {
		Comms c = comms("life-blocked");
		JsonNode scheduled = call(HttpMethod.POST, c.api(), c.lead1A(),
				with(announcement("Blocked", "Body", classAudience(c.class1A())), "mode", "SCHEDULE", "scheduledFor",
						this.clock.instant().plus(Duration.ofHours(1)).toString()), 201);
		UUID id = UUID.fromString(scheduled.get("id").asString());
		// the teacher leaves the class before the time comes
		this.jdbc.update("update class_teachers set status = 'FORMER', left_at = now() where teacher_id = ? and class_id = ?",
				c.lead1A().userId(), c.class1A());
		this.clock.advance(Duration.ofHours(2));
		assertThat(this.maintenance.publishScheduled()).isZero();
		assertThat(statusOf(id)).isEqualTo("DRAFT");
		assertThat(this.jdbc.queryForObject("select schedule_error_code from announcements where id = ?", String.class, id))
			.isEqualTo("class.not_found");
		assertThat(this.jdbc.queryForObject("select count(*) from announcement_recipients where announcement_id = ?", Integer.class, id))
			.isZero();
		await().atMost(Duration.ofSeconds(10))
			.until(() -> !inboxOf(c.lead1A().userId(), "ANNOUNCEMENT_PUBLISH_BLOCKED").isEmpty());
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and action = 'announcement.publish_blocked'",
				Integer.class, id)).isEqualTo(1);
		// the author sees why
		this.clock.reset();
		assertThat(get(c.api() + "/" + id, c.admin()).get("scheduleErrorCode").asString()).isEqualTo("class.not_found");
	}

	@Test
	void editsAfterPublishingKeepRevisionsAndOnlyMaterialOnesTellTheRecipients() throws Exception {
		Comms c = comms("life-edit");
		UUID id = publishNow(c, c.lead1A(), "Trip on Fridy", classAudience(c.class1A()));
		awaitDelivered(id);
		String url = c.api() + "/" + id;
		// the editor must decide
		assertThat(code(call(HttpMethod.PATCH, url, c.lead1A(), Map.of("title", "Trip on Friday"), 400)))
			.isEqualTo("announcement.material_required");
		// a typo: a revision, no notification
		JsonNode typo = call(HttpMethod.PATCH, url, c.lead1A(), Map.of("title", "Trip on Friday", "material", false), 200);
		assertThat(typo.get("revision").asInt()).isEqualTo(2);
		assertThat(absent(typo, "editedAt")).isFalse();
		assertThat(typo.get("title").asString()).isEqualTo("Trip on Friday");
		// no change at all is not an edit
		assertThat(call(HttpMethod.PATCH, url, c.lead1A(), Map.of("title", "Trip on Friday", "material", true), 200).get("revision").asInt())
			.isEqualTo(2);
		this.maintenance.resumeDeliveries();
		assertThat(inboxOf(c.gA1().userId(), "ANNOUNCEMENT_UPDATED")).isEmpty();
		// a real change: told once, even if the job and the event both run
		call(HttpMethod.PATCH, url, c.admin(), Map.of("body", "The trip moved to Monday.", "material", true, "changeNote", "new day"), 200);
		await().atMost(Duration.ofSeconds(10)).until(() -> inboxOf(c.gSib().userId(), "ANNOUNCEMENT_UPDATED").size() == 1);
		this.maintenance.resumeDeliveries();
		assertThat(inboxOf(c.gA1().userId(), "ANNOUNCEMENT_UPDATED")).hasSize(1);
		assertThat(inboxOf(c.gSib().userId(), "ANNOUNCEMENT_UPDATED")).hasSize(1);
		assertThat(inboxOf(c.gX().userId(), "ANNOUNCEMENT_UPDATED")).isEmpty();
		// the history keeps what was replaced
		JsonNode revisions = get(url + "/revisions", c.lead1A());
		assertThat(revisions.size()).isEqualTo(2);
		assertThat(revisions.get(0).get("revision").asInt()).isEqualTo(2);
		assertThat(revisions.get(0).get("material").asBoolean()).isTrue();
		assertThat(revisions.get(0).get("changeNote").asString()).isEqualTo("new day");
		assertThat(revisions.get(1).get("title").asString()).isEqualTo("Trip on Fridy");
		assertThat(revisions.get(1).get("material").asBoolean()).isFalse();
		JsonNode current = get(url, c.gA1());
		assertThat(current.get("revision").asInt()).isEqualTo(3);
		assertThat(current.get("body").asString()).isEqualTo("The trip moved to Monday.");
		// revisions and edits belong to the author and managers: readers get 403, outsiders 404
		call(HttpMethod.GET, url + "/revisions", c.gA1(), null, 403);
		call(HttpMethod.PATCH, url, c.gA1(), Map.of("title", "Mine", "material", true), 403);
		call(HttpMethod.GET, url + "/revisions", c.gB1(), null, 404);
		// what is fixed after publishing
		assertThat(code(call(HttpMethod.PATCH, url, c.lead1A(), Map.of("type", "IMPORTANT"), 409))).isEqualTo("announcement.locked_after_publish");
		assertThat(code(call(HttpMethod.PATCH, url, c.lead1A(), Map.of("audiences", List.of(schoolAudience())), 409)))
			.isEqualTo("announcement.locked_after_publish");
		// the audit rows say what changed without saying what it says
		assertThat(this.jdbc.queryForList("select metadata::text from audit_logs where target_id = ? and action = 'announcement.update'",
				String.class, id)).allSatisfy((m) -> assertThat(m).doesNotContain("Monday").doesNotContain("Friday"));
	}

	@Test
	void withdrawingLeavesATombstoneRecipientsCanSee() throws Exception {
		Comms c = comms("life-withdraw");
		UUID id = UUID.fromString(call(HttpMethod.POST, c.api(), c.lead1A(),
				with(announcement("Wrong date", "Please come on the 3rd.", classAudience(c.class1A())), "mode", "PUBLISH", "type",
						"IMPORTANT", "requiresAcknowledgement", true), 201).get("id").asString());
		awaitDelivered(id);
		String url = c.api() + "/" + id;
		assertThat(inboxOf(c.gA1().userId(), "ACK_REQUESTED")).hasSize(1);
		call(HttpMethod.POST, url + "/acknowledge", c.gA1(), null, 200);
		// a reader cannot withdraw; the author's lead colleague (no right) cannot either
		call(HttpMethod.POST, url + "/withdraw", c.gA1(), Map.of("reason", "x"), 403);
		call(HttpMethod.POST, url + "/withdraw", c.lead1B(), Map.of("reason", "x"), 404);
		JsonNode withdrawn = call(HttpMethod.POST, url + "/withdraw", c.admin(), Map.of("reason", "The date was wrong; a new one follows."), 200);
		assertThat(withdrawn.get("status").asString()).isEqualTo("WITHDRAWN");
		assertThat(withdrawn.get("body").asString()).isEqualTo("Please come on the 3rd.");
		// recipients: the tombstone has the title, when and why, not the body
		JsonNode tomb = get(url, c.gA1());
		assertThat(tomb.get("status").asString()).isEqualTo("WITHDRAWN");
		assertThat(tomb.get("title").asString()).isEqualTo("Wrong date");
		assertThat(absent(tomb, "body")).isTrue();
		assertThat(tomb.get("withdrawalReason").asString()).isEqualTo("The date was wrong; a new one follows.");
		assertThat(absent(tomb, "withdrawnAt")).isFalse();
		JsonNode card = get("/api/v1/me/announcements", c.gA1()).get("items").get(0);
		assertThat(card.get("status").asString()).isEqualTo("WITHDRAWN");
		assertThat(absent(card, "excerpt")).isTrue();
		// the author keeps the text
		assertThat(get(url, c.lead1A()).get("body").asString()).isEqualTo("Please come on the 3rd.");
		// it cannot be acknowledged, edited or published any more; withdrawing again is harmless
		assertThat(code(call(HttpMethod.POST, url + "/acknowledge", c.gSib(), null, 409))).isEqualTo("announcement.withdrawn");
		assertThat(code(call(HttpMethod.PATCH, url, c.lead1A(), Map.of("title", "x", "material", true), 409))).isEqualTo("announcement.withdrawn");
		assertThat(code(call(HttpMethod.POST, url + "/publish", c.lead1A(), null, 409))).isEqualTo("announcement.withdrawn");
		call(HttpMethod.POST, url + "/withdraw", c.lead1A(), Map.of(), 200);
		assertThat(code(call(HttpMethod.DELETE, url, c.lead1A(), null, 409))).isEqualTo("announcement.invalid_state");
		// the recipients are told, and the row is an audited tombstone that still exists
		await().atMost(Duration.ofSeconds(10)).until(() -> inboxOf(c.gA1().userId(), "ANNOUNCEMENT_WITHDRAWN").size() == 1);
		assertThat(this.jdbc.queryForObject("select count(*) from announcements where id = ? and status = 'WITHDRAWN'", Integer.class, id))
			.isEqualTo(1);
		List<String> audit = this.jdbc.queryForList("select metadata::text from audit_logs where target_id = ? and action = 'announcement.withdraw'",
				String.class, id);
		assertThat(audit).hasSize(1);
		assertThat(audit.getFirst()).contains("\"hasReason\": true").doesNotContain("wrong");
		// the withdrawn one is not a draft: it cannot be deleted, a draft can
		JsonNode draft = call(HttpMethod.POST, c.api(), c.lead1A(), announcement("Draft", "b", classAudience(c.class1A())), 201);
		call(HttpMethod.DELETE, c.api() + "/" + draft.get("id").asString(), c.lead1A(), null, 204);
		call(HttpMethod.GET, c.api() + "/" + draft.get("id").asString(), c.lead1A(), null, 404);
	}

	@Test
	void validationAndTheRulesOfTypes() throws Exception {
		Comms c = comms("life-validation");
		// urgent and safety need their own permission
		for (String type : List.of("URGENT", "SAFETY")) {
			assertThat(code(call(HttpMethod.POST, c.api(), c.lead1A(), with(announcement("t", "b", classAudience(c.class1A())), "type", type), 403)))
				.isEqualTo("announcement.urgent_not_allowed");
			call(HttpMethod.POST, c.api(), c.admin(), with(announcement("t", "b", classAudience(c.class1A())), "type", type), 201);
		}
		// an acknowledgement only makes sense above NORMAL
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("t", "b", classAudience(c.class1A())),
				"requiresAcknowledgement", true), 400))).isEqualTo("announcement.ack_not_allowed");
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), announcement("   ", "b", classAudience(c.class1A())), 400)))
			.isEqualTo("announcement.title_required");
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), announcement("t", " ", classAudience(c.class1A())), 400)))
			.isEqualTo("announcement.body_required");
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), announcement("t".repeat(201), "b", classAudience(c.class1A())), 400)))
			.isEqualTo("announcement.title_too_long");
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), announcement("t", "b".repeat(5001), classAudience(c.class1A())), 400)))
			.isEqualTo("announcement.body_too_long");
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("t", "b", classAudience(c.class1A())),
				"bodyLanguageCode", "xx-nonsense"), 400))).isEqualTo("announcement.language_invalid");
		// a class of another school, a campus of another school
		Comms other = comms("life-validation-other");
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), announcement("t", "b", classAudience(other.class1A())), 404)))
			.isEqualTo("class.not_found");
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), announcement("t", "b", campusAudience(other.campus1())), 400)))
			.isEqualTo("announcement.audience_invalid");
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), announcement("t", "b", gradeAudience(other.grade())), 400)))
			.isEqualTo("announcement.audience_invalid");
		// nobody to send to
		UUID empty = this.personas.newClass(c.schoolId(), "Empty");
		JsonNode draft = call(HttpMethod.POST, c.api(), c.admin(), announcement("t", "b", classAudience(empty)), 201);
		assertThat(code(call(HttpMethod.POST, c.api() + "/" + draft.get("id").asString() + "/publish", c.admin(), null, 422)))
			.isEqualTo("announcement.no_recipients");
		// optional channels are unavailable until the school switches them on
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("t", "b", classAudience(c.class1A())), "channels",
				List.of("PUSH", "SMS")), 400))).isEqualTo("announcement.sms_not_allowed");
		// the feature switch
		this.jdbc.update("insert into feature_overrides (feature_key, scope_type, school_id, enabled) values ('announcements', 'SCHOOL', ?, false)",
				c.schoolId());
		this.caches().clear();
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), announcement("t", "b", classAudience(c.class1A())), 403)))
			.isEqualTo("announcement.feature_disabled");
		this.jdbc.update("delete from feature_overrides where feature_key = 'announcements' and school_id = ?", c.schoolId());
		this.caches().clear();
	}

	@Test
	void textIsPlainAndNeverMarkup() throws Exception {
		Comms c = comms("life-xss");
		String script = "<script>alert('x')</script><img src=x onerror=alert(1)> & \"quotes\" 5 < 6";
		JsonNode created = call(HttpMethod.POST, c.api(), c.admin(),
				with(announcement("<b>Bold</b> title", script, classAudience(c.class1A())), "mode", "PUBLISH"), 201);
		UUID id = UUID.fromString(created.get("id").asString());
		awaitDelivered(id);
		// stored and returned as the characters that were typed, labelled plain text, as JSON (never as HTML)
		var response = perform(HttpMethod.GET, c.api() + "/" + id, c.gA1(), null).andReturn().getResponse();
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		JsonNode seen = this.json.readTree(response.getContentAsString());
		assertThat(seen.get("body").asString()).isEqualTo(script);
		assertThat(seen.get("title").asString()).isEqualTo("<b>Bold</b> title");
		assertThat(seen.get("bodyFormat").asString()).isEqualTo("PLAIN_TEXT");
		// control characters and the invisible direction overrides that can disguise a title are dropped, lines normalized
		JsonNode tricky = call(HttpMethod.POST, c.api(), c.admin(),
				announcement("Fee‮sdrawkcab​ due\u0007", "one\r\ntwo\rthree\u0000", classAudience(c.class1A())), 201);
		assertThat(tricky.get("title").asString()).isEqualTo("Feesdrawkcab due");
		assertThat(tricky.get("body").asString()).isEqualTo("one\ntwo\nthree");
		// translations are written by people, with known languages
		JsonNode translated = call(HttpMethod.POST, c.api(), c.admin(), with(announcement("Trip", "Details", classAudience(c.class1A())),
				"bodyLanguageCode", "en", "translations", Map.of("am", Map.of("title", "ጉዞ", "body", "ዝርዝር"))), 201);
		assertThat(translated.get("translations").get("am").get("title").asString()).isEqualTo("ጉዞ");
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("Trip", "Details", classAudience(c.class1A())),
				"bodyLanguageCode", "en", "translations", Map.of("en", Map.of("title", "x", "body", "y"))), 400)))
			.isEqualTo("announcement.language_invalid");
	}

	private String statusOf(UUID id) {
		return this.jdbc.queryForObject("select status from announcements where id = ?", String.class, id);
	}

	@org.springframework.beans.factory.annotation.Autowired
	org.springframework.cache.CacheManager cacheManager;

	private org.springframework.cache.Cache caches() {
		return this.cacheManager.getCache("school-configuration");
	}

}

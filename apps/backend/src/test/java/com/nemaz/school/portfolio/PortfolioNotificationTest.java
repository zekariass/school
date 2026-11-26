package com.nemaz.school.portfolio;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.portfolio.PortfolioCommentPosted;
import com.nemaz.school.portfolio.PortfolioItemReviewed;
import com.nemaz.school.portfolio.PortfolioItemSubmitted;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Rule book P3: notifications are generic, idempotent and sent to the right people only. */
@IntegrationTest
class PortfolioNotificationTest extends PortfolioTestSupport {

	@Autowired
	ApplicationEventPublisher publisher;

	private int count(Persona who, String type) {
		return notificationCount(who.userId(), type);
	}

	private List<Map<String, Object>> rows(Persona who) {
		return this.jdbc.queryForList("select type_code, title, body, data::text as data, source_type from notifications where user_id = ?", who.userId());
	}

	@Test
	void submissionNotifiesReviewersGenerically() throws Exception {
		Pf p = pf("not1");
		UUID id = id(create(p.student1(), p, textBody("SUBMIT-TITLE-CANARY", "x", "mode", "SUBMIT", "subjectId", p.mathsId(),
				"reflection", "SUBMIT-REFLECTION-CANARY"), 201));
		awaitNotifications(p.lead().userId(), "PORTFOLIO_SUBMITTED", 1);
		awaitNotifications(p.co().userId(), "PORTFOLIO_SUBMITTED", 1);
		awaitNotifications(p.mathsTeacher().userId(), "PORTFOLIO_SUBMITTED", 1);
		for (Persona none : List.of(p.artTeacher(), p.assistant(), p.admin(), p.student1(), p.guardian1(), p.student2(), p.otherTeacher())) {
			assertThat(count(none, "PORTFOLIO_SUBMITTED")).as(none.name()).isZero();
		}
		Map<String, Object> row = rows(p.lead()).getFirst();
		assertThat(row.get("title")).isEqualTo("A portfolio item is ready for review");
		assertThat(row.get("data").toString()).contains("/portfolio/items/" + id);
		assertThat(row.get("title").toString() + row.get("body") + row.get("data")).doesNotContain("CANARY", "Abel");
		// a resubmission is a new approval: the reviewers hear again, once
		post(p.item(id) + "/review", p.lead(), body("decision", "REQUEST_CHANGES", "note", "more"), 200);
		post(p.item(id) + "/submit", p.student1(), null, 200);
		awaitNotifications(p.lead().userId(), "PORTFOLIO_SUBMITTED", 2);
	}

	@Test
	void reviewNotifiesTheStudentGenerically() throws Exception {
		Pf p = pf("not2");
		UUID id = submitted(p, p.student1(), "reviewed");
		post(p.item(id) + "/review", p.lead(), body("decision", "REQUEST_CHANGES", "note", "NOTE-CANARY add more"), 200);
		awaitNotifications(p.student1().userId(), "PORTFOLIO_REVIEWED", 1);
		post(p.item(id) + "/submit", p.student1(), null, 200);
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE", "note", "NOTE-CANARY well done"), 200);
		awaitNotifications(p.student1().userId(), "PORTFOLIO_REVIEWED", 2);
		for (Map<String, Object> row : rows(p.student1())) {
			if (row.get("type_code").equals("PORTFOLIO_REVIEWED")) {
				assertThat(row.get("title")).isEqualTo("A teacher reviewed your portfolio");
				assertThat(row.get("title").toString() + row.get("body") + row.get("data")).doesNotContain("NOTE-CANARY", "Abel").contains("/portfolio/items/" + id);
			}
		}
		// the student is not told about a review of somebody else's item
		assertThat(count(p.student2(), "PORTFOLIO_REVIEWED")).isZero();
	}

	@Test
	void approvalNotifiesEligibleGuardiansOncePerItem() throws Exception {
		Pf p = pf("not3");
		Persona noPush = this.personas.newUser("not3-nopush");
		UUID link = this.personas.linkGuardian(p.s1(), noPush.userId(), "ACTIVE", false);
		this.jdbc.update("update student_guardians set can_receive_notifications = false where id = ?", link);
		Persona noAcademics = this.personas.newUser("not3-noacademics");
		UUID link2 = this.personas.linkGuardian(p.s1(), noAcademics.userId(), "ACTIVE", false);
		this.jdbc.update("update student_guardians set can_view_academics = false where id = ?", link2);
		UUID id = approved(p, p.student1(), "once", "GUARDIANS");
		awaitNotifications(p.guardian1().userId(), "PORTFOLIO_NEW_ITEM", 1);
		awaitNotifications(p.student1().userId(), "PORTFOLIO_REVIEWED", 1);
		for (Persona none : List.of(noPush, noAcademics, p.restricted(), p.revoked(), p.pending(), p.guardian2(), p.otherGuardian(), p.student2(), p.lead())) {
			assertThat(count(none, "PORTFOLIO_NEW_ITEM")).as(none.name()).isZero();
		}
		Map<String, Object> row = rows(p.guardian1()).stream().filter((r) -> r.get("type_code").equals("PORTFOLIO_NEW_ITEM")).findFirst().orElseThrow();
		assertThat(row.get("source_type")).isEqualTo("portfolio_item");
		// archiving and bringing it back is not a new approval: still one
		post(p.item(id) + "/archive", p.lead(), body(), 200);
		post(p.item(id) + "/unarchive", p.lead(), body(), 200);
		// a second approved item is a second notification
		approved(p, p.student1(), "twice", "GUARDIANS");
		awaitNotifications(p.guardian1().userId(), "PORTFOLIO_NEW_ITEM", 2);
		assertThat(count(p.guardian1(), "PORTFOLIO_NEW_ITEM")).isEqualTo(2);
	}

	@Test
	void guardiansAreNotNotifiedWhenTheSettingIsOff() throws Exception {
		Pf p = pf("not4");
		setting(p.schoolId(), "portfolio.notify_guardians", false);
		approved(p, p.student1(), "silent", "GUARDIANS");
		awaitNotifications(p.student1().userId(), "PORTFOLIO_REVIEWED", 1);
		assertThat(count(p.guardian1(), "PORTFOLIO_NEW_ITEM")).isZero();
	}

	@Test
	void guardiansAreNotNotifiedWhenTheItemIsNotGuardianVisible() throws Exception {
		Pf p = pf("not5");
		approved(p, p.student1(), "teachers only", "STUDENT_TEACHER");
		awaitNotifications(p.student1().userId(), "PORTFOLIO_REVIEWED", 1);
		assertThat(count(p.guardian1(), "PORTFOLIO_NEW_ITEM")).isZero();
		cap(p, "STUDENT_TEACHER");
		approved(p, p.student1(), "capped", "GUARDIANS");
		awaitNotifications(p.student1().userId(), "PORTFOLIO_REVIEWED", 2);
		assertThat(count(p.guardian1(), "PORTFOLIO_NEW_ITEM")).isZero();
	}

	@Test
	void restrictedPendingAndNonNotifiedGuardiansGetNothing() throws Exception {
		Pf p = pf("not6");
		this.jdbc.update("update student_guardians set custody_restricted = true where guardian_id = ?", p.guardian1().userId());
		approved(p, p.student1(), "restricted", "GUARDIANS");
		awaitNotifications(p.student1().userId(), "PORTFOLIO_REVIEWED", 1);
		for (Persona none : List.of(p.guardian1(), p.restricted(), p.pending(), p.revoked())) {
			assertThat(count(none, "PORTFOLIO_NEW_ITEM")).as(none.name()).isZero();
		}
	}

	@Test
	void notificationsNeverContainTitleReflectionOrNames() throws Exception {
		Pf p = pf("not7");
		cap(p, "CLASS");
		grant(p.guardian1(), p.s1());
		UUID media = photo(p.student1(), p.schoolId());
		UUID id = id(create(p.student1(), p, photoBody("TITLE-CANARY-7", List.of(media), "mode", "SUBMIT", "visibility", "CLASS",
				"description", "DESC-CANARY-7", "reflection", "REFLECTION-CANARY-7", "confirmNoUnconsentedStudents", true), 201));
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE", "note", "NOTE-CANARY-7"), 200);
		post(p.item(id) + "/comments", p.guardian1(), body("body", "COMMENT-CANARY-7"), 201);
		post(p.item(id) + "/comments", p.lead(), body("body", "COMMENT-CANARY-8"), 201);
		awaitNotifications(p.guardian1().userId(), "PORTFOLIO_NEW_ITEM", 1);
		awaitNotifications(p.student1().userId(), "PORTFOLIO_COMMENT", 2);
		awaitNotifications(p.lead().userId(), "PORTFOLIO_SUBMITTED", 1);
		for (Persona who : List.of(p.student1(), p.guardian1(), p.lead(), p.co())) {
			for (Map<String, Object> row : rows(who)) {
				String text = row.get("title") + " " + row.get("body") + " " + row.get("data");
				assertThat(text).as(who.name() + " " + row.get("type_code")).doesNotContain("CANARY", "Abel", "Bethlehem", "Chala");
			}
		}
	}

	@Test
	void listenersAreIdempotentUnderRedelivery() throws Exception {
		Pf p = pf("not8");
		UUID id = id(create(p.student1(), p, textBody("redelivered", "x", "mode", "SUBMIT"), 201));
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		UUID comment = id(post(p.item(id) + "/comments", p.guardian1(), body("body", "hello"), 201));
		awaitNotifications(p.guardian1().userId(), "PORTFOLIO_NEW_ITEM", 1);
		awaitNotifications(p.student1().userId(), "PORTFOLIO_COMMENT", 1);
		awaitNotifications(p.lead().userId(), "PORTFOLIO_SUBMITTED", 1);
		UUID submission = this.jdbc.queryForObject("select id from portfolio_approvals where item_id = ? and action = 'SUBMITTED'", UUID.class, id);
		UUID approval = this.jdbc.queryForObject("select id from portfolio_approvals where item_id = ? and action = 'APPROVED'", UUID.class, id);
		int before = completedEvents();
		this.tx.executeWithoutResult((s) -> {
			for (int i = 0; i < 2; i++) {
				this.publisher.publishEvent(new PortfolioItemSubmitted(id, p.schoolId(), submission));
				this.publisher.publishEvent(new PortfolioItemReviewed(id, p.schoolId(), p.s1(), approval, "APPROVED", false));
				this.publisher.publishEvent(new PortfolioCommentPosted(comment, id, p.schoolId(), p.guardian1().userId()));
			}
		});
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(completedEvents()).isGreaterThanOrEqualTo(before + 6));
		assertThat(count(p.guardian1(), "PORTFOLIO_NEW_ITEM")).isEqualTo(1);
		assertThat(count(p.student1(), "PORTFOLIO_REVIEWED")).isEqualTo(1);
		assertThat(count(p.student1(), "PORTFOLIO_COMMENT")).isEqualTo(1);
		assertThat(count(p.lead(), "PORTFOLIO_SUBMITTED")).isEqualTo(1);
		assertThat(count(p.co(), "PORTFOLIO_SUBMITTED")).isEqualTo(1);
	}

	private int completedEvents() {
		return this.jdbc.queryForObject("""
				select count(*) from event_publication where completion_date is not null and event_type like 'com.nemaz.school.portfolio.%'""",
				Integer.class);
	}

	@Test
	void commentNotifiesTheStudent() throws Exception {
		Pf p = pf("not9");
		UUID id = approved(p, p.student1(), "comments", "GUARDIANS");
		post(p.item(id) + "/comments", p.guardian1(), body("body", "COMMENT-TEXT-CANARY"), 201);
		awaitNotifications(p.student1().userId(), "PORTFOLIO_COMMENT", 1);
		post(p.item(id) + "/comments", p.student1(), body("body", "my reply"), 201);
		post(p.item(id) + "/comments", p.lead(), body("body", "teacher comment"), 201);
		awaitNotifications(p.student1().userId(), "PORTFOLIO_COMMENT", 2);
		assertThat(count(p.guardian1(), "PORTFOLIO_COMMENT")).isZero();
		assertThat(count(p.lead(), "PORTFOLIO_COMMENT")).isZero();
		List<String> texts = new ArrayList<>();
		rows(p.student1()).forEach((r) -> texts.add(r.get("title") + " " + r.get("body")));
		assertThat(String.join("|", texts)).doesNotContain("CANARY", "my reply");
		JsonNode none = get(p.api() + "/portfolio/capabilities", p.student1());
		assertThat(none.get("studentMayCreate").asBoolean()).isTrue();
	}

}

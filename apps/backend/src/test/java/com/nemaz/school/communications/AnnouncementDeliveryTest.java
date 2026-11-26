package com.nemaz.school.communications;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.notifications.domain.PushSender.PushResult;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Delivery in chunks (resumable, idempotent, safe with several workers), delivery versus reading versus acknowledgement,
 * push, and the quiet-hours policy of announcements.
 */
@IntegrationTest
class AnnouncementDeliveryTest extends CommunicationsTestSupport {

	/** A published announcement with {@code n} bulk guardians as recipients, written directly so no listener runs. */
	private UUID bulkAnnouncement(int n) {
		UUID school = this.personas.newSchool("bulk");
		Persona admin = this.personas.schoolAdmin(school, "bulk-admin");
		UUID classId = this.personas.newClass(school, "Bulk");
		this.jdbc.update("""
				with su as (insert into users (id, account_type, given_name, display_name, status)
				              select uuidv7(), 'MANAGED', 'S', 'Stu ' || g, 'ACTIVE' from generate_series(1, ?) g returning id),
				     gu as (insert into users (id, given_name, display_name, email, status)
				              select uuidv7(), 'G', 'Guardian ' || g, 'bulk-' || uuidv7() || '@example.org', 'ACTIVE'
				              from generate_series(1, ?) g returning id),
				     sp as (insert into student_profiles (user_id) select id from su),
				     gp as (insert into guardian_profiles (user_id) select id from gu),
				     ss as (insert into school_students (id, school_id, student_id, status, admission_on)
				              select uuidv7(), ?, id, 'ACTIVE', current_date from su),
				     cs as (insert into class_students (id, class_id, school_id, student_id, status)
				              select uuidv7(), ?, ?, id, 'ACTIVE' from su)
				insert into student_guardians (id, student_id, guardian_id, relationship_type, status, requested_via,
				                               verification_method, verified_at)
				select uuidv7(), a.id, b.id, 'MOTHER', 'ACTIVE', 'ADMIN', 'SCHOOL_VERIFIED', now()
				from (select id, row_number() over () as rn from su) a
				join (select id, row_number() over () as rn from gu) b on a.rn = b.rn""", n, n, school, classId, school);
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into announcements (id, school_id, author_id, title, body, status, published_at, recipient_count)
				values (?, ?, ?, 'Bulk news', 'Bulk body', 'PUBLISHED', now() - interval '1 hour', ?)""", id, school,
				admin.userId(), n);
		this.jdbc.update("""
				insert into announcement_audiences (announcement_id, audience_type, class_id, recipient_roles)
				values (?, 'CLASS', ?, '{GUARDIAN}')""", id, classId);
		this.jdbc.update("""
				insert into announcement_recipients (announcement_id, school_id, user_id, recipient_role)
				select ?, ?, sg.guardian_id, 'GUARDIAN' from student_guardians sg
				join class_students cs on cs.student_id = sg.student_id where cs.class_id = ?""", id, school, classId);
		return id;
	}

	private int notifiedCount(UUID id) {
		return this.jdbc.queryForObject("select count(*) from announcement_recipients where announcement_id = ? and notified_at is not null",
				Integer.class, id);
	}

	private int notificationCount(UUID id) {
		return this.jdbc.queryForObject("""
				select count(*) from notifications where source_type = 'ANNOUNCEMENT' and source_id = ?
				  and type_code = 'ANNOUNCEMENT_PUBLISHED'""", Integer.class, id);
	}

	@Test
	void aLargeAudienceIsDeliveredInChunksAndAnInterruptedRunResumes() throws Exception {
		int n = 1250;
		UUID id = bulkAnnouncement(n);
		assertThat(notifiedCount(id)).isZero();
		// an earlier run got through its first 500 recipients before it died (their notifications exist already)
		this.jdbc.update("""
				update announcement_recipients set notified_at = now() where id in (
				  select id from announcement_recipients where announcement_id = ? order by id limit 500)""", id);
		// two workers at once (the redelivered event and the resume job): each recipient is notified exactly once
		CountDownLatch start = new CountDownLatch(1);
		CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
			awaitLatch(start);
			return this.delivery.deliver(id);
		});
		CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> {
			awaitLatch(start);
			return this.delivery.deliver(id);
		});
		start.countDown();
		int delivered = first.get().intValue() + second.get().intValue();
		assertThat(delivered).isEqualTo(n - 500);
		assertThat(notificationCount(id)).isEqualTo(n - 500);
		assertThat(notifiedCount(id)).isEqualTo(n);
		assertThat(this.jdbc.queryForObject("select delivery_completed_at is not null from announcements where id = ?", Boolean.class, id))
			.isTrue();
		assertThat(this.jdbc.queryForObject("""
				select count(*) from announcement_recipients where announcement_id = ? and notification_id is not null""",
				Integer.class, id)).isEqualTo(n - 500);
		// running it again, or the resume job, adds nothing
		assertThat(this.delivery.deliver(id)).isZero();
		this.maintenance.resumeDeliveries();
		assertThat(notificationCount(id)).isEqualTo(n - 500);
	}

	@Test
	void theResumeJobFinishesDeliveriesThatWereNeverStarted() throws Exception {
		UUID id = bulkAnnouncement(30);
		// published an hour ago, nothing delivered: the job picks it up
		assertThat(this.maintenance.resumeDeliveries()).isGreaterThanOrEqualTo(1);
		assertThat(notifiedCount(id)).isEqualTo(30);
		assertThat(notificationCount(id)).isEqualTo(30);
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void deliveryReadingAndAcknowledgementAreTrackedApart() throws Exception {
		Comms c = comms("deliv-ack");
		UUID id = UUID.fromString(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("Sign the form", "Please sign.",
				classAudience(c.class1A())), "mode", "PUBLISH", "type", "IMPORTANT", "requiresAcknowledgement", true), 201)
			.get("id")
			.asString());
		awaitDelivered(id);
		String url = c.api() + "/" + id;
		JsonNode report = get(url + "/report", c.admin());
		assertThat(report.get("recipients").asInt()).isEqualTo(2);
		assertThat(report.get("delivered").asInt()).isEqualTo(2);
		assertThat(report.get("read").asInt()).isZero();
		assertThat(report.get("acknowledged").asInt()).isZero();
		assertThat(report.get("deliveryComplete").asBoolean()).isTrue();
		// the notification asks for the acknowledgement
		assertThat(inboxOf(c.gA1().userId(), "ACK_REQUESTED")).hasSize(1);
		assertThat(inboxOf(c.gA1().userId(), "ANNOUNCEMENT_PUBLISHED")).isEmpty();
		JsonNode summary = get("/api/v1/me/announcements/summary", c.gA1());
		assertThat(summary.get("unread").asInt()).isEqualTo(1);
		assertThat(summary.get("needingAcknowledgement").asInt()).isEqualTo(1);

		// reading is not acknowledging
		call(HttpMethod.POST, url + "/read", c.gA1(), null, 204);
		call(HttpMethod.POST, url + "/read", c.gA1(), null, 204);
		report = get(url + "/report", c.admin());
		assertThat(report.get("read").asInt()).isEqualTo(1);
		assertThat(report.get("acknowledged").asInt()).isZero();
		summary = get("/api/v1/me/announcements/summary", c.gA1());
		assertThat(summary.get("unread").asInt()).isZero();
		assertThat(summary.get("needingAcknowledgement").asInt()).isEqualTo(1);
		// acknowledging is idempotent and keeps the first moment
		JsonNode first = call(HttpMethod.POST, url + "/acknowledge", c.gA1(), null, 200);
		String at = first.get("mine").get("acknowledgedAt").asString();
		assertThat(first.get("mine").get("acknowledgedRevision").asInt()).isEqualTo(1);
		JsonNode again = call(HttpMethod.POST, url + "/acknowledge", c.gA1(), null, 200);
		assertThat(again.get("mine").get("acknowledgedAt").asString()).isEqualTo(at);
		report = get(url + "/report", c.admin());
		assertThat(report.get("read").asInt()).isEqualTo(1);
		assertThat(report.get("acknowledged").asInt()).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select count(*) from announcement_acknowledgements where announcement_id = ?", Integer.class, id))
			.isEqualTo(1);
		// acknowledging also marks it read
		call(HttpMethod.POST, url + "/acknowledge", c.gSib(), null, 200);
		assertThat(get(url + "/report", c.admin()).get("read").asInt()).isEqualTo(2);
		// an announcement that asks for nothing cannot be acknowledged
		UUID plain = publishNow(c, c.admin(), "Plain", classAudience(c.class1A()));
		assertThat(code(call(HttpMethod.POST, c.api() + "/" + plain + "/acknowledge", c.gA1(), null, 409))).isEqualTo("announcement.ack_not_required");
		// people who may not read it record nothing
		call(HttpMethod.POST, url + "/read", c.gB1(), null, 404);
		call(HttpMethod.POST, url + "/acknowledge", c.gRestricted(), null, 404);
		// a late joiner's reading is counted apart
		Persona late = this.personas.newUser("late");
		this.personas.linkGuardian(c.a2(), late.userId(), "ACTIVE", false);
		call(HttpMethod.POST, url + "/read", late, null, 204);
		report = get(url + "/report", c.admin());
		assertThat(report.get("lateReaders").asInt()).isEqualTo(1);
		assertThat(report.get("read").asInt()).isEqualTo(2);
		assertThat(report.get("recipients").asInt()).isEqualTo(2);
	}

	@Test
	void pushGoesThroughTheNotificationServiceAndAFailureIsReported() throws Exception {
		Comms c = comms("deliv-push");
		String ok = device(c.gA1());
		String bad = device(c.gSib());
		this.pushes.respondWith((m) -> m.token().equals(bad) ? PushResult.failed("provider said no") : PushResult.sent("ok"));
		UUID id = publishNow(c, c.admin(), "Open day", classAudience(c.class1A()));
		awaitDelivered(id);
		await().atMost(Duration.ofSeconds(10)).until(() -> !this.pushes.sentTo(ok).isEmpty() && !this.pushes.sentTo(bad).isEmpty());
		assertThat(this.pushes.sentTo(ok).getFirst().title()).isEqualTo("Open day");
		assertThat(this.pushes.sentTo(ok).getFirst().urgent()).isFalse();
		assertThat(this.pushes.sentTo(ok).getFirst().body()).doesNotContain("Body of");
		await().atMost(Duration.ofSeconds(10)).until(() -> get(c.api() + "/" + id + "/report", c.admin()).get("push").get("failed").asInt() == 1);
		JsonNode report = get(c.api() + "/" + id + "/report", c.admin());
		assertThat(report.get("push").get("sent").asInt()).isEqualTo(1);
		assertThat(report.get("failed").asInt()).isEqualTo(1);
		JsonNode failed = get(c.api() + "/" + id + "/report/recipients?filter=FAILED", c.admin());
		assertThat(failed.get("items").size()).isEqualTo(1);
		assertThat(failed.get("items").get(0).get("userId").asString()).isEqualTo(c.gSib().userId().toString());
		assertThat(failed.get("items").get(0).get("push").asString()).isEqualTo("FAILED");
		// delivered (in-app) is separate from the push that failed
		assertThat(absent(failed.get("items").get(0), "notifiedAt")).isFalse();
	}

	@Test
	void normalAnnouncementsWaitForTheEndOfQuietHoursButUrgentOnesDoNot() throws Exception {
		Comms c = comms("deliv-quiet");
		String quietDevice = device(c.gA1());
		String awakeDevice = device(c.gSib());
		quietNow(c.gA1());
		UUID id = publishNow(c, c.admin(), "Normal news", classAudience(c.class1A()));
		awaitDelivered(id);
		// in-app is immediate for both; the push of the sleeper is held, the other goes out
		assertThat(inboxOf(c.gA1().userId(), "ANNOUNCEMENT_PUBLISHED")).hasSize(1);
		await().atMost(Duration.ofSeconds(10)).until(() -> !this.pushes.sentTo(awakeDevice).isEmpty());
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.queryForObject("""
				select count(*) from notification_deliveries d join notifications n on n.id = d.notification_id
				where n.source_id = ? and n.user_id = ? and d.status = 'QUEUED' and d.not_before is not null""", Integer.class, id,
				c.gA1().userId()) == 1);
		assertThat(this.pushes.sentTo(quietDevice)).isEmpty();
		JsonNode report = get(c.api() + "/" + id + "/report", c.admin());
		assertThat(report.get("push").get("pending").asInt()).isEqualTo(1);
		assertThat(report.get("delivered").asInt()).isEqualTo(2);
		// the job does not send it early ...
		this.retryJob.retryDue();
		assertThat(this.pushes.sentTo(quietDevice)).isEmpty();
		// ... and sends it once the quiet hours are over
		this.clock.advance(Duration.ofHours(4));
		this.retryJob.retryDue();
		await().atMost(Duration.ofSeconds(10)).until(() -> !this.pushes.sentTo(quietDevice).isEmpty());
		assertThat(this.pushes.sentTo(quietDevice)).hasSize(1);
		this.retryJob.retryDue();
		assertThat(this.pushes.sentTo(quietDevice)).hasSize(1);
		this.clock.reset();

		// urgent and safety announcements ring through quiet hours, and even through a muted type
		quietNow(c.gA1());
		this.jdbc.update("insert into notification_preferences (user_id, type_code, channel, enabled) values (?, 'ANNOUNCEMENT_PUBLISHED', 'PUSH', false)",
				c.gA1().userId());
		this.pushes.reset();
		UUID urgent = UUID.fromString(call(HttpMethod.POST, c.api(), c.admin(),
				with(announcement("Fire drill now", "Go outside.", classAudience(c.class1A())), "mode", "PUBLISH", "type", "SAFETY"), 201)
			.get("id")
			.asString());
		awaitDelivered(urgent);
		await().atMost(Duration.ofSeconds(10)).until(() -> !this.pushes.sentTo(quietDevice).isEmpty());
		assertThat(this.pushes.sentTo(quietDevice).getFirst().urgent()).isTrue();
		// a normal one is dropped by the same muted preference
		this.pushes.reset();
		quietHoursOff(c.gA1());
		UUID muted = publishNow(c, c.admin(), "Muted news", classAudience(c.class1A()));
		awaitDelivered(muted);
		await().atMost(Duration.ofSeconds(10)).until(() -> !this.pushes.sentTo(awakeDevice).isEmpty());
		assertThat(this.pushes.sentTo(quietDevice)).isEmpty();
		assertThat(this.jdbc.queryForObject("""
				select d.suppressed_reason from notification_deliveries d join notifications n on n.id = d.notification_id
				where n.source_id = ? and n.user_id = ?""", String.class, muted, c.gA1().userId())).isEqualTo("PREFERENCE");
	}

	@Test
	void withdrawingCancelsPushesThatWereHeldBack() throws Exception {
		Comms c = comms("deliv-withdraw-held");
		String sleeper = device(c.gA1());
		quietNow(c.gA1());
		UUID id = publishNow(c, c.admin(), "Held news", classAudience(c.class1A()));
		awaitDelivered(id);
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.queryForObject("""
				select count(*) from notification_deliveries d join notifications n on n.id = d.notification_id
				where n.source_id = ? and n.user_id = ? and d.status = 'QUEUED'""", Integer.class, id, c.gA1().userId()) == 1);
		call(HttpMethod.POST, c.api() + "/" + id + "/withdraw", c.admin(), Map.of("reason", "Sent by mistake"), 200);
		this.clock.advance(Duration.ofHours(4));
		this.retryJob.retryDue();
		assertThat(this.pushes.sentTo(sleeper)).isEmpty();
		assertThat(this.jdbc.queryForObject("""
				select d.suppressed_reason from notification_deliveries d join notifications n on n.id = d.notification_id
				where n.source_id = ? and n.user_id = ? and n.type_code = 'ANNOUNCEMENT_PUBLISHED'""", String.class, id, c.gA1().userId()))
			.isEqualTo("BLOCKED");
	}

	@Test
	void deliveryNeverNamesTheChildOrQuotesTheText() throws Exception {
		Comms c = comms("deliv-privacy");
		device(c.gA1());
		UUID id = publishNow(c, c.admin(), "Class news", classAudience(c.class1A()));
		awaitDelivered(id);
		await().atMost(Duration.ofSeconds(10)).until(() -> !this.pushes.sent().isEmpty());
		this.pushes.sent().forEach((m) -> {
			assertThat(m.title() + " " + m.body() + " " + m.data()).doesNotContain("Abel").doesNotContain("Body of");
		});
		assertThat(this.jdbc.queryForObject("select count(*) from announcement_recipients where announcement_id = ? and notified_at < ?",
				Integer.class, id, Timestamp.from(this.clock.instant().minus(Duration.ofDays(1))))).isZero();
		assertThat(List.of(c.gA1().userId(), c.gSib().userId())).allSatisfy((u) -> assertThat(inboxOf(u, "ANNOUNCEMENT_PUBLISHED")).hasSize(1));
	}

}

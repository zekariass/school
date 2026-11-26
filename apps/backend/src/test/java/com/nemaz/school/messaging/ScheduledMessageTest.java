package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.messaging.application.MessagingMaintenance;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R8.5: scheduled messages. */
@IntegrationTest
class ScheduledMessageTest extends MessagingTestSupport {

	@Autowired
	MessagingMaintenance maintenance;

	private UUID schedule(Persona who, UUID thread, String text, Duration ahead) throws Exception {
		JsonNode sent = post(conv(thread) + "/messages", who,
				body("clientMessageId", UUID.randomUUID(), "body", text, "sendAt", this.clock.instant().plus(ahead)), 201);
		assertThat(sent.get("message").get("status").asString()).isEqualTo("SCHEDULED");
		return UUID.fromString(sent.get("message").get("id").asString());
	}

	@Test
	void dueMessagesAreSentOnceAndRecheckThePolicy() throws Exception {
		Msg m = school("sched-send");
		UUID thread = parentTeacherThread(m);
		String token = device(m.g1());
		send(m.lead(), thread, "A before");
		UUID scheduled = schedule(m.lead(), thread, "S scheduled for later", Duration.ofMinutes(5));
		send(m.lead(), thread, "B after");
		awaitPushes(token, 1);
		// invisible to the other side, to unread counts and to notifications until it is sent; the sender sees it in their own list
		assertThat(bodies(messagesOf(m.g1(), thread))).containsExactly("B after", "A before");
		assertThat(statusOf(HttpMethod.GET, conv(thread) + "/messages/" + scheduled, m.g1(), null)).isEqualTo(404);
		assertThat(bodies(messagesOf(m.lead(), thread))).containsExactly("B after", "A before");
		JsonNode mine = get("/api/v1/me/scheduled-messages", m.lead());
		assertThat(mine).hasSize(1);
		assertThat(mine.get(0).get("id").asString()).isEqualTo(scheduled.toString());
		assertThat(mine.get(0).get("scheduledFor").isNull()).isFalse();
		// the sender sees their own text (to know what they scheduled); nobody else can see the message at all
		assertThat(mine.get(0).get("body").asString()).isEqualTo("S scheduled for later");
		assertThat(get("/api/v1/me/scheduled-messages", m.g1())).isEmpty();
		// not due yet: nothing happens
		assertThat(this.maintenance.sendScheduled()).isZero();
		// due: sent once, in order after the messages that were sent meanwhile
		this.clock.advance(Duration.ofMinutes(6));
		assertThat(this.maintenance.sendScheduled()).isEqualTo(1);
		assertThat(this.maintenance.sendScheduled()).isZero();
		assertThat(bodies(messagesOf(m.g1(), thread))).containsExactly("S scheduled for later", "B after", "A before");
		assertThat(get("/api/v1/me/scheduled-messages", m.lead())).isEmpty();
		assertThat(get(conv(thread) + "/messages/" + scheduled, m.lead()).get("status").asString()).isEqualTo("SENT");
		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
				this.jdbc.queryForObject("select notified_at is not null from messages where id = ?", Boolean.class, scheduled)).isTrue());
		// R1 and R5 are checked again at send time: the link was restricted before the message was due
		UUID second = schedule(m.lead(), thread, "never delivered", Duration.ofMinutes(5));
		restrict(m.g1Link());
		this.clock.advance(Duration.ofMinutes(6));
		assertThat(this.maintenance.sendScheduled()).isZero();
		assertThat(this.jdbc.queryForObject("select status from messages where id = ?", String.class, second)).isEqualTo("BLOCKED");
		awaitNotification(m.lead(), "MESSAGE_SCHEDULE_BLOCKED");
		assertThat(notificationsOf(m.lead(), "MESSAGE_SCHEDULE_BLOCKED").getFirst().get("body").toString()).doesNotContain("never delivered");
		unrestrict(m.g1Link());
		assertThat(bodies(messagesOf(m.g1(), thread))).doesNotContain("never delivered");
		assertThat(this.maintenance.sendScheduled()).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'messaging.message.schedule_blocked' and target_id = ?",
				Integer.class, second)).isEqualTo(1);
	}

	@Test
	void adultsOnly() throws Exception {
		Msg m = school("sched-adults");
		UUID classThread = classConversation(m);
		Instant later = this.clock.instant().plus(Duration.ofHours(1));
		assertThat(code(post(conv(classThread) + "/messages", m.student1(),
				body("clientMessageId", UUID.randomUUID(), "body", "later", "sendAt", later), 403))).isEqualTo("messaging.scheduling_not_allowed");
		// too soon, in the past or too far ahead
		for (Instant bad : new Instant[] { this.clock.instant().minusSeconds(5), this.clock.instant().plusSeconds(10),
				this.clock.instant().plus(Duration.ofDays(91)) }) {
			assertThat(code(post(conv(classThread) + "/messages", m.lead(),
					body("clientMessageId", UUID.randomUUID(), "body", "x", "sendAt", bad), 400))).isEqualTo("messaging.schedule_invalid");
		}
		schedule(m.lead(), classThread, "teachers can", Duration.ofHours(1));
	}

	@Test
	void scheduledMessageIsInvisibleUntilSent() throws Exception {
		Msg m = school("sched-invisible");
		UUID thread = teacherStudentThread(m);
		UUID scheduled = schedule(m.lead(), thread, "see you tomorrow", Duration.ofHours(1));
		for (Persona who : new Persona[] { m.student1(), m.g1() }) {
			assertThat(bodies(messagesOf(who, thread))).isEmpty();
			assertThat(statusOf(HttpMethod.GET, conv(thread) + "/messages/" + scheduled, who, null)).isEqualTo(404);
			assertThat(unread(who, thread)).isZero();
		}
		assertThat(inbox(m.student1()).get("items").get(0).has("lastMessage")).isFalse();
		assertThat(notificationsOf(m.student1(), "NEW_MESSAGE")).isEmpty();
		// the sender can withdraw it
		call(HttpMethod.DELETE, conv(thread) + "/messages/" + scheduled, m.lead(), null, 204);
		assertThat(get("/api/v1/me/scheduled-messages", m.lead())).isEmpty();
		this.clock.advance(Duration.ofHours(2));
		assertThat(this.maintenance.sendScheduled()).isZero();
		assertThat(bodies(messagesOf(m.student1(), thread))).isEmpty();
		// a scheduled message is released only while the safeguarding rules still allow it
		UUID second = schedule(m.lead(), thread, "needs the setting", Duration.ofMinutes(5));
		setting(m.schoolId(), "messaging.student.teacher_direct.enabled", false);
		this.clock.advance(Duration.ofMinutes(6));
		assertThat(this.maintenance.sendScheduled()).isZero();
		assertThat(this.jdbc.queryForObject("select status from messages where id = ?", String.class, second)).isEqualTo("BLOCKED");
	}

	@Test
	void blockedScheduleNotifiesTheSender() throws Exception {
		Msg m = school("sched-blocked");
		UUID thread = parentTeacherThread(m);
		UUID closed = schedule(m.lead(), thread, "closed meanwhile", Duration.ofMinutes(5));
		this.jdbc.update("update class_teachers set status = 'FORMER', left_at = now() where teacher_id = ? and class_id = ?", m.lead().userId(), m.classId());
		this.clock.advance(Duration.ofMinutes(6));
		assertThat(this.maintenance.sendScheduled()).isZero();
		assertThat(this.jdbc.queryForObject("select blocked_code from messages where id = ?", String.class, closed)).isNotNull();
		awaitNotification(m.lead(), "MESSAGE_SCHEDULE_BLOCKED");
		assertThat(notificationsOf(m.g1(), "NEW_MESSAGE")).isEmpty();
	}

	@Test
	void jobIsIdempotent() throws Exception {
		Msg m = school("sched-idem");
		UUID thread = parentTeacherThread(m);
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			ids.add(schedule(m.lead(), thread, "m" + i, Duration.ofMinutes(5)));
		}
		this.clock.advance(Duration.ofMinutes(6));
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				calls.add(() -> this.maintenance.sendScheduled());
			}
			int sent = 0;
			for (Future<Integer> f : pool.invokeAll(calls)) {
				sent += f.get();
			}
			assertThat(sent).isEqualTo(6);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(this.jdbc.queryForObject("select count(*) from messages where conversation_id = ? and status = 'SENT'", Integer.class, thread)).isEqualTo(6);
		assertThat(this.jdbc.queryForObject("select count(distinct seq) from messages where conversation_id = ? and seq is not null", Integer.class, thread)).isEqualTo(6);
		assertThat(this.maintenance.sendScheduled()).isZero();
		// and one notification for the whole burst of messages
		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(notificationsOf(m.g1(), "NEW_MESSAGE")).hasSize(1));
		assertThat(ids).hasSize(6);
	}

}

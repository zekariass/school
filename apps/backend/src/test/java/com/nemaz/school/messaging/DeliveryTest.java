package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.messaging.application.MessageService;
import com.nemaz.school.realtime.RealtimePublisher;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R9: delivery by doorbell, push and notification, and office hours. */
@IntegrationTest
class DeliveryTest extends MessagingTestSupport {

	@LocalServerPort
	int port;

	@Autowired
	RealtimePublisher publisher;

	@Autowired
	ApplicationEventPublisher events;

	private DoorbellClient connect(Persona who) throws Exception {
		DoorbellClient client = DoorbellClient.connect(this.port, DoorbellClient.origin(null), DoorbellClient.bearer(who.accessToken()));
		client.subscribe("/user/queue/messages", who.userId(), this.publisher, RealtimePublisher.MESSAGES_QUEUE);
		return client;
	}

	private void awaitDelivered(UUID message) {
		await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> assertThat(
				this.jdbc.queryForObject("select notified_at is not null from messages where id = ?", Boolean.class, message)).isTrue());
	}

	private void hoursAround(Msg m, boolean inside) {
		// every day; "inside" is all day, "outside" is one minute that is (almost) never now
		Map<String, Object> hours = inside ? Map.of("days", List.of(1, 2, 3, 4, 5, 6, 7), "start", "00:00", "end", "23:59")
				: Map.of("days", List.of(1, 2, 3, 4, 5, 6, 7), "start", "04:00", "end", "04:01");
		setting(m.schoolId(), "messaging.teacher_hours", hours);
	}

	@Test
	void membersGetARealtimeDoorbellWithIdsOnly() throws Exception {
		Msg m = school("deliv-bell");
		UUID thread = teacherStudentThread(m);
		try (DoorbellClient student = connect(m.student1()); DoorbellClient observer = connect(m.g1());
				DoorbellClient outsider = connect(m.g2()); DoorbellClient other = connect(m.restricted())) {
			UUID message = send(m.lead(), thread, "CANARY-BELL text for Abel");
			String toStudent = student.nextMessage(10);
			String toObserver = observer.nextMessage(10);
			assertThat(toStudent).isNotNull().contains("message.created").contains(thread.toString()).contains(message.toString());
			assertThat(toObserver).isNotNull().contains(message.toString());
			for (String payload : new String[] { toStudent, toObserver }) {
				JsonNode ring = this.json.readTree(payload);
				assertThat(ring.propertyNames()).containsExactlyInAnyOrder("type", "conversationId", "messageId", "at");
				assertThat(payload).doesNotContain("CANARY").doesNotContain("Abel").doesNotContain("deliv-bell");
			}
			// nobody else hears it
			assertThat(outsider.nextMessage(2)).isNull();
			assertThat(other.nextMessage(1)).isNull();
			// a guardian who is restricted while connected hears nothing more
			restrict(m.g1Link());
			send(m.lead(), thread, "second");
			assertThat(student.nextMessage(10)).contains("message.created");
			assertThat(observer.nextMessage(2)).isNull();
		}
	}

	@Test
	void noDoorbellForPeopleWhoLostAccess() throws Exception {
		Msg m = school("deliv-lost");
		UUID thread = parentTeacherThread(m);
		try (DoorbellClient guardian = connect(m.g1())) {
			send(m.lead(), thread, "hello");
			assertThat(guardian.nextMessage(10)).contains("message.created");
			this.jdbc.update("update student_guardians set can_message_staff = false where id = ?", m.g1Link());
			// the thread is dead for both: a message cannot even be sent, and the guardian is not rung
			assertThat(statusOf(HttpMethod.POST, conv(thread) + "/messages", m.lead(), body("clientMessageId", UUID.randomUUID(), "body", "x")))
				.isEqualTo(404);
			assertThat(guardian.nextMessage(2)).isNull();
		}
	}

	@Test
	void pushRespectsQuietHoursMuteAndReading() throws Exception {
		Msg m = school("deliv-push");
		UUID thread = parentTeacherThread(m);
		String token = device(m.g1());
		UUID first = send(m.lead(), thread, "CANARY-PUSH one");
		awaitPushes(token, 1);
		assertThat(this.pushes.sentTo(token).getFirst().title()).isEqualTo("New message");
		assertThat(this.pushes.sentTo(token).getFirst().data().get("path").toString()).startsWith("/messages/");
		assertThat(this.pushes.sentTo(token).getFirst().toString()).doesNotContain("CANARY").doesNotContain("Abel");
		// a second message does not ring again while the first one is unread
		UUID second = send(m.lead(), thread, "two");
		awaitDelivered(second);
		assertThat(notificationsOf(m.g1(), "NEW_MESSAGE")).hasSize(1);
		assertThat(this.pushes.sentTo(token)).hasSize(1);
		// opening the conversation reads the notification; a message while she is reading rings nothing
		call(HttpMethod.POST, conv(thread) + "/read", m.g1(), null, 204);
		assertThat(notificationsOf(m.g1(), "NEW_MESSAGE").getFirst().get("read_at")).isNotNull();
		UUID third = send(m.lead(), thread, "three");
		awaitDelivered(third);
		assertThat(notificationsOf(m.g1(), "NEW_MESSAGE")).hasSize(1);
		// half a minute later she is not reading any more
		this.clock.advance(Duration.ofSeconds(31));
		UUID fourth = send(m.lead(), thread, "four");
		awaitDelivered(fourth);
		assertThat(notificationsOf(m.g1(), "NEW_MESSAGE")).hasSize(2);
		awaitPushes(token, 2);
		// a muted conversation gets no notification at all
		call(HttpMethod.POST, conv(thread) + "/read", m.g1(), null, 204);
		call(HttpMethod.PUT, conv(thread) + "/mute", m.g1(), body("until", this.clock.instant().plus(Duration.ofDays(1))), 200);
		this.clock.advance(Duration.ofSeconds(31));
		UUID fifth = send(m.lead(), thread, "five");
		awaitDelivered(fifth);
		assertThat(notificationsOf(m.g1(), "NEW_MESSAGE")).hasSize(2);
		assertThat(get(conv(thread), m.g1()).get("summary").get("muted").asBoolean()).isTrue();
		call(HttpMethod.DELETE, conv(thread) + "/mute", m.g1(), null, 204);
		assertThat(get(conv(thread), m.g1()).get("summary").get("muted").asBoolean()).isFalse();
		assertThat(first).isNotEqualTo(second);
		// the sender is never notified of their own message
		assertThat(notificationsOf(m.lead(), "NEW_MESSAGE")).isEmpty();
	}

	@Test
	void quietHoursHoldBackThePushButNotTheMessage() throws Exception {
		Msg m = school("deliv-quiet");
		UUID thread = parentTeacherThread(m);
		String token = device(m.g1());
		quietNow(m.g1());
		UUID message = send(m.lead(), thread, "evening message");
		awaitDelivered(message);
		// in the app at once, the push waits for the end of the recipient's quiet hours
		assertThat(notificationsOf(m.g1(), "NEW_MESSAGE")).hasSize(1);
		assertThat(this.pushes.sentTo(token)).isEmpty();
		assertThat(this.jdbc.queryForObject("""
				select count(*) from notification_deliveries d join notifications n on n.id = d.notification_id
				where n.user_id = ? and n.type_code = 'NEW_MESSAGE' and d.status = 'QUEUED' and d.not_before is not null""",
				Integer.class, m.g1().userId())).isEqualTo(1);
		assertThat(bodies(messagesOf(m.g1(), thread))).containsExactly("evening message");
	}

	@Test
	void notificationsNeverContainTheTextOrNames() throws Exception {
		Msg m = school("deliv-names");
		UUID thread = teacherStudentThread(m);
		UUID parents = parentGroup(m);
		UUID classThread = classConversation(m);
		String teacherToken = device(m.g1());
		String studentToken = device(m.student1());
		UUID a = send(m.lead(), thread, "CANARY-TEXT-1 Abel Bethlehem");
		UUID b = send(m.g2(), parents, "CANARY-TEXT-2 from the parent of Bethlehem");
		UUID c = send(m.student2(), classThread, "CANARY-TEXT-3 homework");
		awaitDelivered(a);
		awaitDelivered(b);
		awaitDelivered(c);
		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(this.pushes.sentTo(teacherToken)).isNotEmpty());
		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(this.pushes.sentTo(studentToken)).isNotEmpty());
		var rows = this.jdbc.queryForList("""
				select n.title, n.body, n.data::text as data, n.type_code from notifications n
				where n.school_id = ? and n.type_code in ('NEW_MESSAGE', 'NEW_MESSAGE_QUIET', 'MESSAGE_OBSERVER_ADDED')""", m.schoolId());
		assertThat(rows).isNotEmpty();
		for (var row : rows) {
			String all = row.toString();
			assertThat(all).doesNotContain("CANARY").doesNotContain("Abel").doesNotContain("Bethlehem").doesNotContain("deliv-names-lead")
				.doesNotContain("deliv-names-guardian").doesNotContain("deliv-names-admin");
			String data = (String) row.get("data");
			assertThat(data).contains("\"path\": \"/messages/");
		}
		for (var push : this.pushes.sent()) {
			assertThat(push.toString()).doesNotContain("CANARY").doesNotContain("Abel").doesNotContain("deliv-names");
		}
	}

	@Test
	void redeliveryNotifiesOnce() throws Exception {
		Msg m = school("deliv-redeliver");
		UUID thread = parentTeacherThread(m);
		UUID message = send(m.lead(), thread, "once");
		awaitDelivered(message);
		assertThat(notificationsOf(m.g1(), "NEW_MESSAGE")).hasSize(1);
		// the same event again, as the outbox redelivers after a crash
		call(HttpMethod.POST, conv(thread) + "/read", m.g1(), null, 204);
		this.clock.advance(Duration.ofSeconds(31));
		for (int i = 0; i < 2; i++) {
			this.tx.executeWithoutResult((s) -> this.events.publishEvent(new MessageSent(message, thread, m.schoolId(), m.lead().userId())));
		}
		Thread.sleep(1500);
		assertThat(notificationsOf(m.g1(), "NEW_MESSAGE")).hasSize(1);
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where user_id = ? and type_code = 'NEW_MESSAGE'", Integer.class,
				m.g1().userId())).isEqualTo(1);
	}

	@Test
	void outsideTeacherHoursIsSilentAndTheSenderIsTold() throws Exception {
		Msg m = school("deliv-hours");
		UUID thread = parentTeacherThread(m);
		String teacherToken = device(m.lead());
		String guardianToken = device(m.g1());
		hoursAround(m, false);
		JsonNode sent = send(m.g1(), thread, "after hours", 201);
		assertThat(sent.get("afterHours").asBoolean()).isTrue();
		UUID message = UUID.fromString(sent.get("message").get("id").asString());
		awaitDelivered(message);
		// the teacher has it in the app, without a push; it is stored and shown like any other message
		assertThat(notificationsOf(m.lead(), "NEW_MESSAGE_QUIET")).hasSize(1);
		assertThat(notificationsOf(m.lead(), "NEW_MESSAGE")).isEmpty();
		assertThat(this.pushes.sentTo(teacherToken)).isEmpty();
		assertThat(this.jdbc.queryForObject("select count(*) from notification_deliveries d join notifications n on n.id = d.notification_id where n.user_id = ?",
				Integer.class, m.lead().userId())).isZero();
		assertThat(bodies(messagesOf(m.lead(), thread))).containsExactly("after hours");
		// the teacher's own replies are not affected by office hours
		JsonNode reply = send(m.lead(), thread, "answer", 201);
		assertThat(reply.get("afterHours").asBoolean()).isFalse();
		awaitPushes(guardianToken, 1);
		assertThat(notificationsOf(m.g1(), "NEW_MESSAGE")).hasSize(1);
		// a repeat tells the sender the same thing
		JsonNode repeat = post(conv(thread) + "/messages", m.g1(),
				body("clientMessageId", UUID.fromString(this.jdbc.queryForObject("select client_message_id::text from messages where id = ?", String.class, message)),
						"body", "after hours"), 200);
		assertThat(repeat.get("afterHours").asBoolean()).isTrue();
	}

	@Test
	void insideTeacherHoursPushes() throws Exception {
		Msg m = school("deliv-inside");
		UUID thread = parentTeacherThread(m);
		String teacherToken = device(m.lead());
		hoursAround(m, true);
		JsonNode sent = send(m.g1(), thread, "office hours", 201);
		assertThat(sent.get("afterHours").asBoolean()).isFalse();
		awaitPushes(teacherToken, 1);
		assertThat(notificationsOf(m.lead(), "NEW_MESSAGE")).hasSize(1);
		assertThat(notificationsOf(m.lead(), "NEW_MESSAGE_QUIET")).isEmpty();
		// no office hours at all: always inside
		setting(m.schoolId(), "messaging.teacher_hours", null);
		assertThat(send(m.g1(), thread, "any time", 201).get("afterHours").asBoolean()).isFalse();
		assertThat(MessageService.REACTIONS).hasSize(12);
	}

	@Test
	void silentDeliveryHasNoPush() throws Exception {
		Msg m = school("deliv-silent");
		UUID parents = parentGroup(m);
		String teacherToken = device(m.lead());
		hoursAround(m, false);
		UUID message = send(m.g1(), parents, "question for the teachers");
		awaitDelivered(message);
		assertThat(notificationsOf(m.lead(), "NEW_MESSAGE_QUIET")).hasSize(1);
		assertThat(this.pushes.sentTo(teacherToken)).isEmpty();
		// other guardians of the group are not office-hours bound
		assertThat(notificationsOf(m.g2(), "NEW_MESSAGE")).hasSize(1);
	}

}

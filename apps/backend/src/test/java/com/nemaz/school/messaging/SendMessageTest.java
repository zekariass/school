package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R8: sending, editing, deleting, reacting, hiding, closing. */
@IntegrationTest
class SendMessageTest extends MessagingTestSupport {

	@Test
	void repeatedClientMessageIdReturnsTheOriginal() throws Exception {
		Msg m = school("send-repeat");
		UUID thread = parentTeacherThread(m);
		UUID clientId = UUID.randomUUID();
		JsonNode first = post(conv(thread) + "/messages", m.g1(), body("clientMessageId", clientId, "body", "once"), 201);
		JsonNode second = post(conv(thread) + "/messages", m.g1(), body("clientMessageId", clientId, "body", "once"), 200);
		assertThat(first.get("created").asBoolean()).isTrue();
		assertThat(second.get("created").asBoolean()).isFalse();
		assertThat(second.get("message").get("id").asString()).isEqualTo(first.get("message").get("id").asString());
		// even a different text under the same id returns the original: the id is the identity
		JsonNode third = post(conv(thread) + "/messages", m.g1(), body("clientMessageId", clientId, "body", "other"), 200);
		assertThat(third.get("message").get("body").asString()).isEqualTo("once");
		assertThat(bodies(messagesOf(m.lead(), thread))).containsExactly("once");
		// the same id in another conversation is refused
		UUID other = conversationId(startDirect(m.g1(), m, m.co().userId(), m.s1(), 201));
		assertThat(code(post(conv(other) + "/messages", m.g1(), body("clientMessageId", clientId, "body", "x"), 409)))
			.isEqualTo("messaging.client_message_id_reused");
		// somebody who lost access gets the refusal, not the old message
		restrict(m.g1Link());
		assertThat(statusOf(HttpMethod.POST, conv(thread) + "/messages", m.g1(), body("clientMessageId", clientId, "body", "once")))
			.isEqualTo(404);
	}

	@Test
	void concurrentRepeatsStoreOneMessage() throws Exception {
		Msg m = school("send-race");
		UUID thread = parentTeacherThread(m);
		UUID clientId = UUID.randomUUID();
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				calls.add(() -> statusOf(HttpMethod.POST, conv(thread) + "/messages", m.g1(),
						body("clientMessageId", clientId, "body", "raced")));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(calls)) {
				statuses.add(f.get());
			}
			assertThat(statuses).containsOnly(200, 201);
			assertThat(statuses.stream().filter((s) -> s == 201).count()).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(this.jdbc.queryForObject("select count(*) from messages where sender_id = ? and client_message_id = ?", Integer.class,
				m.g1().userId(), clientId)).isEqualTo(1);
		assertThat(bodies(messagesOf(m.lead(), thread))).containsExactly("raced");
	}

	@Test
	void controlAndBidiCharactersAreStripped() throws Exception {
		Msg m = school("send-clean");
		UUID thread = parentTeacherThread(m);
		JsonNode sent = send(m.g1(), thread, "  a‮b​c\u0007d\r\ne ﻿<b>x</b>  ", 201);
		assertThat(sent.get("message").get("body").asString()).isEqualTo("abcd\ne <b>x</b>");
		// no markup is interpreted: the angle brackets are plain characters
		assertThat(bodies(messagesOf(m.lead(), thread))).containsExactly("abcd\ne <b>x</b>");
	}

	@Test
	void emptyAndTooLongTextIsRefused() throws Exception {
		Msg m = school("send-length");
		UUID thread = parentTeacherThread(m);
		assertThat(code(send(m.g1(), thread, "  ​ \n", 400))).isEqualTo("messaging.message_empty");
		assertThat(code(send(m.g1(), thread, "x".repeat(2001), 400))).isEqualTo("messaging.message_too_long");
		send(m.g1(), thread, "y".repeat(2000), 201);
		assertThat(code(post(conv(thread) + "/messages", m.g1(), body("body", "no id"), 400))).isEqualTo("validation_failed");
		assertThat(code(post(conv(thread) + "/messages", m.g1(),
				body("clientMessageId", UUID.randomUUID(), "body", "x", "bodyLanguageCode", "zz-nope"), 400))).isEqualTo("messaging.language_invalid");
	}

	@Test
	void voiceNotesAreNotBuilt() throws Exception {
		Msg m = school("send-voice");
		UUID thread = parentTeacherThread(m);
		for (String type : new String[] { "VOICE", "SYSTEM", "VIDEO" }) {
			assertThat(code(post(conv(thread) + "/messages", m.g1(),
					body("clientMessageId", UUID.randomUUID(), "messageType", type, "body", "x"), 400))).isEqualTo("messaging.type_not_supported");
		}
	}

	@Test
	void rateLimitsAreStricterForStudents() throws Exception {
		Msg m = school("send-rate");
		UUID classThread = classConversation(m);
		int studentFirst = firstRateLimited(m.student2(), classThread, 40);
		Persona adult = m.lead();
		int adultFirst = firstRateLimited(adult, classThread, 80);
		assertThat(studentFirst).isBetween(10, 25);
		assertThat(adultFirst).isBetween(30, 70);
		assertThat(studentFirst).isLessThan(adultFirst);
	}

	/** The 1-based number of the first send answered 429 (the window may roll, so allow a few more). */
	private int firstRateLimited(Persona who, UUID thread, int max) throws Exception {
		for (int i = 1; i <= max; i++) {
			int status = statusOf(HttpMethod.POST, conv(thread) + "/messages", who,
					body("clientMessageId", UUID.randomUUID(), "body", "n" + i));
			if (status == 429) {
				return i;
			}
			assertThat(status).isEqualTo(201);
		}
		return -1;
	}

	@Test
	void reactionsAreOnePerUserPerEmoji() throws Exception {
		Msg m = school("send-react");
		UUID thread = parentTeacherThread(m);
		UUID message = send(m.g1(), thread, "react to me");
		String thumbs = "👍";
		String path = conv(thread) + "/messages/" + message + "/reaction";
		call(HttpMethod.PUT, path, m.lead(), body("emoji", thumbs), 200);
		JsonNode again = call(HttpMethod.PUT, path, m.lead(), body("emoji", thumbs), 200);
		assertThat(again.get("reactions")).hasSize(1);
		assertThat(again.get("reactions").get(0).get("count").asInt()).isEqualTo(1);
		JsonNode two = call(HttpMethod.PUT, path, m.g1(), body("emoji", thumbs), 200);
		assertThat(two.get("reactions").get(0).get("count").asInt()).isEqualTo(2);
		assertThat(two.get("reactions").get(0).get("mine").asBoolean()).isTrue();
		call(HttpMethod.PUT, path, m.g1(), body("emoji", "❤️"), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from message_reactions where message_id = ?", Integer.class, message)).isEqualTo(3);
		assertThat(code(call(HttpMethod.PUT, path, m.g1(), body("emoji", "💩"), 400))).isEqualTo("messaging.reaction_invalid");
		assertThat(code(call(HttpMethod.PUT, path, m.g1(), body("emoji", "<script>"), 400))).isEqualTo("messaging.reaction_invalid");
		JsonNode removed = call(HttpMethod.DELETE, path + "?emoji=" + java.net.URLEncoder.encode(thumbs, "UTF-8"), m.lead(), null, 200);
		assertThat(removed.get("reactions")).hasSize(2);
		call(HttpMethod.DELETE, path + "?emoji=" + java.net.URLEncoder.encode(thumbs, "UTF-8"), m.lead(), null, 200);
		assertThat(statusOf(HttpMethod.PUT, path, m.stranger(), body("emoji", thumbs))).isEqualTo(404);
	}

	@Test
	void editKeepsHistoryAndRespectsTheWindow() throws Exception {
		Msg m = school("send-edit");
		UUID thread = parentTeacherThread(m);
		UUID message = send(m.g1(), thread, "first version");
		String path = conv(thread) + "/messages/" + message;
		assertThat(get(path, m.g1()).get("canEdit").asBoolean()).isTrue();
		assertThat(get(path, m.lead()).get("canEdit").asBoolean()).isFalse();
		assertThat(code(call(HttpMethod.PATCH, path, m.lead(), body("body", "hijacked"), 403))).isEqualTo("messaging.not_your_message");
		JsonNode edited = call(HttpMethod.PATCH, path, m.g1(), body("body", "second version"), 200);
		assertThat(edited.get("status").asString()).isEqualTo("EDITED");
		assertThat(edited.get("body").asString()).isEqualTo("second version");
		assertThat(edited.get("editedAt").isNull()).isFalse();
		call(HttpMethod.PATCH, path, m.g1(), body("body", "third version"), 200);
		// everybody sees the current text; the earlier versions are kept, in the history only
		assertThat(get(path, m.lead()).get("body").asString()).isEqualTo("third version");
		assertThat(this.jdbc.queryForList("select body from message_edits where message_id = ? order by edited_at, id", String.class, message))
			.containsExactly("first version", "second version");
		assertThat(get(path, m.lead()).toString()).doesNotContain("first version").doesNotContain("second version");
		// after the window it is closed
		this.clock.advance(Duration.ofMinutes(16));
		assertThat(get(path, m.g1()).get("canEdit").asBoolean()).isFalse();
		assertThat(code(call(HttpMethod.PATCH, path, m.g1(), body("body", "too late"), 409))).isEqualTo("messaging.edit_window_expired");
		this.clock.reset();
		// the window is a setting and 0 switches editing off
		setting(m.schoolId(), "messaging.edit_window_minutes", 0);
		assertThat(code(call(HttpMethod.PATCH, path, m.g1(), body("body", "nope"), 409))).isEqualTo("messaging.edit_disabled");
		setting(m.schoolId(), "messaging.edit_window_minutes", 60);
		call(HttpMethod.PATCH, path, m.g1(), body("body", "within an hour"), 200);
		// a deleted message cannot be edited
		call(HttpMethod.DELETE, path, m.g1(), null, 204);
		assertThat(code(call(HttpMethod.PATCH, path, m.g1(), body("body", "zombie"), 409))).isEqualTo("messaging.message_unavailable");
	}

	@Test
	void deleteLeavesATombstoneAndKeepsTheOriginalInHistory() throws Exception {
		Msg m = school("send-delete");
		UUID thread = parentTeacherThread(m);
		UUID message = send(m.g1(), thread, "regrettable words");
		send(m.lead(), thread, "later");
		String path = conv(thread) + "/messages/" + message;
		assertThat(code(call(HttpMethod.DELETE, path, m.lead(), null, 403))).isEqualTo("messaging.not_your_message");
		call(HttpMethod.DELETE, path, m.g1(), null, 204);
		call(HttpMethod.DELETE, path, m.g1(), null, 204);
		for (Persona who : new Persona[] { m.g1(), m.lead() }) {
			JsonNode view = get(path, who);
			assertThat(view.get("status").asString()).isEqualTo("DELETED");
			assertThat(view.has("body") && !view.get("body").isNull()).isFalse();
			assertThat(messagesOf(who, thread).toString()).doesNotContain("regrettable");
			assertThat(inbox(who).toString()).doesNotContain("regrettable");
		}
		// the stored row no longer holds the text; the history does
		assertThat(this.jdbc.queryForObject("select body from messages where id = ?", String.class, message)).isNull();
		assertThat(this.jdbc.queryForList("select body from message_edits where message_id = ? and kind = 'DELETE'", String.class, message))
			.containsExactly("regrettable words");
		// a tombstone is not a "last message preview"
		UUID last = send(m.g1(), thread, "bye");
		call(HttpMethod.DELETE, conv(thread) + "/messages/" + last, m.g1(), null, 204);
		JsonNode lastMessage = inbox(m.lead()).get("items").get(0).get("lastMessage");
		assertThat(lastMessage.get("status").asString()).isEqualTo("DELETED");
		assertThat(lastMessage.has("preview") && !lastMessage.get("preview").isNull()).isFalse();
	}

	@Test
	void adultsCannotEditOrDeleteInSafeguardedConversations() throws Exception {
		Msg m = school("send-record");
		UUID thread = teacherStudentThread(m);
		UUID fromTeacher = send(m.lead(), thread, "CANARY-KEPT as sent");
		String path = conv(thread) + "/messages/" + fromTeacher;
		assertThat(code(call(HttpMethod.PATCH, path, m.lead(), body("body", "rewritten"), 409))).isEqualTo("messaging.record_kept");
		assertThat(code(call(HttpMethod.DELETE, path, m.lead(), null, 409))).isEqualTo("messaging.record_kept");
		assertThat(get(path, m.lead()).get("canEdit").asBoolean()).isFalse();
		assertThat(get(path, m.lead()).get("canDelete").asBoolean()).isFalse();
		assertThat(get(path, m.student1()).get("body").asString()).isEqualTo("CANARY-KEPT as sent");
		// the student may still edit and delete their own words, and the teacher may withdraw a scheduled message
		UUID fromStudent = send(m.student1(), thread, "mine");
		call(HttpMethod.PATCH, conv(thread) + "/messages/" + fromStudent, m.student1(), body("body", "mine, edited"), 200);
		call(HttpMethod.DELETE, conv(thread) + "/messages/" + fromStudent, m.student1(), null, 204);
		JsonNode scheduled = post(conv(thread) + "/messages", m.lead(), body("clientMessageId", UUID.randomUUID(), "body", "later", "sendAt",
				this.clock.instant().plus(Duration.ofHours(1))), 201);
		call(HttpMethod.DELETE, conv(thread) + "/messages/" + scheduled.get("message").get("id").asString(), m.lead(), null, 204);
		// in other conversations adults keep their rights
		UUID parentThread = parentTeacherThread(m);
		UUID own = send(m.lead(), parentThread, "can change");
		call(HttpMethod.PATCH, conv(parentThread) + "/messages/" + own, m.lead(), body("body", "changed"), 200);
	}

	@Test
	void replyToMustBeInTheSameConversation() throws Exception {
		Msg m = school("send-reply");
		UUID thread = parentTeacherThread(m);
		UUID other = conversationId(startDirect(m.g1(), m, m.co().userId(), m.s1(), 201));
		UUID inThread = send(m.lead(), thread, "question?");
		UUID elsewhere = send(m.co(), other, "unrelated");
		JsonNode reply = post(conv(thread) + "/messages", m.g1(),
				body("clientMessageId", UUID.randomUUID(), "body", "answer", "replyToMessageId", inThread), 201);
		assertThat(reply.get("message").get("replyToMessageId").asString()).isEqualTo(inThread.toString());
		assertThat(code(post(conv(thread) + "/messages", m.g1(),
				body("clientMessageId", UUID.randomUUID(), "body", "sneaky", "replyToMessageId", elsewhere), 400))).isEqualTo("messaging.reply_invalid");
		// the reply carries the id only, never the quoted text
		assertThat(reply.toString()).doesNotContain("question?");
	}

	@Test
	void hiddenMessagesShowATombstone() throws Exception {
		Msg m = school("send-hide");
		UUID classThread = classConversation(m);
		UUID message = send(m.student1(), classThread, "something rude");
		String path = conv(classThread) + "/messages/" + message;
		// students and non-moderators cannot hide; a reason is mandatory
		assertThat(statusOf(HttpMethod.POST, path + "/hide", m.student2(), body("reason", "x"))).isEqualTo(403);
		assertThat(statusOf(HttpMethod.POST, path + "/hide", m.subject(), body("reason", "x"))).isEqualTo(403);
		assertThat(code(call(HttpMethod.POST, path + "/hide", m.lead(), body("reason", " "), 400))).isEqualTo("messaging.reason_required");
		call(HttpMethod.POST, path + "/hide", m.lead(), body("reason", "unkind"), 204);
		for (Persona who : new Persona[] { m.student1(), m.student2(), m.lead() }) {
			JsonNode view = get(path, who);
			assertThat(view.get("status").asString()).isEqualTo("HIDDEN");
			assertThat(view.has("body") && !view.get("body").isNull()).isFalse();
			assertThat(messagesOf(who, classThread).toString()).doesNotContain("something rude");
		}
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'messaging.message.hide' and target_id = ?",
				Integer.class, message)).isEqualTo(1);
		// the author cannot edit or react to a hidden message; a moderator brings it back
		assertThat(code(call(HttpMethod.PATCH, path, m.student1(), body("body", "x"), 409))).isEqualTo("messaging.message_unavailable");
		call(HttpMethod.POST, path + "/unhide", m.lead(), null, 204);
		assertThat(get(path, m.student2()).get("body").asString()).isEqualTo("something rude");
	}

	@Test
	void lockedAndModeratorsOnlyConversationsRefuse() throws Exception {
		Msg m = school("send-closed");
		UUID classThread = classConversation(m);
		call(HttpMethod.PATCH, conv(classThread), m.lead(), body("status", "LOCKED"), 200);
		assertThat(code(send(m.student1(), classThread, "locked?", 409))).isEqualTo("messaging.conversation_closed");
		assertThat(code(send(m.lead(), classThread, "even me", 409))).isEqualTo("messaging.conversation_closed");
		// readable while locked
		assertThat(statusOf(HttpMethod.GET, conv(classThread) + "/messages", m.student1(), null)).isEqualTo(200);
		call(HttpMethod.PATCH, conv(classThread), m.lead(), body("status", "ACTIVE", "postingPolicy", "MODERATORS_ONLY"), 200);
		assertThat(code(send(m.student1(), classThread, "may I?", 403))).isEqualTo("messaging.posting_restricted");
		assertThat(code(send(m.subject(), classThread, "subject teacher", 403))).isEqualTo("messaging.posting_restricted");
		send(m.lead(), classThread, "announcement");
		send(m.co(), classThread, "also a moderator");
		// non-moderators cannot change the settings
		assertThat(statusOf(HttpMethod.PATCH, conv(classThread), m.student1(), body("status", "ACTIVE"))).isEqualTo(403);
		assertThat(statusOf(HttpMethod.PATCH, conv(classThread), m.stranger(), body("status", "ACTIVE"))).isEqualTo(404);
	}

}

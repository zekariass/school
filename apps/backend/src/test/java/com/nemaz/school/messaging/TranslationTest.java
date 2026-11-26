package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.RecordingTranslationProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R11.4: translation of messages reuses the adapter and the cache of the feed, with strict limits. */
@IntegrationTest
class TranslationTest extends MessagingTestSupport {

	@Autowired
	RecordingTranslationProvider translator;

	@AfterEach
	void resetTranslator() {
		this.translator.reset();
	}

	private String url(UUID thread, UUID message) {
		return conv(thread) + "/messages/" + message + "/translation?language=am";
	}

	@Test
	void offByDefaultAndNeverForAdultStudentDirect() throws Exception {
		Msg m = school("tr-off");
		UUID classThread = classConversation(m);
		UUID message = send(m.student1(), classThread, "good morning everyone");
		// off by default
		assertThat(code(post(url(classThread, message), m.lead(), null, 403))).isEqualTo("messaging.translation_disabled");
		assertThat(this.translator.received()).isEmpty();
		// on, but no provider is available
		setting(m.schoolId(), "messaging.translation.enabled", true);
		assertThat(code(post(url(classThread, message), m.lead(), null, 503))).isEqualTo("messaging.translation_unavailable");
		// never in a direct conversation between an adult and a student, whatever the setting says
		this.translator.available(true);
		UUID thread = teacherStudentThread(m);
		UUID direct = send(m.student1(), thread, "a private note to my teacher");
		assertThat(code(post(url(thread, direct), m.lead(), null, 403))).isEqualTo("messaging.translation_not_allowed");
		assertThat(code(post(url(thread, direct), m.g1(), null, 403))).isEqualTo("messaging.translation_not_allowed");
		assertThat(code(post(url(thread, direct), m.student1(), null, 403))).isEqualTo("messaging.translation_not_allowed");
		assertThat(this.translator.received()).isEmpty();
		// people who cannot see the message cannot translate it
		assertThat(statusOf(HttpMethod.POST, url(classThread, message), m.stranger(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.POST, url(classThread, message), m.g1(), null)).isEqualTo(404);
		// deleted or hidden messages have nothing to translate
		call(HttpMethod.POST, conv(classThread) + "/messages/" + message + "/hide", m.lead(), body("reason", "off topic"), 204);
		assertThat(statusOf(HttpMethod.POST, url(classThread, message), m.student2(), null)).isEqualTo(400);
		assertThat(this.translator.received()).isEmpty();
	}

	@Test
	void masksNamesBeforeTheProvider() throws Exception {
		Msg m = school("tr-mask");
		UUID classThread = classConversation(m);
		setting(m.schoolId(), "messaging.translation.enabled", true);
		this.translator.available(true);
		UUID message = send(m.student1(), classThread, "Abel will call Bethlehem on +251911234567 or write to abel@example.com tomorrow");
		JsonNode translated = post(url(classThread, message), m.student2(), null, 200);
		assertThat(translated.get("machineTranslated").asBoolean()).isTrue();
		assertThat(translated.get("cached").asBoolean()).isFalse();
		assertThat(translated.get("targetLanguage").asString()).isEqualTo("am");
		// restored locally for the reader ...
		assertThat(translated.get("text").asString()).contains("Abel").contains("Bethlehem").contains("+251911234567").contains("abel@example.com");
		// ... but the provider never saw them
		assertThat(this.translator.received()).hasSize(1);
		String sentToProvider = this.translator.received().getFirst().text();
		assertThat(sentToProvider).doesNotContain("Abel").doesNotContain("Bethlehem").doesNotContain("251911234567").doesNotContain("example.com");
		assertThat(this.translator.received().getFirst().toString()).doesNotContain(message.toString());
		// the second request is served from the cache
		JsonNode again = post(url(classThread, message), m.student3(), null, 200);
		assertThat(again.get("cached").asBoolean()).isTrue();
		assertThat(this.translator.received()).hasSize(1);
		assertThat(this.jdbc.queryForObject("select count(*) from content_translations where source_type = 'MESSAGE' and source_id = ?", Integer.class,
				message)).isEqualTo(1);
		// an edited message is translated again; an unknown language is refused
		call(HttpMethod.PATCH, conv(classThread) + "/messages/" + message, m.student1(), body("body", "see you on Monday"), 200);
		assertThat(post(url(classThread, message), m.student2(), null, 200).get("cached").asBoolean()).isFalse();
		assertThat(code(post(conv(classThread) + "/messages/" + message + "/translation?language=xx-nope", m.student2(), null, 400)))
			.isEqualTo("messaging.translation_language_invalid");
		// a failing provider caches nothing
		this.translator.failing(true);
		UUID other = send(m.lead(), classThread, "homework is due");
		assertThat(code(post(url(classThread, other), m.student2(), null, 503))).isEqualTo("messaging.translation_unavailable");
		assertThat(this.jdbc.queryForObject("select count(*) from content_translations where source_id = ?", Integer.class, other)).isZero();
	}

}

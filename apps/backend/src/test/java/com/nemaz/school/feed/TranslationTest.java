package com.nemaz.school.feed;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.feed.TranslationProvider.TranslationRequest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** "See translation" (decision D5): off by default, adapter-only, nothing but masked post text leaves the platform. */
@IntegrationTest
class TranslationTest extends FeedTestSupport {

	private String url(Feed f, String postId) {
		return f.api() + "/posts/" + postId + "/translation";
	}

	@Test
	void translationIsOffByDefaultAndWithoutAProviderUnavailable() throws Exception {
		Feed f = feed("translate-off");
		String id = classPost(f.lead(), f, "Hello class").get("id").asString();
		assertThat(code(post(url(f, id) + "?language=am", f.guardian1(), null, 403))).isEqualTo("feed.translation_disabled");
		assertThat(this.translator.received()).isEmpty();
		setting(f.schoolId(), "feed.translation.enabled", true);
		// no provider is available (the shipped state): 503, and nothing was cached
		assertThat(code(post(url(f, id) + "?language=am", f.guardian1(), null, 503))).isEqualTo("feed.translation_unavailable");
		assertThat(this.jdbc.queryForObject("select count(*) from content_translations where source_id = ?", Integer.class, UUID.fromString(id)))
			.isZero();
		assertThat(this.translator.received()).isEmpty();
	}

	@Test
	void onlyPublishedPostsOfVisibleClassesAreTranslated() throws Exception {
		Feed f = feed("translate-access");
		setting(f.schoolId(), "feed.translation.enabled", true);
		this.translator.available(true);
		String draft = classPost(f.lead(), f, "A draft", 201, Map.of("mode", "DRAFT")).get("id").asString();
		call(HttpMethod.POST, url(f, draft) + "?language=am", f.lead(), null, 404);
		String id = classPost(f.lead(), f, "Hello class").get("id").asString();
		for (Persona outsider : List.of(f.otherGuardian(), f.otherStudent(), f.restricted(), f.revoked(), f.otherTeacher())) {
			call(HttpMethod.POST, url(f, id) + "?language=am", outsider, null, 404);
		}
		assertThat(this.translator.received()).isEmpty();
		call(HttpMethod.POST, url(f, id) + "?language=zz", f.guardian1(), null, 400);
		JsonNode ok = post(url(f, id) + "?language=am", f.guardian1(), null, 200);
		assertThat(ok.get("machineTranslated").asBoolean()).isTrue();
		assertThat(ok.get("text").asString()).isEqualTo("[am] Hello class");
	}

	@Test
	void theProviderGetsOnlyMaskedTextAndResultsAreCachedUntilTheTextChanges() throws Exception {
		Feed f = feed("translate-mask");
		setting(f.schoolId(), "feed.translation.enabled", true);
		this.translator.available(true);
		grant(f.guardian1(), f.s1());
		UUID photo = photo(f.lead(), f.classId());
		String text = "Abel read aloud today. Call 0911 123 456 or write to teacher@school.example. Well done, abel!";
		JsonNode post = classPost(f.lead(), f, text, 201, Map.of("media", refs(photo), "studentIds", List.of(f.s1().toString()),
				"bodyLanguageCode", "en"));
		String id = post.get("id").asString();

		JsonNode first = post(url(f, id) + "?language=am", f.guardian2(), null, 200);
		assertThat(first.get("cached").asBoolean()).isFalse();
		assertThat(first.get("provider").asString()).isEqualTo("fake");
		assertThat(first.get("sourceLanguage").asString()).isEqualTo("en");
		// what the provider received: the text and two language codes, with the child's name, the phone number and the address masked
		assertThat(this.translator.received()).hasSize(1);
		TranslationRequest sent = this.translator.received().getFirst();
		assertThat(sent.text()).doesNotContain("Abel").doesNotContain("abel").doesNotContain("0911").doesNotContain("@school")
			.contains("read aloud");
		assertThat(sent.sourceLanguage()).isEqualTo("en");
		assertThat(sent.targetLanguage()).isEqualTo("am");
		// the family still reads their own names in the result
		assertThat(first.get("text").asString()).contains("Abel").contains("0911 123 456").contains("teacher@school.example");

		// the second request is served from the cache: no provider call, any user who may see the post
		JsonNode second = post(url(f, id) + "?language=am", f.guardian1(), null, 200);
		assertThat(second.get("cached").asBoolean()).isTrue();
		assertThat(second.get("text").asString()).isEqualTo(first.get("text").asString());
		assertThat(this.translator.received()).hasSize(1);
		// another language is another entry
		post(url(f, id) + "?language=om", f.guardian1(), null, 200);
		assertThat(this.translator.received()).hasSize(2);
		// the same language as the original needs no provider and is not labelled machine translated
		JsonNode same = post(url(f, id) + "?language=en", f.guardian1(), null, 200);
		assertThat(same.get("machineTranslated").asBoolean()).isFalse();
		assertThat(this.translator.received()).hasSize(2);
		// editing the text invalidates the cache (the key carries a hash of the text)
		call(HttpMethod.PATCH, f.api() + "/posts/" + id, f.lead(), Map.of("body", "Abel read again"), 200);
		JsonNode third = post(url(f, id) + "?language=am", f.guardian1(), null, 200);
		assertThat(third.get("cached").asBoolean()).isFalse();
		assertThat(this.translator.received()).hasSize(3);
		assertThat(this.translator.received().get(2).text()).doesNotContain("Abel");
		assertThat(this.jdbc.queryForObject("select count(*) from content_translations where source_id = ?", Integer.class, UUID.fromString(id)))
			.isEqualTo(3);
		// the original is untouched
		assertThat(get(f.api() + "/posts/" + id, f.guardian1()).get("body").asString()).isEqualTo("Abel read again");
	}

	@Test
	void aFailingProviderAnswers503AndCachesNothing() throws Exception {
		Feed f = feed("translate-fail");
		setting(f.schoolId(), "feed.translation.enabled", true);
		this.translator.available(true);
		this.translator.failing(true);
		String id = classPost(f.lead(), f, "Hello class").get("id").asString();
		assertThat(code(post(url(f, id) + "?language=am", f.guardian1(), null, 503))).isEqualTo("feed.translation_unavailable");
		assertThat(this.jdbc.queryForObject("select count(*) from content_translations where source_id = ?", Integer.class, UUID.fromString(id)))
			.isZero();
		this.translator.failing(false);
		assertThat(post(url(f, id) + "?language=am", f.guardian1(), null, 200).get("cached").asBoolean()).isFalse();
	}

	@Test
	void commentsCanBeTranslatedByThoseWhoSeeThem() throws Exception {
		Feed f = feed("translate-comment");
		setting(f.schoolId(), "feed.translation.enabled", true);
		this.translator.available(true);
		String id = classPost(f.lead(), f, "Hello class").get("id").asString();
		String comment = post(f.api() + "/posts/" + id + "/comments", f.guardian1(), Map.of("body", "Thank you teacher"), 201).get("id").asString();
		String url = f.api() + "/posts/" + id + "/comments/" + comment + "/translation?language=am";
		assertThat(post(url, f.guardian2(), null, 200).get("text").asString()).isEqualTo("[am] Thank you teacher");
		call(HttpMethod.POST, url, f.otherGuardian(), null, 404);
		// a hidden comment is translated for moderators only
		post(f.api() + "/posts/" + id + "/comments/" + comment + "/hide", f.lead(), null, 200);
		call(HttpMethod.POST, url, f.guardian2(), null, 404);
		call(HttpMethod.POST, url, f.lead(), null, 200);
	}

}

package com.nemaz.school.feed;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Class and school stories: visibility, view tracking, consent, 24-hour expiry enforced at read time and by the job. */
@IntegrationTest
class StoryTest extends FeedTestSupport {

	private JsonNode story(Persona who, Feed f, Map<String, Object> body, int status) throws Exception {
		return post(f.classApi() + "/stories", who, body, status);
	}

	private JsonNode photoStory(Persona who, Feed f, String caption) throws Exception {
		UUID media = storyPhoto(who, f.classId());
		return story(who, f, Map.of("caption", caption, "mediaId", media.toString(), "confirmNoUnconsentedStudents", true), 201);
	}

	@Test
	void teachersPublishAndOnlyTheClassFamiliesSee() throws Exception {
		Feed f = feed("story-visibility");
		JsonNode story = photoStory(f.lead(), f, "Sports day");
		String id = story.get("id").asString();
		assertThat(story.get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(story.get("media")).isNotNull();
		String media = story.get("media").get("mediaId").asString();
		for (Persona viewer : List.of(f.lead(), f.co(), f.admin(), f.student1(), f.student2(), f.guardian1(), f.guardian2())) {
			assertThat(get(f.classApi() + "/stories", viewer).findValuesAsString("id")).as(viewer.name()).containsExactly(id);
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(200);
		}
		assertThat(get("/api/v1/me/stories", f.guardian1()).findValuesAsString("id")).containsExactly(id);
		assertThat(get("/api/v1/me/stories?studentId=" + f.s1(), f.guardian1()).findValuesAsString("id")).containsExactly(id);
		call(HttpMethod.GET, "/api/v1/me/stories?studentId=" + f.s2(), f.guardian1(), null, 404);
		for (Persona viewer : List.of(f.otherTeacher(), f.otherStudent(), f.otherGuardian(), f.restricted(), f.revoked())) {
			call(HttpMethod.GET, f.classApi() + "/stories", viewer, null, 404);
			call(HttpMethod.GET, f.api() + "/stories/" + id, viewer, null, 404);
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(404);
		}
		assertThat(get("/api/v1/me/stories", f.otherGuardian()).findValuesAsString("id")).isEmpty();
		// only the class's posters publish
		assertThat(code(story(f.guardian1(), f, Map.of("caption", "No"), 403))).isEqualTo("feed.access_denied");
		assertThat(code(story(f.student1(), f, Map.of("caption", "No"), 403))).isEqualTo("feed.access_denied");
		assertThat(code(story(f.lead(), f, Map.of(), 400))).isEqualTo("feed.story_empty");
		// a text-only story needs no consent
		assertThat(story(f.co(), f, Map.of("caption", "Remember your books"), 201).has("media")).isFalse();
	}

	@Test
	void schoolStoriesNeedTheFeedPermission() throws Exception {
		Feed f = feed("story-school");
		JsonNode story = post(f.api() + "/stories", f.admin(), Map.of("caption", "Open day on Friday"), 201);
		assertThat(story.get("scope").asString()).isEqualTo("SCHOOL");
		assertThat(code(post(f.api() + "/stories", f.lead(), Map.of("caption", "No"), 403))).isEqualTo("feed.access_denied");
		assertThat(get(f.api() + "/stories", f.guardian1()).findValuesAsString("id")).containsExactly(story.get("id").asString());
		assertThat(get(f.api() + "/stories", f.otherTeacher()).findValuesAsString("id")).containsExactly(story.get("id").asString());
		call(HttpMethod.GET, f.api() + "/stories", f.restricted(), null, 404);
		call(HttpMethod.GET, f.api() + "/stories", this.personas.newUser("story-stranger"), null, 404);
	}

	@Test
	void viewsAreCountedOncePerViewerAndShownAsACountToStaffOnly() throws Exception {
		Feed f = feed("story-views");
		String id = photoStory(f.lead(), f, "Counting").get("id").asString();
		String url = f.api() + "/stories/" + id;
		call(HttpMethod.POST, url + "/view", f.guardian1(), null, 204);
		call(HttpMethod.POST, url + "/view", f.guardian1(), null, 204);
		call(HttpMethod.POST, url + "/view", f.guardian2(), null, 204);
		call(HttpMethod.POST, url + "/view", f.lead(), null, 204);
		call(HttpMethod.POST, url + "/view", f.otherGuardian(), null, 404);
		assertThat(this.jdbc.queryForObject("select count(*) from story_views where story_id = ?", Integer.class, UUID.fromString(id)))
			.isEqualTo(3);
		assertThat(get(url, f.lead()).get("seenCount").asInt()).isEqualTo(2);
		assertThat(get(url, f.co()).get("seenCount").asInt()).isEqualTo(2);
		assertThat(get(url, f.guardian1()).has("seenCount")).isFalse();
		assertThat(get(url, f.guardian1()).get("viewedByMe").asBoolean()).isTrue();
		assertThat(get(url, f.guardian2()).toString()).doesNotContain(f.guardian1().name());
	}

	@Test
	void aStoryIsGoneAfter24HoursEvenBeforeTheJobRunsAndTheJobIsIdempotent() throws Exception {
		Feed f = feed("story-expiry");
		JsonNode story = photoStory(f.lead(), f, "Short lived");
		String id = story.get("id").asString();
		String media = story.get("media").get("mediaId").asString();
		assertThat(story.get("expiresAt").asString()).isNotBlank();
		this.clock.advance(Duration.ofHours(23));
		assertThat(get(f.classApi() + "/stories", f.guardian1()).findValuesAsString("id")).containsExactly(id);
		assertThat(mediaUrlStatus(f.guardian1(), media)).isEqualTo(200);

		// 25 hours: the job has not run, yet nobody but the author and the moderators gets anything
		this.clock.advance(Duration.ofHours(2));
		assertThat(this.jdbc.queryForObject("select status from stories where id = ?", String.class, UUID.fromString(id))).isEqualTo("PUBLISHED");
		for (Persona viewer : List.of(f.guardian1(), f.student1(), f.co())) {
			assertThat(get(f.classApi() + "/stories", viewer).findValuesAsString("id")).as(viewer.name()).isEmpty();
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(404);
			call(HttpMethod.GET, f.api() + "/stories/" + id, viewer, null, 404);
			call(HttpMethod.POST, f.api() + "/stories/" + id + "/view", viewer, null, 404);
		}
		assertThat(get("/api/v1/me/stories", f.guardian1()).findValuesAsString("id")).isEmpty();
		assertThat(mediaUrlStatus(f.lead(), media)).isEqualTo(200);

		// the job expires it once; running it again changes nothing
		assertThat(this.maintenance.expireStories()).isGreaterThanOrEqualTo(1);
		assertThat(this.maintenance.expireStories()).isZero();
		assertThat(this.jdbc.queryForObject("select status from stories where id = ?", String.class, UUID.fromString(id))).isEqualTo("EXPIRED");
		assertThat(mediaUrlStatus(f.guardian1(), media)).isEqualTo(404);
		assertThat(mediaUrlStatus(f.lead(), media)).isEqualTo(200);

		// a week later the photo is deleted
		this.clock.advance(Duration.ofDays(8));
		assertThat(this.maintenance.expireStories()).isGreaterThanOrEqualTo(1);
		assertThat(this.maintenance.expireStories()).isZero();
		assertThat(this.jdbc.queryForObject("select status from media where id = ?", String.class, UUID.fromString(media))).isEqualTo("DELETED");
		assertThat(mediaUrlStatus(f.lead(), media)).isEqualTo(404);
		assertThat(this.jdbc.queryForObject("select status from stories where id = ?", String.class, UUID.fromString(id))).isEqualTo("DELETED");
	}

	@Test
	void storiesGoThroughTheConsentGateAndAreWithheldWhenConsentIsWithdrawn() throws Exception {
		Feed f = feed("story-consent");
		UUID media = storyPhoto(f.lead(), f.classId());
		Map<String, Object> body = Map.of("mediaId", media.toString(), "studentIds", List.of(f.s1().toString()));
		assertThat(code(story(f.lead(), f, body, 422))).isEqualTo("feed.media_consent_missing");
		assertThat(code(story(f.lead(), f, Map.of("mediaId", media.toString()), 422))).isEqualTo("feed.media_confirmation_required");
		grant(f.guardian1(), f.s1());
		JsonNode story = story(f.lead(), f, body, 201);
		String id = story.get("id").asString();
		assertThat(story.get("tags")).hasSize(1);
		awaitNotifications(f.guardian1().userId(), "NEW_STORY", 1);
		assertThat(get(f.classApi() + "/stories", f.guardian2()).findValuesAsString("id")).containsExactly(id);

		decide(f.guardian1(), f.s1(), "WITHDRAWN");
		for (Persona viewer : List.of(f.guardian1(), f.guardian2(), f.student2())) {
			assertThat(get(f.classApi() + "/stories", viewer).findValuesAsString("id")).as(viewer.name()).isEmpty();
			call(HttpMethod.GET, f.api() + "/stories/" + id, viewer, null, 404);
			assertThat(mediaUrlStatus(viewer, media.toString())).as(viewer.name()).isEqualTo(404);
		}
		JsonNode seen = get(f.api() + "/stories/" + id, f.lead());
		assertThat(seen.get("mediaWithheld").asBoolean()).isTrue();
		assertThat(mediaUrlStatus(f.lead(), media.toString())).isEqualTo(200);
		awaitNotifications(f.lead().userId(), "POST_MEDIA_WITHHELD", 1);
		grant(f.guardian1(), f.s1());
		assertThat(get(f.classApi() + "/stories", f.guardian2()).findValuesAsString("id")).containsExactly(id);
	}

	@Test
	void authorsAndModeratorsDeleteStories() throws Exception {
		Feed f = feed("story-delete");
		String byCo = photoStory(f.co(), f, "Mine").get("id").asString();
		call(HttpMethod.DELETE, f.api() + "/stories/" + byCo, f.subject(), null, 403);
		call(HttpMethod.DELETE, f.api() + "/stories/" + byCo, f.guardian1(), null, 403);
		call(HttpMethod.DELETE, f.api() + "/stories/" + byCo, f.otherGuardian(), null, 404);
		call(HttpMethod.DELETE, f.api() + "/stories/" + byCo, f.lead(), null, 204);
		call(HttpMethod.GET, f.api() + "/stories/" + byCo, f.lead(), null, 404);
		String mine = photoStory(f.co(), f, "Mine again").get("id").asString();
		call(HttpMethod.DELETE, f.api() + "/stories/" + mine, f.co(), null, 204);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'feed.story.delete' and school_id = ?",
				Integer.class, f.schoolId())).isEqualTo(2);
	}

}

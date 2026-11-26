package com.nemaz.school.feed;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.MediaFixtures;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R-30: media is read through its post or story, and uploaded only by those who may post or contribute. */
@IntegrationTest
class FeedMediaAccessTest extends FeedTestSupport {

	private JsonNode publishedPost(Feed f, Persona author) throws Exception {
		UUID media = photo(author, f.classId());
		return classPost(author, f, "Photo day", 201, Map.of("media", refs(media), "confirmNoUnconsentedStudents", true));
	}

	private static String mediaOf(JsonNode post) {
		return post.get("media").get(0).get("mediaId").asString();
	}

	@Test
	void aPublishedPostsPhotoIsReadableByThoseWhoSeeThePostOnly() throws Exception {
		Feed f = feed("media-read");
		String media = mediaOf(publishedPost(f, f.lead()));
		for (Persona viewer : List.of(f.lead(), f.co(), f.subject(), f.assistant(), f.admin(), f.student1(), f.student2(),
				f.guardian1(), f.guardian2())) {
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(200);
			getMedia(viewer, media).andExpect(status().isOk());
		}
		Persona otherAdmin = this.personas.schoolAdmin(this.personas.newSchool("media-elsewhere"), "media-elsewhere-admin");
		for (Persona viewer : List.of(f.otherTeacher(), f.otherStudent(), f.otherGuardian(), f.restricted(), f.revoked(),
				this.personas.newUser("media-stranger"), otherAdmin)) {
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(404);
			getMedia(viewer, media).andExpect(status().isNotFound());
		}
		// never a link to the original of an image, whoever asks
		for (String variant : List.of("", "?variant=ORIGINAL")) {
			this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/media/" + media + "/url" + variant)
					.with(f.lead().bearer()))
				.andExpect(status().isBadRequest());
		}
		// a custody restriction applied later cuts a guardian off at once
		this.jdbc.update("update student_guardians set custody_restricted = true where guardian_id = ?", f.guardian1().userId());
		assertThat(mediaUrlStatus(f.guardian1(), media)).isEqualTo(404);
	}

	@Test
	void anUnpublishedPostsPhotoIsReadableByItsAuthorAndTheModeratorsOnly() throws Exception {
		Feed f = feed("media-draft");
		UUID photo = photo(f.co(), f.classId());
		JsonNode draft = classPost(f.co(), f, "Draft", 201, Map.of("mode", "DRAFT", "media", refs(photo)));
		String media = photo.toString();
		for (Persona viewer : List.of(f.co(), f.lead(), f.admin())) {
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(200);
		}
		for (Persona viewer : List.of(f.subject(), f.assistant(), f.student1(), f.guardian1(), f.otherTeacher())) {
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(404);
		}
		// published: now the families too
		post(f.api() + "/posts/" + draft.get("id").asString() + "/publish", f.co(), Map.of("confirmNoUnconsentedStudents", true), 200);
		assertThat(mediaUrlStatus(f.guardian1(), media)).isEqualTo(200);
		// hidden by a moderator: the families lose it again
		post(f.api() + "/posts/" + draft.get("id").asString() + "/hide", f.lead(), Map.of(), 200);
		assertThat(mediaUrlStatus(f.guardian1(), media)).isEqualTo(404);
		assertThat(mediaUrlStatus(f.co(), media)).isEqualTo(200);
		// deleted: nobody
		call(HttpMethod.DELETE, f.api() + "/posts/" + draft.get("id").asString(), f.co(), null, 204);
		for (Persona viewer : List.of(f.co(), f.lead(), f.admin(), f.guardian1())) {
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(404);
		}
	}

	@Test
	void anUploadThatIsNotAttachedYetBelongsToItsUploaderAlone() throws Exception {
		Feed f = feed("media-unattached");
		UUID photo = photo(f.co(), f.classId());
		assertThat(mediaUrlStatus(f.co(), photo.toString())).isEqualTo(200);
		for (Persona viewer : List.of(f.lead(), f.admin(), f.subject(), f.guardian1(), f.student1())) {
			assertThat(mediaUrlStatus(viewer, photo.toString())).as(viewer.name()).isEqualTo(404);
		}
		// and nobody else can attach it
		assertThat(code(classPost(f.subject(), f, "Stolen", 404, Map.of("media", refs(photo), "confirmNoUnconsentedStudents", true))))
			.isEqualTo("media.not_found");
	}

	@Test
	void aPhotoBelongsToOnePostAndOneClassAndMustBeAPhoto() throws Exception {
		Feed f = feed("media-attach");
		UUID photo = photo(f.lead(), f.classId());
		classPost(f.lead(), f, "First", 201, Map.of("media", refs(photo), "confirmNoUnconsentedStudents", true));
		assertThat(code(classPost(f.lead(), f, "Again", 409, Map.of("media", refs(photo), "confirmNoUnconsentedStudents", true))))
			.isEqualTo("feed.media_already_attached");
		// two references to the same file in one post
		UUID second = photo(f.lead(), f.classId());
		assertThat(code(classPost(f.lead(), f, "Twice", 400, Map.of("media", refs(second, second), "confirmNoUnconsentedStudents", true))))
			.isEqualTo("feed.media_already_attached");
		// a photo made for another class does not fit this one
		UUID otherClassPhoto = photo(f.admin(), f.otherClassId());
		assertThat(code(classPost(f.admin(), f, "Wrong class", 400, Map.of("media", refs(otherClassPhoto), "confirmNoUnconsentedStudents", true))))
			.isEqualTo("media.context_mismatch");
		// only photos for now: no PDFs, no videos
		requestUpload(f.lead(), "POST", "a.pdf", "application/pdf", 100, classContext(f.classId())).andExpect(status().isBadRequest());
		requestUpload(f.lead(), "POST", "a.mp4", "video/mp4", 100, classContext(f.classId())).andExpect(status().isBadRequest());
		// at most ten photos
		List<Map<String, Object>> eleven = new java.util.ArrayList<>();
		for (int i = 0; i < 11; i++) {
			eleven.add(Map.of("mediaId", UUID.randomUUID().toString()));
		}
		assertThat(code(classPost(f.lead(), f, "Many", 400, Map.of("media", eleven)))).isIn("feed.too_many_media", "validation_failed");
	}

	@Test
	void whoMayUploadPostMedia() throws Exception {
		Feed f = feed("media-upload");
		Map<String, Object> ctx = classContext(f.classId());
		for (Persona poster : List.of(f.lead(), f.co(), f.subject(), f.assistant(), f.admin())) {
			requestUpload(poster, "POST", "a.jpg", "image/jpeg", 1000, ctx).andExpect(status().isCreated());
		}
		// families and students may not, until the school accepts their contributions
		for (Persona family : List.of(f.guardian1(), f.student1())) {
			requestUpload(family, "POST", "a.jpg", "image/jpeg", 1000, ctx).andExpect(status().isForbidden());
		}
		setting(f.schoolId(), "feed.parent_contributions.enabled", true);
		requestUpload(f.guardian1(), "POST", "a.jpg", "image/jpeg", 1000, ctx).andExpect(status().isCreated());
		requestUpload(f.student1(), "POST", "a.jpg", "image/jpeg", 1000, ctx).andExpect(status().isForbidden());
		setting(f.schoolId(), "feed.student_contributions.enabled", true);
		requestUpload(f.student1(), "POST", "a.jpg", "image/jpeg", 1000, ctx).andExpect(status().isCreated());
		// never for people outside the class, restricted or revoked guardians, or another school
		for (Persona outsider : List.of(f.otherGuardian(), f.otherStudent(), f.otherTeacher(), f.restricted(), f.revoked())) {
			requestUpload(outsider, "POST", "a.jpg", "image/jpeg", 1000, ctx).andExpect(status().isForbidden());
		}
		// the school context: feed managers only
		requestUpload(f.admin(), "POST", "a.jpg", "image/jpeg", 1000, school(f.schoolId())).andExpect(status().isCreated());
		requestUpload(f.lead(), "POST", "a.jpg", "image/jpeg", 1000, school(f.schoolId())).andExpect(status().isForbidden());
		requestUpload(f.guardian1(), "POST", "a.jpg", "image/jpeg", 1000, school(f.schoolId())).andExpect(status().isForbidden());
		// the stories purpose is for staff only
		requestUpload(f.lead(), "STORY", "a.jpg", "image/jpeg", 1000, ctx).andExpect(status().isCreated());
		requestUpload(f.guardian1(), "STORY", "a.jpg", "image/jpeg", 1000, ctx).andExpect(status().isForbidden());
		// the policies endpoint lists the purposes now
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/media/policies").with(f.lead().bearer()))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.purposes[*].purpose",
					org.hamcrest.Matchers.hasItems("POST", "STORY")));
	}

	@Test
	void aModeratorCannotTouchAnotherTeachersUnattachedUploadButCanDeleteAttachedMedia() throws Exception {
		Feed f = feed("media-manage");
		UUID loose = photo(f.co(), f.classId());
		this.mvc.perform(delete("/api/v1/media/" + loose).with(f.lead().bearer())).andExpect(status().isNotFound());
		JsonNode post = publishedPost(f, f.co());
		String attached = mediaOf(post);
		this.mvc.perform(delete("/api/v1/media/" + attached).with(f.subject().bearer())).andExpect(status().isForbidden());
		this.mvc.perform(delete("/api/v1/media/" + attached).with(f.guardian1().bearer())).andExpect(status().isForbidden());
		this.mvc.perform(delete("/api/v1/media/" + attached).with(f.otherGuardian().bearer())).andExpect(status().isNotFound());
		ResultActions deleted = this.mvc.perform(delete("/api/v1/media/" + attached).with(f.lead().bearer()));
		deleted.andExpect(status().isNoContent());
		// the post forgets the file once the event is delivered
		org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(
				() -> assertThat(this.jdbc.queryForObject("select count(*) from post_media where media_id = ?", Integer.class,
						UUID.fromString(attached))).isZero());
		assertThat(get(f.api() + "/posts/" + post.get("id").asString(), f.co()).get("media")).isEmpty();
	}

	@Test
	void photosAreServedOnlyAsReencodedVariantsWithoutMetadata() throws Exception {
		Feed f = feed("media-variants");
		UUID media = UUID.fromString(uploadReady(f.lead(), "POST", "gps.jpg", "image/jpeg", MediaFixtures.jpegWithGps(800, 600, 1),
				classContext(f.classId())).get("id").asString());
		classPost(f.lead(), f, "Geo", 201, Map.of("media", refs(media), "confirmNoUnconsentedStudents", true));
		JsonNode url = this.json.readTree(this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.get("/api/v1/media/" + media + "/url?variant=MEDIUM").with(f.guardian1().bearer())).andReturn().getResponse().getContentAsString());
		byte[] bytes = download(url.get("url").asString());
		assertThat(MediaFixtures.hasGps(bytes)).isFalse();
		assertThat(MediaFixtures.hasExif(bytes)).isFalse();
	}

}

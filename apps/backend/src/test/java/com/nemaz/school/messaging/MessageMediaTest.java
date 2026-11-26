package com.nemaz.school.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Rule book R8.4: images of messages are readable by current members and observers only, while the message stands. */
@IntegrationTest
class MessageMediaTest extends MessagingTestSupport {

	private int url(Persona who, UUID media) throws Exception {
		return statusOf(HttpMethod.GET, "/api/v1/media/" + media + "/url?variant=SMALL", who, null);
	}

	private UUID sendImage(Persona who, UUID thread, UUID media) throws Exception {
		JsonNode sent = post(conv(thread) + "/messages", who, body("clientMessageId", UUID.randomUUID(), "mediaIds", List.of(media)), 201);
		assertThat(sent.get("message").get("messageType").asString()).isEqualTo("MEDIA");
		assertThat(sent.get("message").get("attachments")).hasSize(1);
		// the payload carries ids and variants, never a link
		assertThat(sent.toString()).doesNotContain("http");
		return UUID.fromString(sent.get("message").get("id").asString());
	}

	@Test
	void imageReadableByMembersAndObserversOnly() throws Exception {
		Msg m = school("media-access");
		UUID thread = teacherStudentThread(m);
		UUID media = image(m.lead(), thread);
		// before it is sent only the uploader reads it
		assertThat(url(m.lead(), media)).isEqualTo(200);
		assertThat(url(m.student1(), media)).isEqualTo(404);
		sendImage(m.lead(), thread, media);
		for (Persona who : new Persona[] { m.lead(), m.student1(), m.g1(), m.g1b() }) {
			assertThat(url(who, media)).as(who.name()).isEqualTo(200);
		}
		for (Persona who : new Persona[] { m.g2(), m.restricted(), m.revoked(), m.pending(), m.stranger(), m.otherAdmin(), m.otherTeacher(),
				m.student2(), m.co() }) {
			assertThat(url(who, media)).as(who.name()).isEqualTo(404);
		}
		// not even the school administrator: the conversation has observers, so it is not reviewable
		assertThat(url(m.admin(), media)).isEqualTo(404);
		// uploads: members who may post, nobody else
		assertThat(requestUpload(m.g1(), "MESSAGE", "p.jpg", "image/jpeg", 1000, conversationContext(thread)).andReturn().getResponse().getStatus())
			.isEqualTo(403);
		assertThat(requestUpload(m.stranger(), "MESSAGE", "p.jpg", "image/jpeg", 1000, conversationContext(thread)).andReturn().getResponse().getStatus())
			.isEqualTo(403);
		assertThat(requestUpload(m.lead(), "MESSAGE", "p.jpg", "image/jpeg", 1000, school(m.schoolId())).andReturn().getResponse().getStatus())
			.isEqualTo(400);
		// somebody else's upload cannot be attached
		UUID theirs = image(m.student1(), conversationOf(m, thread));
		assertThat(statusOf(HttpMethod.POST, conv(thread) + "/messages", m.lead(), body("clientMessageId", UUID.randomUUID(), "mediaIds", List.of(theirs))))
			.isNotEqualTo(201);
	}

	/** An upload by a student needs messaging.student.media_enabled: switch it on for the helper. */
	private UUID conversationOf(Msg m, UUID thread) {
		setting(m.schoolId(), "messaging.student.media_enabled", true);
		return thread;
	}

	@Test
	void reviewersReadTheImagesOfAnAdminVisibleConversationAndItIsAudited() throws Exception {
		Msg m = school("media-review");
		allowTeacherStudentDirect(m.schoolId(), "TEACHER_ONLY");
		UUID thread = conversationId(startDirect(m.lead(), m, m.s3(), null, 201));
		UUID media = image(m.lead(), thread);
		sendImage(m.lead(), thread, media);
		assertThat(url(m.admin(), media)).isEqualTo(200);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'media.read' and target_id = ? and actor_user_id = ?",
				Integer.class, media, m.admin().userId())).isEqualTo(1);
		assertThat(url(m.otherAdmin(), media)).isEqualTo(404);
		assertThat(url(m.stranger(), media)).isEqualTo(404);
	}

	@Test
	void deletedHiddenOrRevokedMessageImageIsNotReadable() throws Exception {
		Msg m = school("media-gone");
		UUID classThread = classConversation(m);
		setting(m.schoolId(), "messaging.student.media_enabled", true);
		UUID media = image(m.student1(), classThread);
		UUID message = sendImage(m.student1(), classThread, media);
		assertThat(url(m.student2(), media)).isEqualTo(200);
		// hidden by a moderator: nobody reads it, the author neither; shown again: all do
		call(HttpMethod.POST, conv(classThread) + "/messages/" + message + "/hide", m.lead(), body("reason", "not for school"), 204);
		for (Persona who : new Persona[] { m.student1(), m.student2(), m.lead() }) {
			assertThat(url(who, media)).as(who.name()).isEqualTo(404);
		}
		JsonNode tombstone = get(conv(classThread) + "/messages/" + message, m.student2());
		assertThat(tombstone.get("attachments")).isEmpty();
		call(HttpMethod.POST, conv(classThread) + "/messages/" + message + "/unhide", m.lead(), null, 204);
		assertThat(url(m.student2(), media)).isEqualTo(200);
		// deleted by the author: the image goes with the message
		call(HttpMethod.DELETE, conv(classThread) + "/messages/" + message, m.student1(), null, 204);
		assertThat(url(m.student2(), media)).isEqualTo(404);
		assertThat(url(m.student1(), media)).isEqualTo(404);
		// a member who loses access loses the picture they sent, too
		UUID thread = parentTeacherThread(m);
		UUID own = image(m.g1(), thread);
		sendImage(m.g1(), thread, own);
		assertThat(url(m.g1(), own)).isEqualTo(200);
		assertThat(url(m.lead(), own)).isEqualTo(200);
		restrict(m.g1Link());
		assertThat(url(m.g1(), own)).isEqualTo(404);
		assertThat(url(m.lead(), own)).isEqualTo(404);
		unrestrict(m.g1Link());
		assertThat(url(m.g1(), own)).isEqualTo(200);
		// leaving the class ends the student's access to class pictures
		UUID classMedia = image(m.lead(), classThread);
		sendImage(m.lead(), classThread, classMedia);
		assertThat(url(m.student2(), classMedia)).isEqualTo(200);
		leaveClass(m.classId(), m.s2());
		assertThat(url(m.student2(), classMedia)).isEqualTo(404);
		// a user who blocked the sender does not get the image either
		UUID chala = memberIdOf(m.student3(), classThread, m.lead());
		post("/api/v1/me/blocks", m.student3(), body("conversationId", classThread, "memberId", chala), 201);
		assertThat(url(m.student3(), classMedia)).isEqualTo(404);
		assertThat(url(m.student1(), classMedia)).isEqualTo(200);
	}

	@Test
	void neverIssuesAnOriginalSignedUrl() throws Exception {
		Msg m = school("media-original");
		UUID thread = parentTeacherThread(m);
		UUID media = image(m.g1(), thread);
		sendImage(m.g1(), thread, media);
		assertThat(code(call(HttpMethod.GET, "/api/v1/media/" + media + "/url", m.lead(), null, 400))).isEqualTo("media.variant_required");
		assertThat(code(call(HttpMethod.GET, "/api/v1/media/" + media + "/url?variant=ORIGINAL", m.lead(), null, 400))).isEqualTo("media.original_not_available");
		JsonNode signed = call(HttpMethod.GET, "/api/v1/media/" + media + "/url?variant=MEDIUM", m.lead(), null, 200);
		assertThat(signed.get("url").asString()).doesNotContain("original");
		// not even the uploader gets the original of an attached picture
		assertThat(statusOf(HttpMethod.GET, "/api/v1/media/" + media + "/url?variant=ORIGINAL", m.g1(), null)).isEqualTo(400);
		// at most four images, only images
		assertThat(code(post(conv(thread) + "/messages", m.lead(), body("clientMessageId", UUID.randomUUID(), "mediaIds",
				List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())), 400)))
			.isEqualTo("validation_failed");
		assertThat(requestUpload(m.lead(), "MESSAGE", "x.pdf", "application/pdf", 1000, conversationContext(thread)).andReturn().getResponse().getStatus())
			.isEqualTo(400);
	}

	@Test
	void studentsCannotUploadByDefault() throws Exception {
		Msg m = school("media-students");
		UUID classThread = classConversation(m);
		assertThat(requestUpload(m.student1(), "MESSAGE", "p.jpg", "image/jpeg", 1000, conversationContext(classThread)).andReturn().getResponse()
			.getStatus()).isEqualTo(403);
		UUID teacherImage = image(m.lead(), classThread);
		JsonNode sent = post(conv(classThread) + "/messages", m.lead(), body("clientMessageId", UUID.randomUUID(), "mediaIds", List.of(teacherImage)), 201);
		assertThat(url(m.student1(), UUID.fromString(sent.get("message").get("attachments").get(0).get("mediaId").asString()))).isEqualTo(200);
		setting(m.schoolId(), "messaging.student.media_enabled", true);
		assertThat(image(m.student1(), classThread)).isNotNull();
	}

}

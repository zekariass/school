package com.nemaz.school.portfolio;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Rule book P1.4 and P4.7: media follows the item (R-30), signed URLs, originals, uploads. */
@IntegrationTest
class PortfolioMediaAccessTest extends PortfolioTestSupport {

	@Autowired
	MediaService mediaService;

	private int upload(Persona who, UUID school, String mime, String name) throws Exception {
		ResultActions result = requestUpload(who, "PORTFOLIO", name, mime, 1000, school(school));
		return result.andReturn().getResponse().getStatus();
	}

	@Test
	void uploadNeedsAnActiveStudentOrTeacher() throws Exception {
		Pf p = pf("med1");
		assertThat(upload(p.student1(), p.schoolId(), "image/jpeg", "a.jpg")).isEqualTo(201);
		assertThat(upload(p.student1(), p.schoolId(), "application/pdf", "a.pdf")).isEqualTo(201);
		assertThat(upload(p.lead(), p.schoolId(), "image/jpeg", "a.jpg")).isEqualTo(201);
		assertThat(upload(p.mathsTeacher(), p.schoolId(), "image/jpeg", "a.jpg")).isEqualTo(201);
		for (Persona refused : List.of(p.guardian1(), p.admin(), p.adminB(), p.studentB(), p.withdrawnStudent())) {
			assertThat(upload(refused, p.schoolId(), "image/jpeg", "a.jpg")).as(refused.name()).isEqualTo(403);
		}
		// another school's context is not yours
		assertThat(upload(p.student1(), p.schoolB(), "image/jpeg", "a.jpg")).isEqualTo(403);
		// assistants only while they may capture
		assertThat(upload(p.assistant(), p.schoolId(), "image/jpeg", "a.jpg")).isEqualTo(403);
		setting(p.schoolId(), "portfolio.assistants_can_capture", true);
		assertThat(upload(p.assistant(), p.schoolId(), "image/jpeg", "a.jpg")).isEqualTo(201);
		// students only while the school lets them create
		setting(p.schoolId(), "portfolio.student_create.enabled", false);
		assertThat(upload(p.student1(), p.schoolId(), "image/jpeg", "a.jpg")).isEqualTo(403);
		assertThat(upload(p.lead(), p.schoolId(), "image/jpeg", "a.jpg")).isEqualTo(201);
		// only images and PDFs: no video, audio, GIF
		setting(p.schoolId(), "portfolio.student_create.enabled", true);
		for (String mime : List.of("video/mp4", "audio/mpeg", "image/gif", "text/csv")) {
			assertThat(upload(p.student1(), p.schoolId(), mime, "x")).as(mime).isEqualTo(400);
		}
	}

	@Test
	void draftFilesAreReadByTheCreatorOnly() throws Exception {
		Pf p = pf("med2");
		UUID media = photo(p.student1(), p.schoolId());
		create(p.student1(), p, photoBody("draft", List.of(media)), 201);
		assertThat(mediaUrlStatus(p.student1(), media)).isEqualTo(200);
		for (Persona other : List.of(p.lead(), p.admin(), p.guardian1(), p.student2(), p.otherTeacher(), p.adminB())) {
			assertThat(mediaUrlStatus(other, media)).as(other.name()).isEqualTo(404);
		}
	}

	@Test
	void submittedFilesAreReadByStudentAndReviewers() throws Exception {
		Pf p = pf("med3");
		UUID media = photo(p.student1(), p.schoolId());
		create(p.student1(), p, photoBody("submitted", List.of(media), "mode", "SUBMIT"), 201);
		for (Persona reader : List.of(p.student1(), p.lead(), p.co(), p.admin())) {
			assertThat(mediaUrlStatus(reader, media)).as(reader.name()).isEqualTo(200);
		}
		for (Persona other : List.of(p.guardian1(), p.student2(), p.assistant(), p.otherTeacher(), p.adminB())) {
			assertThat(mediaUrlStatus(other, media)).as(other.name()).isEqualTo(404);
		}
	}

	@Test
	void approvedFilesFollowTheAudience() throws Exception {
		Pf p = pf("med4");
		cap(p, "SCHOOL");
		grant(p.guardian1(), p.s1());
		for (String audience : List.of("STUDENT_TEACHER", "GUARDIANS", "CLASS", "SCHOOL")) {
			UUID id = approvedPhoto(p, p.student1(), "for " + audience, audience);
			UUID media = mediaOf(get(p.item(id), p.student1()));
			int r = List.of("STUDENT_TEACHER", "GUARDIANS", "CLASS", "SCHOOL").indexOf(audience);
			assertThat(mediaUrlStatus(p.student1(), media)).isEqualTo(200);
			assertThat(mediaUrlStatus(p.lead(), media)).isEqualTo(200);
			assertThat(mediaUrlStatus(p.guardian1(), media)).as(audience + " guardian").isEqualTo(r >= 1 ? 200 : 404);
			assertThat(mediaUrlStatus(p.student2(), media)).as(audience + " classmate").isEqualTo(r >= 2 ? 200 : 404);
			assertThat(mediaUrlStatus(p.otherStudent(), media)).as(audience + " other student").isEqualTo(r >= 3 ? 200 : 404);
			for (Persona nobody : List.of(p.guardian2(), p.restricted(), p.revoked(), p.pending(), p.adminB(), p.studentB(), p.withdrawnStudent())) {
				assertThat(mediaUrlStatus(nobody, media)).as(audience + " " + nobody.name()).isEqualTo(404);
			}
		}
	}

	@Test
	void archivedFilesAreForStudentAndReviewers() throws Exception {
		Pf p = pf("med5");
		UUID id = approvedPhoto(p, p.student1(), "to archive", "GUARDIANS");
		UUID media = mediaOf(get(p.item(id), p.student1()));
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(200);
		post(p.item(id) + "/archive", p.student1(), body(), 200);
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(404);
		for (Persona reader : List.of(p.student1(), p.lead(), p.admin())) {
			assertThat(mediaUrlStatus(reader, media)).as(reader.name()).isEqualTo(200);
		}
		post(p.item(id) + "/unarchive", p.lead(), body(), 200);
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(200);
	}

	@Test
	void deletedItemFilesAreNobodysAndAreDiscarded() throws Exception {
		Pf p = pf("med6");
		UUID id = approvedPhoto(p, p.student1(), "to delete", "GUARDIANS");
		UUID media = mediaOf(get(p.item(id), p.student1()));
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(200);
		call(HttpMethod.DELETE, p.item(id), p.lead(), null, 204);
		for (Persona who : List.of(p.student1(), p.lead(), p.admin(), p.guardian1())) {
			assertThat(mediaUrlStatus(who, media)).as(who.name()).isEqualTo(404);
		}
		assertThat(this.jdbc.queryForObject("select status from media where id = ?", String.class, media)).isEqualTo("DELETED");
	}

	@Test
	void withheldFilesAnswer404() throws Exception {
		Pf p = pf("med7");
		cap(p, "CLASS");
		UUID media = photo(p.student1(), p.schoolId());
		UUID id = id(create(p.student1(), p, photoBody("withheld", List.of(media), "mode", "SUBMIT", "visibility", "CLASS",
				"confirmNoUnconsentedStudents", true), 201));
		grant(p.guardian1(), p.s1());
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		assertThat(mediaUrlStatus(p.student2(), media)).isEqualTo(200);
		withdraw(p.guardian1(), p.s1());
		assertThat(mediaUrlStatus(p.student2(), media)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, "/api/v1/media/" + media, p.student2(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, "/api/v1/media/" + media, p.guardian1(), null)).isEqualTo(200);
	}

	@Test
	void imageOriginalsAreNeverSigned() throws Exception {
		Pf p = pf("med8");
		UUID media = photo(p.student1(), p.schoolId());
		UUID doc = pdf(p.student1(), p.schoolId());
		create(p.student1(), p, photoBody("pic", List.of(media)), 201);
		create(p.student1(), p, body("itemType", "DOCUMENT", "title", "doc", "mediaIds", List.of(doc)), 201);
		for (String variant : List.of("THUMB", "SMALL", "MEDIUM")) {
			JsonNode signed = get("/api/v1/media/" + media + "/url?variant=" + variant, p.student1());
			assertThat(signed.get("url").asString()).startsWith("http");
		}
		assertThat(code(call(HttpMethod.GET, "/api/v1/media/" + media + "/url?variant=ORIGINAL", p.student1(), null, 400))).isEqualTo("media.original_not_available");
		assertThat(code(call(HttpMethod.GET, "/api/v1/media/" + media + "/url", p.student1(), null, 400))).isEqualTo("media.variant_required");
		// the uploader is not special once the file is attached: the same rule for everybody
		for (Persona who : List.of(p.lead(), p.admin())) {
			assertThat(statusOf(HttpMethod.GET, "/api/v1/media/" + media + "/url?variant=ORIGINAL", who, null)).isEqualTo(404);
		}
		// a PDF has no image original: it is served as the file itself, with its preview as a variant
		assertThat(get("/api/v1/media/" + doc + "/url", p.student1()).get("url").asString()).startsWith("http");
	}

	@Test
	void unattachedUploadIsReadableByItsUploaderOnly() throws Exception {
		Pf p = pf("med9");
		UUID media = photo(p.student1(), p.schoolId());
		assertThat(mediaUrlStatus(p.student1(), media)).isEqualTo(200);
		for (Persona other : List.of(p.lead(), p.admin(), p.student2(), p.guardian1(), p.adminB())) {
			assertThat(mediaUrlStatus(other, media)).as(other.name()).isEqualTo(404);
		}
		// the uploader may discard it
		call(HttpMethod.DELETE, "/api/v1/media/" + media, p.student1(), null, 204);
		assertThat(mediaUrlStatus(p.student1(), media)).isEqualTo(404);
		// a teacher's upload for quick capture is the teacher's alone until the item exists
		UUID teacherMedia = photo(p.lead(), p.schoolId());
		assertThat(mediaUrlStatus(p.lead(), teacherMedia)).isEqualTo(200);
		assertThat(mediaUrlStatus(p.student1(), teacherMedia)).isEqualTo(404);
	}

	@Test
	void mediaDeletedClearsTheAttachment() throws Exception {
		Pf p = pf("med10");
		UUID media = photo(p.student1(), p.schoolId());
		UUID id = id(create(p.student1(), p, photoBody("attached", List.of(media)), 201));
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_attachments where item_id = ?", Integer.class, id)).isEqualTo(1);
		// an attached file cannot be deleted through the media API, not even by its uploader: it goes with its item
		assertThat(statusOf(HttpMethod.DELETE, "/api/v1/media/" + media, p.student1(), null)).isEqualTo(403);
		assertThat(statusOf(HttpMethod.DELETE, "/api/v1/media/" + media, p.lead(), null)).isEqualTo(404);
		this.tx.executeWithoutResult((s) -> this.mediaService.discard(media));
		await().atMost(Duration.ofSeconds(20))
			.untilAsserted(() -> assertThat(this.jdbc.queryForObject("select count(*) from portfolio_attachments where item_id = ?", Integer.class, id)).isZero());
		assertThat(get(p.item(id), p.student1()).get("media").size()).isZero();
	}

}

package com.nemaz.school.media;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.media.application.MediaMaintenance;
import com.nemaz.school.media.domain.ObjectStorage;
import com.nemaz.school.support.MediaFixtures;
import com.nemaz.school.support.MediaTestSupport;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas.Fixture;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The upload pipeline against real PostgreSQL and a real S3 server (SeaweedFS container):
 * validation, presigned PUT, completion checks, processing, signed reads and clean-up.
 */
@IntegrationTest
class MediaUploadApiTest extends MediaTestSupport {

	@Autowired
	ObjectStorage storage;

	@Autowired
	MediaMaintenance maintenance;

	// --- validation -------------------------------------------------------------------

	@Test
	void unsupportedPurposesAreRefusedUntilTheirMilestone() throws Exception {
		Persona user = this.personas.newUser("purposes");
		for (String purpose : List.of("PORTFOLIO", "SUBMISSION", "LIBRARY", "COURSE", "RECORDING",
				"VERIFICATION_DOC", "REPORT")) {
			requestUpload(user, purpose, "a.jpg", "image/jpeg", 100, school(this.personas.fixture().schoolA()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("media.purpose_not_supported"));
		}
	}

	@Test
	void typeAndSizeLimitsApplyPerPurpose() throws Exception {
		Persona user = this.personas.newUser("limits");
		requestUpload(user, "AVATAR", "a.pdf", "application/pdf", 100, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.type_not_allowed"));
		requestUpload(user, "AVATAR", "a.gif", "image/gif", 100, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.type_not_allowed"));
		requestUpload(user, "AVATAR", "a.mp4", "video/mp4", 100, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.type_not_allowed"));
		requestUpload(user, "AVATAR", "a.jpg", "image/jpeg", 0, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.empty"));
		requestUpload(user, "AVATAR", "a.jpg", "image/jpeg", 10L * 1024 * 1024 + 1, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.too_large"))
			.andExpect(jsonPath("$.detail", containsString("10 MB")));
		requestUpload(user, "AVATAR", "a.jpg", "image/jpeg", 10L * 1024 * 1024, null).andExpect(status().isCreated());
		// MIME parameters and case are normalized
		requestUpload(user, "AVATAR", "a.jpg", "Image/JPEG; charset=binary", 100, null).andExpect(status().isCreated());
		requestUpload(user, "AVATAR", "a.jpg", "image/jpeg", 100, school(this.personas.fixture().schoolA()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.context_invalid"));
		requestUpload(user, "BRANDING", "a.jpg", "image/jpeg", 100, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.context_required"));
		this.mvc.perform(post("/api/v1/media/uploads").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"purpose\":\"AVATAR\",\"filename\":\"a.jpg\",\"mimeType\":\"image/jpeg\",\"sizeBytes\":10,\"sha256\":\"xyz\"}"))
			.andExpect(status().isBadRequest());
		this.mvc.perform(post("/api/v1/media/uploads").with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"purpose\":\"NOT_A_PURPOSE\",\"filename\":\"a.jpg\",\"mimeType\":\"image/jpeg\",\"sizeBytes\":10}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void messagesAreLocalized() throws Exception {
		Persona user = this.personas.newUser("localized");
		this.mvc.perform(post("/api/v1/media/uploads").with(user.bearer()).header("Accept-Language", "am")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"purpose\":\"AVATAR\",\"filename\":\"a.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":10}"))
			.andExpect(jsonPath("$.code").value("media.type_not_allowed"))
			.andExpect(jsonPath("$.detail").value("ይህ የፋይል ዓይነት እዚህ አይፈቀድም።"));
	}

	@Test
	void uploadRequestsNeedASignIn() throws Exception {
		this.mvc.perform(post("/api/v1/media/uploads").contentType(MediaType.APPLICATION_JSON)
			.content("{\"purpose\":\"AVATAR\"}")).andExpect(status().isUnauthorized());
	}

	// --- the happy path ---------------------------------------------------------------

	@Test
	void anAvatarGoesFromPresignedPutToReadyWithMetadataFreeVariants() throws Exception {
		Persona user = this.personas.newUser("avatar-flow");
		byte[] original = MediaFixtures.jpegWithGps(2400, 1600, 1);
		assertThat(MediaFixtures.hasGps(original)).isTrue();

		JsonNode ticket = newUpload(user, "AVATAR", "../../My Photo.JPG", "image/jpeg", original, null);
		String mediaId = ticket.get("mediaId").asString();
		JsonNode upload = ticket.get("upload");
		assertThat(upload.get("method").asString()).isEqualTo("PUT");
		assertThat(upload.get("headers").get("Content-Type").asString()).isEqualTo("image/jpeg");
		assertThat(upload.get("url").asString()).contains("/school-media-test/users/" + user.userId() + "/avatar/" + mediaId + "/original");
		assertThat(upload.get("url").asString()).contains("X-Amz-Expires=600").contains("content-type");

		// pending media is visible to its uploader only, and cannot be read yet
		JsonNode pending = body(getMedia(user, mediaId).andExpect(status().isOk()));
		assertThat(pending.get("status").asString()).isEqualTo("PENDING_UPLOAD");
		this.mvc.perform(get("/api/v1/media/" + mediaId + "/url").param("variant", "SMALL").with(user.bearer()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("media.not_ready"));
		// completing before the bytes arrived
		complete(user, mediaId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("media.not_uploaded"));

		assertThat(putToStorage(upload, original, "image/jpeg")).isEqualTo(200);
		complete(user, mediaId).andExpect(status().isOk());
		JsonNode ready = awaitStatus(user, mediaId, "READY");

		assertThat(ready.get("kind").asString()).isEqualTo("IMAGE");
		assertThat(ready.get("width").asInt()).isEqualTo(2400);
		assertThat(ready.get("height").asInt()).isEqualTo(1600);
		assertThat(ready.get("variants")).extracting(JsonNode::asString).containsExactly("THUMB", "SMALL", "MEDIUM");
		// completing again is harmless
		complete(user, mediaId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
		assertThat(this.jdbc.queryForObject("select original_filename from media where id = ?::uuid", String.class, mediaId))
			.isEqualTo("My Photo.JPG");

		for (String variant : List.of("THUMB", "SMALL", "MEDIUM")) {
			var response = this.mvc.perform(get("/api/v1/media/" + mediaId + "/url").param("variant", variant).with(user.bearer()))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "max-age=240, private"))
				.andReturn()
				.getResponse();
			JsonNode url = this.json.readTree(response.getContentAsString());
			assertThat(url.get("variant").asString()).isEqualTo(variant);
			byte[] bytes = download(url.get("url").asString());
			assertThat(MediaFixtures.hasExif(bytes)).as(variant + " EXIF").isFalse();
			assertThat(MediaFixtures.hasGps(bytes)).as(variant + " GPS").isFalse();
			assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("SecretCamera");
			assertThat(MediaFixtures.decode(bytes).getWidth()).isEqualTo(switch (variant) {
				case "THUMB" -> 256;
				case "SMALL" -> 640;
				default -> 1280;
			});
		}
		// the original, with its GPS data, stays in storage but is never handed out
		assertThat(this.storage.head("users/" + user.userId() + "/avatar/" + mediaId + "/original")).isPresent();
		this.mvc.perform(get("/api/v1/media/" + mediaId + "/url").param("variant", "ORIGINAL").with(user.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.original_not_available"));
		this.mvc.perform(get("/api/v1/media/" + mediaId + "/url").with(user.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.variant_required"));
		this.mvc.perform(get("/api/v1/media/" + mediaId + "/url").param("variant", "PDF_PREVIEW").with(user.bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("media.variant_not_found"));
		this.mvc.perform(get("/api/v1/media/" + mediaId + "/url").param("variant", "HUGE").with(user.bearer()))
			.andExpect(status().isBadRequest());
	}

	@Test
	void signedLinksExpireAndCannotBeChangedToAnotherObject() throws Exception {
		Persona user = this.personas.newUser("links");
		JsonNode media = uploadReady(user, "AVATAR", "a.png", "image/png", MediaFixtures.png(300, 300, false), null);
		String id = media.get("id").asString();
		JsonNode url = body(this.mvc.perform(get("/api/v1/media/" + id + "/url").param("variant", "THUMB").with(user.bearer()))
			.andExpect(status().isOk()));
		String link = url.get("url").asString();
		assertThat(link).contains("X-Amz-Expires=300");

		// a different key under the same signature is refused
		String tampered = link.replace("/thumb", "/original");
		assertThat(fetch(tampered).statusCode()).isEqualTo(403);
		// without any signature the bucket is private
		assertThat(fetch(link.substring(0, link.indexOf('?'))).statusCode()).isIn(401, 403);
		assertThat(fetch(link).statusCode()).isEqualTo(200);
	}

	@Test
	void imagesThatCannotBeDecodedEndFailedAndAreNotReadable() throws Exception {
		Persona user = this.personas.newUser("broken-image");
		// starts like a JPEG (passes the magic-byte check) but is not decodable
		byte[] broken = new byte[2048];
		broken[0] = (byte) 0xFF;
		broken[1] = (byte) 0xD8;
		broken[2] = (byte) 0xFF;
		JsonNode ticket = newUpload(user, "AVATAR", "x.jpg", "image/jpeg", broken, null);
		String id = ticket.get("mediaId").asString();
		assertThat(putToStorage(ticket.get("upload"), broken, "image/jpeg")).isEqualTo(200);
		complete(user, id).andExpect(status().isOk());

		JsonNode failed = awaitStatus(user, id, "FAILED");
		assertThat(failed.get("failureReason").asString()).isEqualTo("unreadable_image");
		this.mvc.perform(get("/api/v1/media/" + id + "/url").param("variant", "SMALL").with(user.bearer()))
			.andExpect(status().isConflict());
	}

	@Test
	void aDeclaredChecksumIsVerifiedDuringProcessing() throws Exception {
		Persona user = this.personas.newUser("checksum");
		byte[] content = MediaFixtures.jpeg(200, 200);
		String right = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
		String wrong = "0".repeat(64);
		for (String sha : List.of(right, wrong)) {
			JsonNode ticket = body(this.mvc.perform(post("/api/v1/media/uploads").with(user.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(this.json.writeValueAsString(Map.of("purpose", "AVATAR", "filename", "a.jpg", "mimeType", "image/jpeg",
						"sizeBytes", content.length, "sha256", sha))))
				.andExpect(status().isCreated()));
			putToStorage(ticket.get("upload"), content, "image/jpeg");
			String id = ticket.get("mediaId").asString();
			complete(user, id).andExpect(status().isOk());
			if (sha.equals(right)) {
				awaitStatus(user, id, "READY");
			}
			else {
				assertThat(awaitStatus(user, id, "FAILED").get("failureReason").asString()).isEqualTo("checksum_mismatch");
			}
		}
	}

	// --- completion checks ------------------------------------------------------------

	@Test
	void contentThatIsNotWhatWasDeclaredIsQuarantinedAndDeleted() throws Exception {
		Persona user = this.personas.newUser("quarantine");
		byte[] pdf = MediaFixtures.pdf(1);
		// declared as a JPEG, but the bytes are a PDF
		JsonNode ticket = newUpload(user, "AVATAR", "innocent.jpg", "image/jpeg", pdf, null);
		String id = ticket.get("mediaId").asString();
		String key = "users/" + user.userId() + "/avatar/" + id + "/original";
		assertThat(putToStorage(ticket.get("upload"), pdf, "image/jpeg")).isEqualTo(200);
		assertThat(this.storage.head(key)).isPresent();

		complete(user, id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("media.type_mismatch"));

		assertThat(this.storage.head(key)).as("the object is deleted").isEmpty();
		JsonNode view = body(getMedia(user, id).andExpect(status().isOk()));
		assertThat(view.get("status").asString()).isEqualTo("QUARANTINED");
		assertThat(view.get("failureReason").asString()).isEqualTo("content_mismatch");
		assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_logs where action = 'media.quarantine' and target_id = ?::uuid", Integer.class, id))
			.isEqualTo(1);
		// it cannot be completed again, nor read
		complete(user, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("media.invalid_state"));
		this.mvc.perform(get("/api/v1/media/" + id + "/url").param("variant", "SMALL").with(user.bearer()))
			.andExpect(status().isConflict());
	}

	@Test
	void anExecutableRenamedToJpgIsQuarantined() throws Exception {
		Persona user = this.personas.newUser("exe");
		byte[] exe = new byte[4096];
		exe[0] = 'M';
		exe[1] = 'Z';
		JsonNode ticket = newUpload(user, "AVATAR", "cute.jpg", "image/jpeg", exe, null);
		putToStorage(ticket.get("upload"), exe, "image/jpeg");
		complete(user, ticket.get("mediaId").asString()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.type_mismatch"));
	}

	@Test
	void aDifferentSizeThanDeclaredIsQuarantined() throws Exception {
		Persona user = this.personas.newUser("size");
		byte[] announced = MediaFixtures.jpeg(300, 300);
		// the presigned URL is signed for the announced length: a longer body is refused by the store ...
		JsonNode ticket = newUpload(user, "AVATAR", "a.jpg", "image/jpeg", announced, null);
		byte[] longer = java.util.Arrays.copyOf(announced, announced.length + 100);
		assertThat(putToStorage(ticket.get("upload"), longer, "image/jpeg")).isIn(400, 403);
		complete(user, ticket.get("mediaId").asString()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("media.not_uploaded"));

		// ... and if an object of another size ends up there anyway, completion catches it.
		JsonNode second = newUpload(user, "AVATAR", "b.jpg", "image/jpeg", announced, null);
		String id = second.get("mediaId").asString();
		String key = "users/" + user.userId() + "/avatar/" + id + "/original";
		this.storage.put(key, java.util.Arrays.copyOf(announced, announced.length - 10), "image/jpeg");
		complete(user, id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("media.size_mismatch"));
		assertThat(this.storage.head(key)).isEmpty();
		assertThat(body(getMedia(user, id)).get("status").asString()).isEqualTo("QUARANTINED");
	}

	@Test
	void aDifferentContentTypeThanSignedIsRefusedByTheStore() throws Exception {
		Persona user = this.personas.newUser("ctype");
		byte[] jpeg = MediaFixtures.jpeg(300, 300);
		JsonNode ticket = newUpload(user, "AVATAR", "a.jpg", "image/jpeg", jpeg, null);
		assertThat(putToStorage(ticket.get("upload"), jpeg, "text/html")).isIn(400, 403);
	}

	@Test
	void onlyTheUploaderCanCompleteOrSeePendingMedia() throws Exception {
		Persona uploader = this.personas.newUser("owner");
		Persona other = this.personas.newUser("other");
		byte[] jpeg = MediaFixtures.jpeg(100, 100);
		JsonNode ticket = newUpload(uploader, "AVATAR", "a.jpg", "image/jpeg", jpeg, null);
		String id = ticket.get("mediaId").asString();
		putToStorage(ticket.get("upload"), jpeg, "image/jpeg");

		complete(other, id).andExpect(status().isNotFound());
		getMedia(other, id).andExpect(status().isNotFound());
		complete(uploader, id).andExpect(status().isOk());
	}

	@Test
	void idempotentUploadRequestsReplayTheSameTicket() throws Exception {
		Persona user = this.personas.newUser("idem");
		String body = this.json.writeValueAsString(Map.of("purpose", "AVATAR", "filename", "a.jpg", "mimeType", "image/jpeg",
				"sizeBytes", 1234));
		String key = UUID.randomUUID().toString();
		String first = this.mvc.perform(post("/api/v1/media/uploads").with(user.bearer()).header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn()
			.getResponse().getContentAsString();
		this.mvc.perform(post("/api/v1/media/uploads").with(user.bearer()).header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(jsonPath("$.mediaId").value(this.json.readTree(first).get("mediaId").asString()));
		assertThat(this.jdbc.queryForObject("select count(*) from media where owner_id = ?", Integer.class, user.userId()))
			.isEqualTo(1);
	}

	// --- documents and videos ---------------------------------------------------------

	@Test
	void aPdfGetsAPreviewAndAPageCountAndItsOriginalIsAnAttachment() throws Exception {
		Persona admin = this.personas.get(Name.SCHOOL_ADMIN_A);
		Fixture f = this.personas.fixture();
		// no document purpose has a policy yet, so exercise the processor through the service level:
		// register the media directly in storage as a completed upload.
		byte[] pdf = MediaFixtures.pdf(4);
		UUID id = insertUploaded(admin, f, "application/pdf", "DOCUMENT", pdf, "report.pdf");
		publishUploaded(id);

		await(id, "READY");
		assertThat(this.jdbc.queryForObject("select page_count from media where id = ?", Integer.class, id)).isEqualTo(4);
		String previewKey = this.jdbc.queryForObject(
				"select object_key from media_variants where media_id = ? and variant = 'PDF_PREVIEW'", String.class, id);
		byte[] preview = this.storage.get(previewKey).readAllBytes();
		assertThat(MediaFixtures.decode(preview).getHeight()).isBetween(1279, 1281);
	}

	@Test
	void videosAreReadyWithoutVariants() throws Exception {
		Persona admin = this.personas.get(Name.SCHOOL_ADMIN_A);
		Fixture f = this.personas.fixture();
		UUID id = insertUploaded(admin, f, "video/mp4", "VIDEO", MediaFixtures.mp4(4096), "clip.mp4");
		publishUploaded(id);

		await(id, "READY");
		assertThat(this.jdbc.queryForObject("select count(*) from media_variants where media_id = ?", Integer.class, id))
			.isZero();
	}

	// --- maintenance and deletion -----------------------------------------------------

	@Test
	void staleUnfinishedUploadsAreRemovedWithTheirObjects() throws Exception {
		Persona user = this.personas.newUser("stale");
		byte[] jpeg = MediaFixtures.jpeg(100, 100);
		JsonNode stale = newUpload(user, "AVATAR", "a.jpg", "image/jpeg", jpeg, null);
		JsonNode fresh = newUpload(user, "AVATAR", "b.jpg", "image/jpeg", jpeg, null);
		String staleId = stale.get("mediaId").asString();
		String staleKey = "users/" + user.userId() + "/avatar/" + staleId + "/original";
		putToStorage(stale.get("upload"), jpeg, "image/jpeg");
		assertThat(this.storage.head(staleKey)).isPresent();
		this.jdbc.update("update media set created_at = now() - interval '25 hours' where id = ?::uuid", staleId);

		assertThat(this.maintenance.purgeStaleUploads()).isEqualTo(1);

		assertThat(this.storage.head(staleKey)).isEmpty();
		assertThat(this.jdbc.queryForObject("select count(*) from media where id = ?::uuid", Integer.class, staleId)).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from media where id = ?::uuid", Integer.class,
				fresh.get("mediaId").asString())).isEqualTo(1);
	}

	@Test
	void deletingMediaRemovesItsObjectsAndVariants() throws Exception {
		Persona user = this.personas.newUser("deleter");
		JsonNode media = uploadReady(user, "AVATAR", "a.jpg", "image/jpeg", MediaFixtures.jpeg(500, 500), null);
		String id = media.get("id").asString();
		String base = "users/" + user.userId() + "/avatar/" + id + "/";
		assertThat(this.storage.head(base + "thumb")).isPresent();

		Persona other = this.personas.newUser("not-the-owner");
		this.mvc.perform(delete("/api/v1/media/" + id).with(other.bearer())).andExpect(status().isNotFound());
		this.mvc.perform(delete("/api/v1/media/" + id).with(user.bearer())).andExpect(status().isNoContent());

		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			assertThat(this.storage.head(base + "original")).isEmpty();
			assertThat(this.storage.head(base + "thumb")).isEmpty();
			assertThat(this.storage.head(base + "medium")).isEmpty();
		});
		getMedia(user, id).andExpect(status().isNotFound());
		this.mvc.perform(delete("/api/v1/media/" + id).with(user.bearer())).andExpect(status().isNotFound());
		assertThat(this.jdbc.queryForObject("select status from media where id = ?::uuid", String.class, id)).isEqualTo("DELETED");
	}

	@Test
	void policiesListOnlyPurposesWithAPolicyAndTheirLimits() throws Exception {
		Persona user = this.personas.newUser("policies");
		this.mvc.perform(get("/api/v1/media/policies").with(user.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.purposes[*].purpose", hasItem("AVATAR")))
			.andExpect(jsonPath("$.purposes[*].purpose", hasItem("BRANDING")))
			.andExpect(jsonPath("$.purposes[*].purpose", hasItem("MESSAGE")))
			.andExpect(jsonPath("$.purposes[*].purpose", not(hasItem("PORTFOLIO"))))
			.andExpect(jsonPath("$.purposes[?(@.purpose=='AVATAR')].maxBytesByMimeType['image/jpeg']").value(10485760));
		this.mvc.perform(get("/api/v1/media/policies")).andExpect(status().isUnauthorized());
	}

	// --- helpers ----------------------------------------------------------------------

	/** An UPLOADED media row with its object in storage (what complete leaves behind). */
	private UUID insertUploaded(Persona owner, Fixture f, String mimeType, String kind, byte[] content, String filename) {
		UUID id = com.nemaz.school.common.id.Ids.newId();
		String key = "schools/" + f.schoolA() + "/library/" + id + "/original";
		this.storage.put(key, content, mimeType);
		this.jdbc.update("""
				insert into media (id, owner_id, school_id, purpose, context_type, context_id, bucket, object_key,
				                   original_filename, mime_type, media_kind, size_bytes, status, uploaded_at)
				values (?, ?, ?, 'LIBRARY', 'SCHOOL', ?, 'school-media-test', ?, ?, ?, ?, ?, 'UPLOADED', now())""", id,
				owner.userId(), f.schoolA(), f.schoolA(), key, filename, mimeType, kind, (long) content.length);
		return id;
	}

	@Autowired
	org.springframework.context.ApplicationEventPublisher events;

	private void publishUploaded(UUID id) {
		this.tx.executeWithoutResult((status) -> this.events.publishEvent(new MediaUploaded(id)));
	}

	private void await(UUID id, String status) {
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
				this.jdbc.queryForObject("select status from media where id = ?", String.class, id)).isEqualTo(status));
	}

}

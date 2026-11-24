package com.nemaz.school.media;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.security.AccessScope;
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
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Avatars, school logos and covers, and who may upload and read them (R-30: access follows the owning object). */
@IntegrationTest
class MediaAttachApiTest extends MediaTestSupport {

	@Autowired
	ObjectStorage storage;

	// --- avatars ----------------------------------------------------------------------

	@Test
	void aUserSetsReplacesAndClearsTheirAvatar() throws Exception {
		Persona me = this.personas.newUser("avatar-user");
		JsonNode first = uploadReady(me, "AVATAR", "me.jpg", "image/jpeg", MediaFixtures.jpegWithGps(900, 900, 1), null);
		String firstId = first.get("id").asString();

		this.mvc.perform(put("/api/v1/me/avatar").with(me.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"mediaId\":\"" + firstId + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.avatar.id").value(firstId))
			.andExpect(jsonPath("$.avatar.kind").value("IMAGE"))
			.andExpect(jsonPath("$.avatar.width").value(900))
			.andExpect(jsonPath("$.avatar.variants[0]").value("THUMB"));
		this.mvc.perform(get("/api/v1/me").with(me.bearer())).andExpect(jsonPath("$.avatar.id").value(firstId));
		assertThat(audits("identity.avatar.set", me)).isEqualTo(1);

		// a second avatar replaces the first, whose file is deleted
		JsonNode second = uploadReady(me, "AVATAR", "me2.png", "image/png", MediaFixtures.png(400, 400, false), null);
		String secondId = second.get("id").asString();
		this.mvc.perform(put("/api/v1/me/avatar").with(me.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"mediaId\":\"" + secondId + "\"}"))
			.andExpect(jsonPath("$.avatar.id").value(secondId));
		assertThat(statusOf(firstId)).isEqualTo("DELETED");
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
				this.storage.head("users/" + me.userId() + "/avatar/" + firstId + "/original")).isEmpty());

		this.mvc.perform(delete("/api/v1/me/avatar").with(me.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.avatar").doesNotExist());
		assertThat(statusOf(secondId)).isEqualTo("DELETED");
		assertThat(audits("identity.avatar.clear", me)).isEqualTo(1);
		// clearing again is a no-op
		this.mvc.perform(delete("/api/v1/me/avatar").with(me.bearer())).andExpect(status().isOk());
		assertThat(audits("identity.avatar.clear", me)).isEqualTo(1);
	}

	@Test
	void anAvatarMustBeMyOwnReadyAvatarUpload() throws Exception {
		Persona me = this.personas.newUser("avatar-rules");
		Persona other = this.personas.newUser("avatar-thief");
		JsonNode mine = uploadReady(me, "AVATAR", "me.jpg", "image/jpeg", MediaFixtures.jpeg(200, 200), null);

		// somebody else's upload is invisible to me
		setAvatar(other, mine.get("id").asString()).andExpect(status().isNotFound());
		// unknown id
		setAvatar(me, UUID.randomUUID().toString()).andExpect(status().isNotFound());
		// not READY yet
		JsonNode pending = newUpload(me, "AVATAR", "p.jpg", "image/jpeg", MediaFixtures.jpeg(100, 100), null);
		setAvatar(me, pending.get("mediaId").asString()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("media.not_ready"));
		// media of another purpose
		SchoolAdmin school = newSchoolWithAdmin();
		JsonNode logo = uploadReady(school.admin(), "BRANDING", "logo.png", "image/png", MediaFixtures.png(100, 100, false),
				school(school.id()));
		this.mvc.perform(put("/api/v1/me/avatar").with(school.admin().bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"mediaId\":\"" + logo.get("id").asString() + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.purpose_mismatch"));
		this.mvc.perform(put("/api/v1/me/avatar").with(me.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void avatarsAreVisibleToTheUserTheirSchoolsAndPlatformStaffOnly() throws Exception {
		Fixture f = this.personas.fixture();
		// a campus administrator of school A (the closest thing to a school member before memberships exist)
		Persona owner = this.personas.withRole("avatar-owner", "CAMPUS_ADMIN", AccessScope.campus(f.schoolA(), f.campusA()));
		JsonNode media = uploadReady(owner, "AVATAR", "o.jpg", "image/jpeg", MediaFixtures.jpeg(300, 300), null);
		String id = media.get("id").asString();
		setAvatar(owner, id).andExpect(status().isOk());

		for (Name name : Name.values()) {
			if (name == Name.SUSPENDED_USER) {
				continue;
			}
			boolean allowed = switch (name) {
				case PLATFORM_ADMIN, COUNTRY_ADMIN_ET, ORG_ADMIN, SCHOOL_ADMIN_A, CAMPUS_ADMIN_A -> true;
				default -> false;
			};
			Persona viewer = this.personas.get(name);
			url(viewer, id).andExpect(status().is(allowed ? 200 : 404));
			getMedia(viewer, id).andExpect(status().is(allowed ? 200 : 404));
		}
		url(owner, id).andExpect(status().isOk());
		url(this.personas.newUser("stranger"), id).andExpect(status().isNotFound());

		// somebody else cannot delete it; a school administrator is not its owner either
		this.mvc.perform(delete("/api/v1/media/" + id).with(this.personas.get(Name.SCHOOL_ADMIN_A).bearer()))
			.andExpect(status().isForbidden());
		this.mvc.perform(delete("/api/v1/media/" + id).with(this.personas.get(Name.PLAIN_USER).bearer()))
			.andExpect(status().isNotFound());
	}

	@Test
	void deletingTheMediaClearsTheAvatarThroughTheEvent() throws Exception {
		Persona me = this.personas.newUser("avatar-deleted");
		String id = uploadReady(me, "AVATAR", "me.jpg", "image/jpeg", MediaFixtures.jpeg(200, 200), null).get("id").asString();
		setAvatar(me, id).andExpect(status().isOk());

		this.mvc.perform(delete("/api/v1/media/" + id).with(me.bearer())).andExpect(status().isNoContent());

		// the summary disappears at once (DELETED media is not READY) and the reference is cleared by the listener
		this.mvc.perform(get("/api/v1/me").with(me.bearer())).andExpect(jsonPath("$.avatar").doesNotExist());
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
				this.jdbc.queryForObject("select avatar_media_id from users where id = ?", UUID.class, me.userId())).isNull());
	}

	@Test
	void youCannotUploadAvatarsForSomeoneElse() throws Exception {
		Persona me = this.personas.newUser("avatar-self-only");
		Persona other = this.personas.newUser("avatar-victim");
		requestUpload(me, "AVATAR", "a.jpg", "image/jpeg", 100, Map.of("type", "USER", "id", other.userId().toString()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("media.forbidden"));
		requestUpload(me, "AVATAR", "a.jpg", "image/jpeg", 100, Map.of("type", "USER", "id", me.userId().toString()))
			.andExpect(status().isCreated());
	}

	// --- branding ---------------------------------------------------------------------

	@Test
	void schoolAdministratorsUploadBrandingForTheirOwnSchoolOnly() throws Exception {
		Fixture f = this.personas.fixture();
		Persona adminA = this.personas.get(Name.SCHOOL_ADMIN_A);
		Persona adminB = this.personas.get(Name.SCHOOL_ADMIN_B);
		Persona campusAdmin = this.personas.get(Name.CAMPUS_ADMIN_A);
		Persona plain = this.personas.plainUser();

		requestUpload(adminA, "BRANDING", "l.png", "image/png", 100, school(f.schoolA())).andExpect(status().isCreated());
		requestUpload(this.personas.platformAdmin(), "BRANDING", "l.png", "image/png", 100, school(f.schoolA()))
			.andExpect(status().isCreated());
		requestUpload(this.personas.get(Name.COUNTRY_ADMIN_ET), "BRANDING", "l.png", "image/png", 100, school(f.schoolA()))
			.andExpect(status().isCreated());
		requestUpload(adminB, "BRANDING", "l.png", "image/png", 100, school(f.schoolA())).andExpect(status().isForbidden());
		requestUpload(campusAdmin, "BRANDING", "l.png", "image/png", 100, school(f.schoolA())).andExpect(status().isForbidden());
		requestUpload(plain, "BRANDING", "l.png", "image/png", 100, school(f.schoolA())).andExpect(status().isForbidden());
		requestUpload(this.personas.get(Name.COUNTRY_ADMIN_KE), "BRANDING", "l.png", "image/png", 100, school(f.schoolA()))
			.andExpect(status().isForbidden());
		// a school that does not exist is not revealed: same 403, no row
		requestUpload(this.personas.platformAdmin(), "BRANDING", "l.png", "image/png", 100, school(UUID.randomUUID()))
			.andExpect(status().isForbidden());
		requestUpload(adminA, "BRANDING", "l.png", "image/png", 100, Map.of("type", "USER", "id", adminA.userId().toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.context_invalid"));
	}

	@Test
	void schoolLogoAndCoverAreSetShownReadByAnyoneSignedInAndCleared() throws Exception {
		SchoolAdmin school = newSchoolWithAdmin();
		Persona admin = school.admin();
		String logoId = uploadReady(admin, "BRANDING", "logo.png", "image/png", MediaFixtures.png(512, 512, true), school(school.id()))
			.get("id").asString();
		String coverId = uploadReady(admin, "BRANDING", "cover.jpg", "image/jpeg", MediaFixtures.jpegWithGps(1600, 600, 1),
				school(school.id())).get("id").asString();

		this.mvc.perform(put("/api/v1/platform/schools/" + school.id() + "/logo").with(admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"" + logoId + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.logo.id").value(logoId))
			.andExpect(jsonPath("$.cover").doesNotExist());
		this.mvc.perform(put("/api/v1/platform/schools/" + school.id() + "/cover").with(admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"" + coverId + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.logo.id").value(logoId))
			.andExpect(jsonPath("$.cover.id").value(coverId));
		this.mvc.perform(get("/api/v1/platform/schools/" + school.id()).with(admin.bearer()))
			.andExpect(jsonPath("$.logo.id").value(logoId))
			.andExpect(jsonPath("$.cover.id").value(coverId));
		this.mvc.perform(get("/api/v1/platform/schools").with(this.personas.platformAdmin().bearer()).param("limit", "50"))
			.andExpect(status().isOk());
		assertThat(audits("school.logo.set", admin)).isEqualTo(1);
		assertThat(audits("school.cover.set", admin)).isEqualTo(1);

		// the by-slug lookup used by the school workspace carries the logo
		String slug = this.jdbc.queryForObject("select slug from schools where id = ?", String.class, school.id());
		this.mvc.perform(get("/api/v1/schools/by-slug/" + slug).with(admin.bearer()))
			.andExpect(jsonPath("$.logo.id").value(logoId));

		// any signed-in user may fetch the logo (it is shown in headers), nobody else
		Persona stranger = this.personas.newUser("logo-viewer");
		url(stranger, logoId).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/media/" + logoId + "/url").param("variant", "SMALL")).andExpect(status().isUnauthorized());
		// ... but only the school's managers delete or replace it
		this.mvc.perform(delete("/api/v1/media/" + logoId).with(stranger.bearer())).andExpect(status().isForbidden());

		// replacing the logo deletes the previous file
		String newLogo = uploadReady(admin, "BRANDING", "logo2.png", "image/png", MediaFixtures.png(300, 300, false),
				school(school.id())).get("id").asString();
		this.mvc.perform(put("/api/v1/platform/schools/" + school.id() + "/logo").with(admin.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"" + newLogo + "\"}"))
			.andExpect(jsonPath("$.logo.id").value(newLogo));
		assertThat(statusOf(logoId)).isEqualTo("DELETED");

		this.mvc.perform(delete("/api/v1/platform/schools/" + school.id() + "/cover").with(admin.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cover").doesNotExist());
		assertThat(statusOf(coverId)).isEqualTo("DELETED");
	}

	@Test
	void brandingMediaMustBeForTheSchoolItIsSetOn() throws Exception {
		SchoolAdmin one = newSchoolWithAdmin();
		SchoolAdmin two = newSchoolWithAdmin();
		String forOne = uploadReady(one.admin(), "BRANDING", "l.png", "image/png", MediaFixtures.png(100, 100, false), school(one.id()))
			.get("id").asString();

		// the platform administrator manages both, but the file was made for school one
		this.mvc.perform(put("/api/v1/platform/schools/" + two.id() + "/logo").with(this.personas.platformAdmin().bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"" + forOne + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("media.context_mismatch"));
		// the other school's administrator cannot use it at all
		this.mvc.perform(put("/api/v1/platform/schools/" + two.id() + "/logo").with(two.admin().bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"" + forOne + "\"}"))
			.andExpect(status().isNotFound());
		this.mvc.perform(put("/api/v1/platform/schools/" + one.id() + "/logo").with(two.admin().bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"" + forOne + "\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void organizationLogoIsManagedWithTheOrganizationPermission() throws Exception {
		Fixture f = this.personas.fixture();
		Persona platform = this.personas.platformAdmin();
		// create an organization of our own so that the shared fixture stays untouched
		JsonNode org = body(this.mvc.perform(post("/api/v1/platform/organizations").with(platform.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("countryCode", "ET", "name", "Logo org", "slug",
					com.nemaz.school.support.Personas.newSlug("logo-org")))))
			.andExpect(status().isCreated()));
		UUID orgId = UUID.fromString(org.get("id").asString());

		requestUpload(this.personas.get(Name.ORG_ADMIN), "BRANDING", "l.png", "image/png", 100, organization(f.organization()))
			.andExpect(status().isForbidden());
		String logoId = uploadReady(platform, "BRANDING", "l.png", "image/png", MediaFixtures.png(200, 200, false),
				organization(orgId)).get("id").asString();
		assertThat(this.jdbc.queryForObject("select object_key from media where id = ?::uuid", String.class, logoId))
			.isEqualTo("organizations/" + orgId + "/branding/" + logoId + "/original");

		this.mvc.perform(put("/api/v1/platform/organizations/" + orgId + "/logo").with(platform.bearer())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"" + logoId + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.logo.id").value(logoId));
		this.mvc.perform(get("/api/v1/platform/organizations/" + orgId).with(platform.bearer()))
			.andExpect(jsonPath("$.logo.id").value(logoId));
		this.mvc.perform(delete("/api/v1/platform/organizations/" + orgId + "/logo").with(platform.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.logo").doesNotExist());
		assertThat(statusOf(logoId)).isEqualTo("DELETED");
	}

	// --- helpers ----------------------------------------------------------------------

	private record SchoolAdmin(UUID id, Persona admin) {
	}

	/** A school created through the API with its own administrator, so that tests never touch the shared fixture. */
	private SchoolAdmin newSchoolWithAdmin() throws Exception {
		JsonNode created = body(this.mvc.perform(post("/api/v1/platform/schools").with(this.personas.platformAdmin().bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("countryCode", "ET", "name", "Branding school", "slug",
					com.nemaz.school.support.Personas.newSlug("branding-school")))))
			.andExpect(status().isCreated()));
		UUID id = UUID.fromString(created.get("id").asString());
		return new SchoolAdmin(id, this.personas.withRole("branding-admin", "SCHOOL_ADMIN", AccessScope.school(id)));
	}

	private ResultActions setAvatar(Persona who, String mediaId) throws Exception {
		return this.mvc.perform(put("/api/v1/me/avatar").with(who.bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"mediaId\":\"" + mediaId + "\"}"));
	}

	private ResultActions url(Persona who, String mediaId) throws Exception {
		return this.mvc.perform(get("/api/v1/media/" + mediaId + "/url").param("variant", "THUMB").with(who.bearer()));
	}

	private String statusOf(String mediaId) {
		return this.jdbc.queryForObject("select status from media where id = ?::uuid", String.class, mediaId);
	}

	private int audits(String action, Persona actor) {
		return this.jdbc.queryForObject("select count(*) from audit_logs where action = ? and actor_user_id = ?",
				Integer.class, action, actor.userId());
	}

}

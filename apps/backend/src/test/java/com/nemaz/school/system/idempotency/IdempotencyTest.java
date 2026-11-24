package com.nemaz.school.system.idempotency;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Idempotency-Key on the create endpoints. */
@IntegrationTest
class IdempotencyTest {

	private static final String URL = "/api/v1/platform/organizations";

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Personas personas;

	@Autowired
	IdempotencyConfig.IdempotencyKeyCleanup cleanup;

	private ResultActions create(Persona as, String key, String slug) throws Exception {
		var request = post(URL).with(as.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("countryCode", "ET", "name", "Idempotent org", "slug", slug)));
		if (key != null) {
			request = request.header("Idempotency-Key", key);
		}
		return this.mvc.perform(request);
	}

	private int organizations(String slug) {
		return this.jdbc.queryForObject("select count(*) from organizations where slug = ?", Integer.class, slug);
	}

	@Test
	void aRetryReplaysTheFirstResponse() throws Exception {
		Persona admin = this.personas.platformAdmin();
		String key = "key-" + UUID.randomUUID();
		String slug = Personas.newSlug("idem");
		String first = create(admin, key, slug).andExpect(status().isCreated())
			.andExpect(header().doesNotExist("Idempotent-Replayed"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String replay = create(admin, key, slug).andExpect(status().isCreated())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(this.json.readTree(replay)).isEqualTo(this.json.readTree(first));
		assertThat(organizations(slug)).isEqualTo(1);

		// another user's identical key is independent
		Persona other = this.personas.withPlatformPermissions("platform.organizations.manage");
		create(other, key, Personas.newSlug("idem-other")).andExpect(status().isCreated())
			.andExpect(header().doesNotExist("Idempotent-Replayed"));
		// without a key, nothing is remembered
		create(admin, null, slug).andExpect(status().isConflict());
	}

	@Test
	void aKeyCannotBeReusedForAnotherRequest() throws Exception {
		Persona admin = this.personas.platformAdmin();
		String key = "key-" + UUID.randomUUID();
		create(admin, key, Personas.newSlug("idem")).andExpect(status().isCreated());
		create(admin, key, Personas.newSlug("idem")).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("idempotency.key_reused"));
	}

	@Test
	void aRequestStillInFlightIsRefused() throws Exception {
		Persona admin = this.personas.platformAdmin();
		String key = "key-" + UUID.randomUUID();
		String slug = Personas.newSlug("idem");
		create(admin, key, slug).andExpect(status().isCreated());
		// pretend the first request is still running
		this.jdbc.update("update idempotency_keys set response_status = null, response_body = null where key = ?", key);
		create(admin, key, slug).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency.in_progress"));
		// an abandoned reservation (older than the stale limit) is taken over: the request runs again
		this.jdbc.update("update idempotency_keys set created_at = now() - interval '10 minutes' where key = ?", key);
		create(admin, key, slug).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("organization.slug_taken"));
		assertThat(this.jdbc.queryForObject("select count(*) from idempotency_keys where key = ?", Integer.class, key))
			.isZero();
	}

	@Test
	void failuresAreNotRememberedAndKeysExpire() throws Exception {
		Persona admin = this.personas.platformAdmin();
		String taken = Personas.newSlug("idem-taken");
		create(admin, null, taken).andExpect(status().isCreated());
		String key = "key-" + UUID.randomUUID();
		create(admin, key, taken).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("organization.slug_taken"));
		assertThat(this.jdbc.queryForObject("select count(*) from idempotency_keys where key = ?", Integer.class, key))
			.isZero();
		String fresh = Personas.newSlug("idem");
		create(admin, key, fresh).andExpect(status().isCreated());

		this.jdbc.update("update idempotency_keys set expires_at = now() - interval '1 second' where key = ?", key);
		String later = Personas.newSlug("idem");
		create(admin, key, later).andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotent-Replayed"));
		assertThat(organizations(later)).isEqualTo(1);

		this.jdbc.update("update idempotency_keys set expires_at = now() - interval '1 second' where key = ?", key);
		this.cleanup.purge();
		assertThat(this.jdbc.queryForObject("select count(*) from idempotency_keys where key = ?", Integer.class, key))
			.isZero();
	}

	@Test
	void invalidKeysAreRejectedAndForbiddenCallsLeaveNoKey() throws Exception {
		Persona admin = this.personas.platformAdmin();
		create(admin, "x".repeat(129), Personas.newSlug("idem")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("idempotency.key_invalid"));
		create(admin, "has space", Personas.newSlug("idem")).andExpect(status().isBadRequest());
		String key = "key-" + UUID.randomUUID();
		create(this.personas.plainUser(), key, Personas.newSlug("idem")).andExpect(status().isForbidden());
		assertThat(this.jdbc.queryForObject("select count(*) from idempotency_keys where key = ?", Integer.class, key))
			.isZero();
	}

	@Test
	void roleAssignmentCreationIsIdempotentToo() throws Exception {
		Persona admin = this.personas.platformAdmin();
		Personas.Fixture f = this.personas.fixture();
		UUID user = this.personas.newUser("idem-grantee").userId();
		String body = this.json.writeValueAsString(Map.of("userId", user, "roleCode", "SCHOOL_ADMIN", "scope",
				Map.of("type", "SCHOOL", "schoolId", f.schoolA())));
		String key = "key-" + UUID.randomUUID();
		for (int i = 0; i < 2; i++) {
			this.mvc.perform(post("/api/v1/platform/role-assignments").with(admin.bearer())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isCreated());
		}
		assertThat(this.jdbc.queryForObject("select count(*) from role_assignments where user_id = ?", Integer.class, user))
			.isEqualTo(1);
	}

}

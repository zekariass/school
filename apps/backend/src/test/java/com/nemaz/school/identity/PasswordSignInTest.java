package com.nemaz.school.identity;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.AuthTestSupport;
import com.nemaz.school.support.Personas;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Password sign-in: argon2id, uniform errors, lockout, rate limits. */
@IntegrationTest
class PasswordSignInTest extends AuthTestSupport {

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	PasswordEncoder encoder;

	private TestUser userWithPassword(String status) {
		String email = "pw" + System.nanoTime() + "@example.org";
		TestUser user = user(Personas.newPhone(), email, status, true);
		this.jdbc.update("insert into password_credentials (user_id, password_hash) values (?, ?)", user.id(),
				this.encoder.encode(PASSWORD));
		return user;
	}

	private org.springframework.test.web.servlet.ResultActions signIn(String identifier, String password)
			throws Exception {
		return postJson("/api/v1/auth/password", Map.of("identifier", identifier, "password", password));
	}

	@Test
	void storesArgon2idAndSignsInByEmailPhoneOrNationalPhone() throws Exception {
		TestUser user = userWithPassword("ACTIVE");
		assertThat(this.jdbc.queryForObject("select password_hash from password_credentials where user_id = ?",
				String.class, user.id())).startsWith("$argon2id$");
		signIn(user.email().toUpperCase(), PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isString());
		signIn(user.phone(), PASSWORD).andExpect(status().isOk());
		signIn("0" + user.phone().substring(4), PASSWORD).andExpect(status().isOk());
		assertThat(this.jdbc.queryForObject("select count(*) from auth_sessions where user_id = ? and auth_method = 'PASSWORD'",
				Integer.class, user.id())).isEqualTo(3);
	}

	@Test
	void everyFailureLooksTheSame() throws Exception {
		TestUser active = userWithPassword("ACTIVE");
		TestUser suspended = userWithPassword("SUSPENDED");
		TestUser noPassword = userWithPhone();
		JsonNode wrong = read(signIn(active.email(), "not the password").andExpect(status().isBadRequest()));
		JsonNode unknown = read(signIn("nobody-" + UUID.randomUUID() + "@example.org", PASSWORD)
			.andExpect(status().isBadRequest()));
		JsonNode inactive = read(signIn(suspended.email(), PASSWORD).andExpect(status().isBadRequest()));
		JsonNode withoutCredential = read(signIn(noPassword.phone(), PASSWORD).andExpect(status().isBadRequest()));
		for (JsonNode body : new JsonNode[] { wrong, unknown, inactive, withoutCredential }) {
			assertThat(body.get("code").asString()).isEqualTo("auth.invalid_credentials");
			assertThat(body.get("detail").asString()).isEqualTo(wrong.get("detail").asString());
			assertThat(body.get("title").asString()).isEqualTo(wrong.get("title").asString());
		}
	}

	@Test
	void fiveFailuresLockThePasswordForFifteenMinutes() throws Exception {
		TestUser user = userWithPassword("ACTIVE");
		for (int i = 0; i < 5; i++) {
			signIn(user.email(), "wrong-" + i).andExpect(status().isBadRequest());
		}
		// even the right password is refused, with the same answer
		signIn(user.email(), PASSWORD).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("auth.invalid_credentials"));
		assertThat(this.jdbc.queryForObject("select locked_until is not null from password_credentials where user_id = ?",
				Boolean.class, user.id())).isTrue();
		assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_logs where target_id = ? and action = 'auth.password.lockout'", Integer.class,
				user.id())).isEqualTo(1);

		this.clock.advance(Duration.ofMinutes(16));
		signIn(user.email(), PASSWORD).andExpect(status().isOk());
		assertThat(this.jdbc.queryForObject(
				"select failed_attempts = 0 and locked_until is null from password_credentials where user_id = ?",
				Boolean.class, user.id())).isTrue();
	}

	@Test
	void auditRowsNeverContainThePassword() throws Exception {
		TestUser user = userWithPassword("ACTIVE");
		signIn(user.email(), "secret-attempt-42").andExpect(status().isBadRequest());
		signIn(user.email(), PASSWORD).andExpect(status().isOk());
		String all = String.join("|", this.jdbc.queryForList(
				"select coalesce(metadata::text, '') from audit_logs where target_id = ?", String.class, user.id()));
		assertThat(all).doesNotContain("secret-attempt-42").doesNotContain(PASSWORD).doesNotContain(user.email())
			.contains("p***@example.org");
	}

	@Test
	void attemptsAreRateLimitedPerIdentifier() throws Exception {
		String identifier = "limited-" + UUID.randomUUID() + "@example.org";
		for (int i = 0; i < 10; i++) {
			signIn(identifier, "x").andExpect(status().isBadRequest());
		}
		signIn(identifier, "x").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("auth.rate_limited"));
	}

}

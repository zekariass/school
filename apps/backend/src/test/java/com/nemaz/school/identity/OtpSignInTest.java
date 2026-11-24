package com.nemaz.school.identity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.AuthTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One-time-code sign-in: happy path, no account enumeration, attempt and time limits, rate limits, audit. */
@IntegrationTest
class OtpSignInTest extends AuthTestSupport {

	@Test
	void phoneCodeSignsInAndIsAuditedWithoutSecrets() throws Exception {
		TestUser user = userWithPhone();
		JsonNode accepted = read(requestOtp(user.phone()).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.codeLength").value(6))
			.andExpect(jsonPath("$.expiresIn").value(300)));
		String code = this.messages.awaitCode(user.phone());

		JsonNode tokens = read(verifyOtp(user.phone(), code).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
			.andExpect(jsonPath("$.refreshTokenExpiresIn").value(14 * 24 * 3600))
			.andExpect(jsonPath("$.refreshToken").isString()));
		getWithToken("/api/v1/me", tokens.get("accessToken").asString()).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(user.id().toString()))
			.andExpect(jsonPath("$.phone").value(user.phone()))
			.andExpect(jsonPath("$.phoneVerified").value(true));
		assertThat(accepted.size()).isEqualTo(2);
		assertThat(this.jdbc.queryForObject("select last_login_at is not null from users where id = ?", Boolean.class,
				user.id())).isTrue();

		// stored: only an HMAC of the code, and the session's refresh token only as a hash
		String storedHash = this.jdbc.queryForObject(
				"select code_hash from otp_challenges where user_id = ? order by created_at desc limit 1", String.class,
				user.id());
		assertThat(storedHash).hasSize(64).doesNotContain(code);
		assertThat(this.jdbc.queryForObject("select count(*) from auth_sessions where refresh_token_hash = ?",
				Integer.class, tokens.get("refreshToken").asString())).isZero();

		List<Map<String, Object>> audit = this.jdbc.queryForList("""
				select action, outcome, actor_user_id, metadata::text as metadata from audit_logs
				where target_id = ? and action like 'auth.otp.%' order by id""", user.id());
		assertThat(audit).extracting((row) -> row.get("action") + ":" + row.get("outcome"))
			.containsExactly("auth.otp.request:SUCCESS", "auth.otp.verify:SUCCESS");
		assertThat(audit.get(1).get("actor_user_id")).isEqualTo(user.id());
		for (Map<String, Object> row : audit) {
			String metadata = String.valueOf(row.get("metadata"));
			assertThat(metadata).doesNotContain(user.phone()).doesNotContain(code)
				.contains(user.phone().substring(0, 5) + "****" + user.phone().substring(user.phone().length() - 3));
		}
	}

	@Test
	void requestAnswersTheSameWhetherOrNotAnAccountCanReceiveACode() throws Exception {
		TestUser known = userWithPhone();
		TestUser suspended = user(com.nemaz.school.support.Personas.newPhone(), null, "SUSPENDED", true);
		TestUser unverified = user(com.nemaz.school.support.Personas.newPhone(), null, "ACTIVE", false);
		String unknown = com.nemaz.school.support.Personas.newPhone();

		String expected = requestOtp(known.phone()).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		this.messages.awaitCode(known.phone());
		for (String phone : List.of(unknown, suspended.phone(), unverified.phone())) {
			assertThat(requestOtp(phone).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
				.isEqualTo(expected);
			assertThat(this.messages.nothingDelivered(phone, Duration.ofMillis(300))).isTrue();
			verifyOtp(phone, "123456").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("auth.invalid_code"));
		}
		assertThat(this.jdbc.queryForObject("select count(*) from otp_challenges where destination = ?", Integer.class,
				unknown)).isZero();
	}

	@Test
	void nationalFormatsAreNormalizedAndBadDestinationsRejected() throws Exception {
		TestUser user = userWithPhone();
		String national = "0" + user.phone().substring(4); // +2519xxxxxxxx -> 09xxxxxxxx
		postJson("/api/v1/auth/otp/request", Map.of("phone", national)).andExpect(status().isAccepted());
		String code = this.messages.awaitCode(user.phone());
		postJson("/api/v1/auth/otp/verify", Map.of("phone", national, "code", code)).andExpect(status().isOk());

		postJson("/api/v1/auth/otp/request", Map.of("phone", "12")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("auth.invalid_destination"));
		postJson("/api/v1/auth/otp/request", Map.of("phone", user.phone(), "email", "a@b.example"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("auth.invalid_destination"));
		postJson("/api/v1/auth/otp/request", Map.of()).andExpect(status().isBadRequest());
	}

	@Test
	void fiveWrongGuessesEndTheCode() throws Exception {
		TestUser user = userWithPhone();
		requestOtp(user.phone()).andExpect(status().isAccepted());
		String code = this.messages.awaitCode(user.phone());
		String wrong = code.equals("000000") ? "111111" : "000000";
		for (int i = 0; i < 5; i++) {
			verifyOtp(user.phone(), wrong).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("auth.invalid_code"));
		}
		verifyOtp(user.phone(), code).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("auth.invalid_code"));
		assertThat(this.jdbc.queryForObject("select attempts from otp_challenges where user_id = ?", Integer.class,
				user.id())).isEqualTo(5);
		assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_logs where target_id = ? and action = 'auth.otp.verify' and outcome = 'FAILURE'",
				Integer.class, user.id())).isEqualTo(6);
	}

	@Test
	void codesExpireAfterFiveMinutes() throws Exception {
		TestUser user = userWithPhone();
		requestOtp(user.phone()).andExpect(status().isAccepted());
		String code = this.messages.awaitCode(user.phone());
		this.clock.advance(Duration.ofMinutes(5).plusSeconds(1));
		verifyOtp(user.phone(), code).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("auth.invalid_code"));
	}

	@Test
	void codesAreSingleUseAndANewRequestReplacesTheOldCode() throws Exception {
		TestUser user = userWithPhone();
		requestOtp(user.phone()).andExpect(status().isAccepted());
		String first = this.messages.awaitCode(user.phone());
		requestOtp(user.phone()).andExpect(status().isAccepted());
		String second = this.messages.awaitCode(user.phone());
		if (!first.equals(second)) {
			verifyOtp(user.phone(), first).andExpect(status().isBadRequest());
		}
		verifyOtp(user.phone(), second).andExpect(status().isOk());
		verifyOtp(user.phone(), second).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("auth.invalid_code"));
	}

	@Test
	void requestsAreRateLimitedPerDestinationEvenWithoutAnAccount() throws Exception {
		String unknown = com.nemaz.school.support.Personas.newPhone();
		for (int i = 0; i < 5; i++) {
			requestOtp(unknown).andExpect(status().isAccepted());
		}
		requestOtp(unknown).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("auth.rate_limited"))
			.andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("^[1-9][0-9]*$")));
	}

	@Test
	void requestsAreRateLimitedPerClientAddress() throws Exception {
		String ip = randomIp();
		for (int i = 0; i < 30; i++) {
			postJson("/api/v1/auth/otp/request", Map.of("phone", com.nemaz.school.support.Personas.newPhone()), from(ip))
				.andExpect(status().isAccepted());
		}
		postJson("/api/v1/auth/otp/request", Map.of("phone", com.nemaz.school.support.Personas.newPhone()), from(ip))
			.andExpect(status().isTooManyRequests());
	}

	@Test
	void verificationIsRateLimitedPerDestination() throws Exception {
		String phone = com.nemaz.school.support.Personas.newPhone();
		for (int i = 0; i < 10; i++) {
			verifyOtp(phone, "123456").andExpect(status().isBadRequest());
		}
		verifyOtp(phone, "123456").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("auth.rate_limited"));
	}

	@Test
	void emailCodesUseTheUsersLanguage() throws Exception {
		String email = "Otp." + System.nanoTime() + "@Example.org";
		TestUser user = user(null, email.toLowerCase(), "ACTIVE", true);
		this.jdbc.update("update users set preferred_language_code = 'am' where id = ?", user.id());
		postJson("/api/v1/auth/otp/request", Map.of("email", email)).andExpect(status().isAccepted());
		String message = this.messages.awaitMessage(email.toLowerCase());
		assertThat(message).contains("የመግቢያ ኮድዎ");
		String code = message.replaceAll("(?s).*?(\\d{6}).*", "$1");
		postJson("/api/v1/auth/otp/verify", Map.of("email", email, "code", code)).andExpect(status().isOk());
	}

}

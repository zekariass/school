package com.nemaz.school.support;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.identity.application.TokenService;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import com.nemaz.school.identity.domain.AuthMethod;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Helpers for sign-in and session tests: users with verified destinations, HTTP calls from a
 * random client address (so per-address rate limits of one test never affect another), and
 * direct session creation through the real TokenService.
 */
public abstract class AuthTestSupport {

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JsonMapper json;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected RecordingMessages messages;

	@Autowired
	protected MutableClock clock;

	@Autowired
	protected TokenService tokenService;

	@Autowired
	protected TransactionTemplate tx;

	@Autowired
	protected Personas personas;

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	/** An ACTIVE user with a verified phone; returns [id, phone]. */
	protected TestUser userWithPhone() {
		return user(Personas.newPhone(), null, "ACTIVE", true);
	}

	protected TestUser user(String phone, String email, String status, boolean verified) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into users (id, given_name, display_name, phone_e164, phone_verified_at, email, email_verified_at, status)
				values (?, 'Test', 'Test user', ?, case when ? then now() end, ?, case when ? then now() end, ?)""", id,
				phone, verified && phone != null, email, verified && email != null, status);
		return new TestUser(id, phone, email);
	}

	protected static String randomIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
	}

	protected static RequestPostProcessor from(String ip) {
		return (request) -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}

	protected ResultActions postJson(String url, Object body, RequestPostProcessor... processors) throws Exception {
		MockHttpServletRequestBuilder builder = post(url).contentType(MediaType.APPLICATION_JSON)
			.content((body instanceof String s) ? s : this.json.writeValueAsString(body));
		for (RequestPostProcessor p : processors) {
			builder = builder.with(p);
		}
		if (processors.length == 0) {
			builder = builder.with(from(randomIp()));
		}
		return this.mvc.perform(builder);
	}

	protected ResultActions getWithToken(String url, String accessToken) throws Exception {
		return this.mvc.perform(get(url).header("Authorization", "Bearer " + accessToken));
	}

	protected ResultActions requestOtp(String phone) throws Exception {
		return postJson("/api/v1/auth/otp/request", Map.of("phone", phone));
	}

	protected ResultActions verifyOtp(String phone, String code) throws Exception {
		return postJson("/api/v1/auth/otp/verify", Map.of("phone", phone, "code", code));
	}

	protected JsonNode read(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString());
	}

	/** A fresh session for {@code userId} through the real TokenService. */
	protected TokenPair session(UUID userId) {
		return this.tx.execute((status) -> this.tokenService.startSession(userId, AuthMethod.OTP));
	}

	protected record TestUser(UUID id, String phone, String email) {
	}

}

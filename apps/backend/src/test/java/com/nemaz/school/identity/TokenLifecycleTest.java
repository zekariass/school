package com.nemaz.school.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import com.nemaz.school.identity.application.UserSnapshotCache;
import com.nemaz.school.support.AuthTestSupport;
import com.nemaz.school.support.Personas;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Access tokens, refresh rotation, reuse detection, sign-out and the deny-list, inactive accounts. */
@IntegrationTest
class TokenLifecycleTest extends AuthTestSupport {

	@Autowired
	JwtEncoder encoder;

	@Autowired
	UserSnapshotCache snapshots;

	private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
		return postJson("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken));
	}

	@Test
	void refreshRotatesTheTokenWithinTheSameSession() throws Exception {
		TestUser user = userWithPhone();
		TokenPair first = session(user.id());
		var next = read(refresh(first.refreshToken()).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store")));
		String nextRefresh = next.get("refreshToken").asString();
		assertThat(nextRefresh).isNotEqualTo(first.refreshToken());
		getWithToken("/api/v1/me", next.get("accessToken").asString()).andExpect(status().isOk());
		// the old access token keeps working until it expires: rotation is not a sign-out
		getWithToken("/api/v1/me", first.accessToken()).andExpect(status().isOk());

		List<Map<String, Object>> rows = this.jdbc.queryForList(
				"select token_family_id, revoked_reason from auth_sessions where user_id = ? order by created_at", user.id());
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).get("token_family_id")).isEqualTo(rows.get(1).get("token_family_id"));
		assertThat(rows.get(0).get("revoked_reason")).isEqualTo("ROTATED");
		assertThat(rows.get(1).get("revoked_reason")).isNull();
		refresh(nextRefresh).andExpect(status().isOk());
	}

	@Test
	void reusingARotatedTokenRevokesTheWholeSession() throws Exception {
		TestUser user = userWithPhone();
		TokenPair first = session(user.id());
		var second = read(refresh(first.refreshToken()).andExpect(status().isOk()));

		refresh(first.refreshToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.session_revoked"));
		// the legitimate holder's newer tokens are dead too
		refresh(second.get("refreshToken").asString()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.session_revoked"));
		getWithToken("/api/v1/me", second.get("accessToken").asString()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.session_revoked"));
		assertThat(this.jdbc.queryForList(
				"select revoked_reason from auth_sessions where user_id = ? order by created_at", String.class, user.id()))
			.containsExactly("ROTATED", "REUSE_DETECTED");
		assertThat(this.jdbc.queryForObject(
				"select count(*) from audit_logs where target_id = ? and action = 'auth.refresh.reuse_detected' and outcome = 'DENIED'",
				Integer.class, user.id())).isGreaterThanOrEqualTo(1);
		// other sessions of the user are not affected
		TokenPair other = session(user.id());
		getWithToken("/api/v1/me", other.accessToken()).andExpect(status().isOk());
	}

	@Test
	void concurrentRefreshOfOneTokenSucceedsOnceAndTheOtherCountsAsReuse() throws Exception {
		TestUser user = userWithPhone();
		TokenPair pair = session(user.id());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Object>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				Callable<Object> call = () -> {
					start.await();
					try {
						return this.tokenService.refresh(pair.refreshToken());
					}
					catch (ApiException ex) {
						return ex;
					}
				};
				results.add(pool.submit(call));
			}
			start.countDown();
			List<Object> outcomes = new ArrayList<>();
			for (Future<Object> f : results) {
				outcomes.add(f.get());
			}
			assertThat(outcomes).filteredOn(TokenPair.class::isInstance).hasSize(1);
			assertThat(outcomes).filteredOn(ApiException.class::isInstance)
				.singleElement()
				.extracting((e) -> ((ApiException) e).code())
				.isEqualTo("auth.session_revoked");
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void logoutStopsTheAccessTokenImmediately() throws Exception {
		TestUser user = userWithPhone();
		TokenPair pair = session(user.id());
		getWithToken("/api/v1/me", pair.accessToken()).andExpect(status().isOk());
		postJson("/api/v1/auth/logout", Map.of("refreshToken", pair.refreshToken())).andExpect(status().isNoContent());
		getWithToken("/api/v1/me", pair.accessToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.session_revoked"))
			.andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
		refresh(pair.refreshToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.session_revoked"));
		// unknown tokens: logout still succeeds, refresh does not
		postJson("/api/v1/auth/logout", Map.of("refreshToken", "not-a-token")).andExpect(status().isNoContent());
		refresh("not-a-token").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.invalid_refresh_token"));
	}

	@Test
	void logoutAllEndsEverySessionOfTheUser() throws Exception {
		TestUser user = userWithPhone();
		TokenPair a = session(user.id());
		TokenPair b = session(user.id());
		TestUser bystander = userWithPhone();
		TokenPair c = session(bystander.id());
		this.mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + a.accessToken()))
			.andExpect(status().isNoContent());
		getWithToken("/api/v1/me", a.accessToken()).andExpect(status().isUnauthorized());
		getWithToken("/api/v1/me", b.accessToken()).andExpect(status().isUnauthorized());
		refresh(b.refreshToken()).andExpect(status().isUnauthorized());
		getWithToken("/api/v1/me", c.accessToken()).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/auth/logout-all")).andExpect(status().isUnauthorized());
	}

	@Test
	void inactiveAccountsAreRejected() throws Exception {
		getWithToken("/api/v1/me", this.personas.get(Personas.Name.SUSPENDED_USER).accessToken())
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.account_inactive"));

		TestUser user = userWithPhone();
		TokenPair pair = session(user.id());
		getWithToken("/api/v1/me", pair.accessToken()).andExpect(status().isOk());
		this.tx.executeWithoutResult((status) -> {
			this.jdbc.update("update users set status = 'SUSPENDED' where id = ?", user.id());
			this.snapshots.evictAfterCommit(user.id());
		});
		getWithToken("/api/v1/me", pair.accessToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.account_inactive"));
		refresh(pair.refreshToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.invalid_refresh_token"));
	}

	@Test
	void invalidAccessTokensAreRejected() throws Exception {
		TestUser user = userWithPhone();
		getWithToken("/api/v1/me", "garbage").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.invalid_token"));
		Instant now = this.clock.instant();
		JwtClaimsSet.Builder valid = JwtClaimsSet.builder()
			.issuer("school-api")
			.audience(List.of("school-apps"))
			.subject(user.id().toString())
			.issuedAt(now)
			.expiresAt(now.plusSeconds(600))
			.claim("sid", java.util.UUID.randomUUID().toString());
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).type("at+jwt").build();
		getWithToken("/api/v1/me", this.encoder.encode(JwtEncoderParameters.from(header, valid.build())).getTokenValue())
			.andExpect(status().isOk());

		for (JwtClaimsSet claims : List.of(valid.audience(List.of("someone-else")).build(),
				JwtClaimsSet.builder().claims((c) -> c.putAll(valid.build().getClaims())).issuer("evil").audience(List.of("school-apps")).build(),
				JwtClaimsSet.builder().claims((c) -> c.putAll(valid.build().getClaims())).audience(List.of("school-apps"))
					.issuer("school-api").issuedAt(now.minusSeconds(900)).expiresAt(now.minusSeconds(120)).build())) {
			getWithToken("/api/v1/me", this.encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue())
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("auth.invalid_token"));
		}
		// wrong token type, and a foreign key
		JwtClaimsSet good = valid.audience(List.of("school-apps")).build();
		getWithToken("/api/v1/me", this.encoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.ES256).type("JWT").build(), good))
			.getTokenValue()).andExpect(status().isUnauthorized());
		ECKey foreign = new ECKeyGenerator(Curve.P_256).keyID("foreign").generate();
		String forged = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(foreign)))
			.encode(JwtEncoderParameters.from(header, good))
			.getTokenValue();
		getWithToken("/api/v1/me", forged).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.invalid_token"));
	}

	@Test
	void accessTokensExpireAfterFifteenMinutes() throws Exception {
		TestUser user = userWithPhone();
		TokenPair pair = session(user.id());
		this.clock.advance(Duration.ofMinutes(15).plusSeconds(61));
		getWithToken("/api/v1/me", pair.accessToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.invalid_token"));
	}

	@Test
	void unusedSessionsExpireAfterFourteenDaysAndAllAfterThirty() throws Exception {
		TestUser user = userWithPhone();
		TokenPair idle = session(user.id());
		TokenPair active = session(user.id());
		String current = active.refreshToken();
		for (int i = 0; i < 3; i++) { // used every 9 days: survives the idle limit, not the absolute one
			this.clock.advance(Duration.ofDays(9));
			var next = read(refresh(current).andExpect(status().isOk()));
			current = next.get("refreshToken").asString();
			assertThat(next.get("refreshTokenExpiresIn").asLong()).isLessThanOrEqualTo(Duration.ofDays(14).toSeconds());
		}
		refresh(idle.refreshToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.invalid_refresh_token"));
		this.clock.advance(Duration.ofDays(4)); // 31 days after sign-in
		refresh(current).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("auth.invalid_refresh_token"));
	}

	@Test
	void staleBearerHeadersDoNotBlockPublicEndpoints() throws Exception {
		TestUser user = userWithPhone();
		TokenPair pair = session(user.id());
		this.mvc.perform(post("/api/v1/auth/refresh").header("Authorization", "Bearer expired.or.garbage")
			.contentType("application/json")
			.content(this.json.writeValueAsString(Map.of("refreshToken", pair.refreshToken())))).andExpect(status().isOk());
	}

}

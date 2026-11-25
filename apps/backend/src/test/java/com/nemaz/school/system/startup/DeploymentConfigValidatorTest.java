package com.nemaz.school.system.startup;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.nemaz.school.system.startup.DeploymentConfigValidator.Mode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.junit.jupiter.api.Test;

import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** One test per rule of the deployed-profile pre-flight check. Secrets never appear in messages. */
class DeploymentConfigValidatorTest {

	private static final String SECRET_PEPPER = "Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZg";

	private static final String JWKS = newJwkSet(true);

	/** A configuration that passes in both deployed profiles. */
	private static MockEnvironment valid(String profile) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profile);
		env.setProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/school");
		env.setProperty("spring.datasource.username", "school_app");
		env.setProperty("spring.datasource.password", "a-long-random-db-password");
		env.setProperty("spring.data.redis.url", "rediss://:token@redis.internal:6379");
		env.setProperty("school.cors.allowed-origins", "https://app.example.org,https://admin.example.org");
		env.setProperty("school.auth.jwt.keys", JWKS);
		env.setProperty("school.auth.otp.pepper", SECRET_PEPPER);
		env.setProperty("school.sms.provider", "console");
		env.setProperty("school.sms.allow-console", "true");
		env.setProperty("school.push.provider", "expo");
		env.setProperty("school.guardians.invite-base-url", "https://app.example.org");
		env.setProperty("school.storage.endpoint", "https://acct.r2.cloudflarestorage.com");
		env.setProperty("school.storage.bucket", "school-staging-media");
		env.setProperty("school.storage.access-key", "AKIAEXAMPLEKEYID0001");
		env.setProperty("school.storage.secret-key", "k3y-secret-value-0987654321");
		return env;
	}

	/** Valid production configuration (a real SMS provider is not built yet, so a name stands in). */
	private static MockEnvironment validProd() {
		MockEnvironment env = valid("prod");
		env.setProperty("school.sms.provider", "africastalking");
		env.setProperty("school.sms.allow-console", "false");
		return env;
	}

	private static List<String> problems(MockEnvironment env) {
		return DeploymentConfigValidator.validate(env);
	}

	private static List<String> problems(String profile, Consumer<MockEnvironment> change) {
		MockEnvironment env = "prod".equals(profile) ? validProd() : valid(profile);
		change.accept(env);
		return problems(env);
	}

	private static void assertSingleProblem(List<String> problems, String mentioning) {
		assertThat(problems).hasSize(1);
		assertThat(problems.getFirst()).startsWith(mentioning);
	}

	@Test
	void validStagingAndProductionConfigurationsPass() {
		assertThat(problems(valid("staging"))).isEmpty();
		assertThat(problems(validProd())).isEmpty();
	}

	@Test
	void developmentProfilesAreNotChecked() {
		for (String profile : new String[] { "local", "test" }) {
			MockEnvironment env = new MockEnvironment();
			env.setActiveProfiles(profile);
			assertThat(DeploymentConfigValidator.modeOf(env)).isEqualTo(Mode.DEVELOPMENT);
			assertThat(problems(env)).isEmpty();
		}
	}

	@Test
	void profilesAreMappedToModes() {
		assertThat(DeploymentConfigValidator.modeOf(valid("staging"))).isEqualTo(Mode.STAGING);
		assertThat(DeploymentConfigValidator.modeOf(validProd())).isEqualTo(Mode.PRODUCTION);
		assertThat(DeploymentConfigValidator.modeOf(new MockEnvironment())).isEqualTo(Mode.OTHER);
	}

	@Test
	void everyProblemIsReportedAtOnce() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("staging");
		List<String> problems = problems(env);
		assertThat(problems).anyMatch((p) -> p.startsWith("DB_URL"))
			.anyMatch((p) -> p.startsWith("DB_USERNAME"))
			.anyMatch((p) -> p.startsWith("DB_PASSWORD"))
			.anyMatch((p) -> p.startsWith("REDIS_URL"))
			.anyMatch((p) -> p.startsWith("CORS_ALLOWED_ORIGINS"))
			.anyMatch((p) -> p.startsWith("SCHOOL_AUTH_JWT_KEYS"))
			.anyMatch((p) -> p.startsWith("SCHOOL_AUTH_OTP_PEPPER"))
			.anyMatch((p) -> p.startsWith("SCHOOL_SMS_PROVIDER"))
			.anyMatch((p) -> p.startsWith("SCHOOL_PUSH_PROVIDER"))
			.anyMatch((p) -> p.startsWith("SCHOOL_GUARDIANS_INVITE_BASE_URL"))
			.anyMatch((p) -> p.startsWith("SCHOOL_STORAGE_ENDPOINT"))
			.anyMatch((p) -> p.startsWith("SCHOOL_STORAGE_BUCKET"))
			.anyMatch((p) -> p.startsWith("SCHOOL_STORAGE_ACCESS_KEY"))
			.anyMatch((p) -> p.startsWith("SCHOOL_STORAGE_SECRET_KEY"));
	}

	@Test
	void unresolvedPlaceholdersCountAsMissing() {
		for (String profile : new String[] { "staging", "prod" }) {
			assertSingleProblem(problems(profile, (env) -> env.setProperty("spring.datasource.url", "${DB_URL}")),
					"DB_URL is not set");
		}
	}

	// ---------------------------------------------------------------- database

	@Test
	void databaseSettingsAreRequired() {
		assertSingleProblem(problems("staging", (env) -> env.withProperty("spring.datasource.username", "")),
				"DB_USERNAME");
		assertSingleProblem(problems("staging", (env) -> env.withProperty("spring.datasource.password", " ")),
				"DB_PASSWORD");
	}

	@Test
	void databaseUrlMustBePostgresAndNotLocalhost() {
		assertSingleProblem(problems("prod", (env) -> env.setProperty("spring.datasource.url", "jdbc:mysql://db/x")),
				"DB_URL must be a PostgreSQL");
		for (String url : new String[] { "jdbc:postgresql://localhost:5442/school",
				"jdbc:postgresql://127.0.0.1/school", "jdbc:postgresql://[::1]:5432/school",
				"jdbc:postgresql://host.docker.internal/school", "jdbc:postgresql://db1:5432,localhost:5432/school" }) {
			assertSingleProblem(problems("prod", (env) -> env.setProperty("spring.datasource.url", url)),
					"DB_URL points at localhost");
		}
	}

	@Test
	void developmentDatabaseCredentialsAreRefused() {
		for (String password : new String[] { "school", "postgres", "Password", "changeme" }) {
			assertSingleProblem(
					problems("staging", (env) -> env.setProperty("spring.datasource.password", password)),
					"DB_PASSWORD is a default");
		}
		assertSingleProblem(problems("staging", (env) -> {
			env.setProperty("spring.datasource.username", "same-as-password-1");
			env.setProperty("spring.datasource.password", "same-as-password-1");
		}), "DB_PASSWORD is a default");
		assertSingleProblem(problems("staging", (env) -> env.setProperty("spring.datasource.password", "short1")),
				"DB_PASSWORD is too short");
	}

	// ------------------------------------------------------------------- redis

	@Test
	void redisUrlIsRequiredInDeployedProfiles() {
		assertSingleProblem(problems("staging", (env) -> env.setProperty("spring.data.redis.url", "")), "REDIS_URL");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("spring.data.redis.url", "")), "REDIS_URL");
	}

	@Test
	void redisUrlMustBeRedisAndNotLocalhost() {
		assertSingleProblem(problems("prod", (env) -> env.setProperty("spring.data.redis.url", "http://r:6379")),
				"REDIS_URL must start with");
		assertSingleProblem(
				problems("staging", (env) -> env.setProperty("spring.data.redis.url", "redis://localhost:6390")),
				"REDIS_URL points at localhost");
	}

	@Test
	void profileWithoutDeploymentNameStillRefusesTheRedisLocalhostFallback() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty("spring.datasource.url", "jdbc:postgresql://db:5432/school");
		env.setProperty("spring.datasource.username", "u");
		env.setProperty("spring.datasource.password", "p");
		assertSingleProblem(problems(env), "REDIS_URL");
		env.setProperty("spring.data.redis.url", "redis://redis:6379");
		assertThat(problems(env)).isEmpty();
		// a bare `docker run` against services on the host (README) is not a deployed profile: localhost is fine
		env.setProperty("spring.data.redis.url", "redis://host.docker.internal:6390");
		env.setProperty("spring.datasource.url", "jdbc:postgresql://host.docker.internal:5442/school");
		assertThat(problems(env)).isEmpty();
	}

	// -------------------------------------------------------------------- cors

	@Test
	void corsOriginsMustBeNonEmptyHttpsAndExact() {
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.cors.allowed-origins", "")),
				"CORS_ALLOWED_ORIGINS is empty");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.cors.allowed-origins", "*")),
				"CORS_ALLOWED_ORIGINS must not contain a wildcard");
		assertSingleProblem(
				problems("prod", (env) -> env.setProperty("school.cors.allowed-origins", "https://*.example.org")),
				"CORS_ALLOWED_ORIGINS must not contain a wildcard");
		assertSingleProblem(
				problems("prod", (env) -> env.setProperty("school.cors.allowed-origins", "http://app.example.org")),
				"CORS_ALLOWED_ORIGINS every origin must use https");
		assertSingleProblem(problems("staging",
				(env) -> env.setProperty("school.cors.allowed-origins", "https://app.example.org,http://localhost:3100")),
				"CORS_ALLOWED_ORIGINS every origin must use https");
		assertSingleProblem(
				problems("prod", (env) -> env.setProperty("school.cors.allowed-origins", "https://app.example.org/")),
				"CORS_ALLOWED_ORIGINS origins must be scheme and host only");
	}

	// --------------------------------------------------------------------- jwt

	@Test
	void jwtKeysMustBeAValidSetWithAPrivateFirstKey() {
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.auth.jwt.keys", "")),
				"SCHOOL_AUTH_JWT_KEYS is not set");
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.auth.jwt.keys", "not json")),
				"SCHOOL_AUTH_JWT_KEYS is not a valid JWK Set");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.auth.jwt.keys", "{\"keys\":[]}")),
				"SCHOOL_AUTH_JWT_KEYS contains no keys");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.auth.jwt.keys", newJwkSet(false))),
				"SCHOOL_AUTH_JWT_KEYS the first key signs");
	}

	@Test
	void jwtKeyRotationSetWithPublicOldKeyIsAccepted() throws Exception {
		ECKey fresh = newKey(true);
		ECKey old = newKey(false);
		String set = new JWKSet(List.of(fresh, old)).toString(false);
		assertThat(problems("prod", (env) -> env.setProperty("school.auth.jwt.keys", set))).isEmpty();
	}

	@Test
	void jwtKeysOfAnotherCurveAreRefused() throws Exception {
		ECKey p384 = new ECKeyGenerator(Curve.P_384).keyID("k").generate();
		String set = new JWKSet(p384).toString(false);
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.auth.jwt.keys", set)),
				"SCHOOL_AUTH_JWT_KEYS key 1 is not an EC P-256 key");
	}

	@Test
	void ephemeralKeyIsRefused() {
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.auth.jwt.allow-ephemeral-key", "true")),
				"school.auth.jwt.allow-ephemeral-key");
	}

	// ------------------------------------------------------------------ pepper

	@Test
	void pepperNeedsLengthAndMustNotBeASampleValue() {
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.auth.otp.pepper", "")),
				"SCHOOL_AUTH_OTP_PEPPER is not set");
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.auth.otp.pepper", "q9Zx7Lm2Vb8Nc4")),
				"SCHOOL_AUTH_OTP_PEPPER is too short");
		for (String sample : new String[] { "local-development-pepper-not-a-secret-0123456789",
				"test-pepper-not-a-secret-0123456789abcdef", "change-me-to-a-long-random-secret-0123456789",
				"my-own-CHANGEME-pepper-value-abcdefghijk1" }) {
			assertSingleProblem(problems("prod", (env) -> env.setProperty("school.auth.otp.pepper", sample)),
					"SCHOOL_AUTH_OTP_PEPPER is a documented sample");
		}
		assertSingleProblem(
				problems("prod", (env) -> env.setProperty("school.auth.otp.pepper", "a".repeat(40))),
				"SCHOOL_AUTH_OTP_PEPPER has too little variety");
	}

	// --------------------------------------------------------------- providers

	@Test
	void consoleSmsIsRefusedInProductionAndNeedsAnExplicitFlagInStaging() {
		assertSingleProblem(problems("prod", (env) -> {
			env.setProperty("school.sms.provider", "console");
			env.setProperty("school.sms.allow-console", "true");
		}), "SCHOOL_SMS_PROVIDER 'console'");
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.sms.allow-console", "false")),
				"SCHOOL_SMS_PROVIDER 'console' in staging");
		assertThat(problems("staging", (env) -> env.setProperty("school.sms.allow-console", "true"))).isEmpty();
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.sms.provider", "")),
				"SCHOOL_SMS_PROVIDER is not set");
	}

	@Test
	void logPushIsRefusedInProductionAndNeedsAnExplicitFlagInStaging() {
		assertSingleProblem(problems("prod", (env) -> {
			env.setProperty("school.push.provider", "log");
			env.setProperty("school.push.allow-log", "true");
		}), "SCHOOL_PUSH_PROVIDER 'log'");
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.push.provider", "log")),
				"SCHOOL_PUSH_PROVIDER 'log' in staging");
		assertThat(problems("staging", (env) -> {
			env.setProperty("school.push.provider", "log");
			env.setProperty("school.push.allow-log", "true");
		})).isEmpty();
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.push.provider", "fcm")),
				"SCHOOL_PUSH_PROVIDER must be");
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.push.provider", "")),
				"SCHOOL_PUSH_PROVIDER is not set");
	}

	@Test
	void inviteBaseUrlMustBeHttpsAndPublic() {
		assertSingleProblem(
				problems("staging", (env) -> env.setProperty("school.guardians.invite-base-url", "http://app.example.org")),
				"SCHOOL_GUARDIANS_INVITE_BASE_URL must be an https");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.guardians.invite-base-url", "")),
				"SCHOOL_GUARDIANS_INVITE_BASE_URL is not set");
		assertSingleProblem(problems("prod",
				(env) -> env.setProperty("school.guardians.invite-base-url", "https://localhost:3100")),
				"SCHOOL_GUARDIANS_INVITE_BASE_URL points at localhost");
	}

	// ----------------------------------------------------------------- storage

	@Test
	void storageSettingsAreRequired() {
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.storage.endpoint", "")),
				"SCHOOL_STORAGE_ENDPOINT is not set");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.storage.bucket", "")),
				"SCHOOL_STORAGE_BUCKET is not set");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.storage.access-key", "")),
				"SCHOOL_STORAGE_ACCESS_KEY is not set");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.storage.secret-key", "")),
				"SCHOOL_STORAGE_SECRET_KEY is not set");
	}

	@Test
	void storageMustNotUseLocalhostOrDevelopmentCredentialsOrCreateBuckets() {
		assertThat(problems("staging", (env) -> env.setProperty("school.storage.endpoint", "http://localhost:8343")))
			.hasSize(2)
			.allMatch((p) -> p.startsWith("SCHOOL_STORAGE_ENDPOINT") || p.startsWith("SCHOOL_STORAGE_PUBLIC_ENDPOINT"));
		assertSingleProblem(problems("prod", (env) -> {
			env.setProperty("school.storage.public-endpoint", "https://127.0.0.1:8343");
		}), "SCHOOL_STORAGE_PUBLIC_ENDPOINT points at localhost");
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.storage.access-key", "school-dev")),
				"SCHOOL_STORAGE_ACCESS_KEY is the development");
		assertSingleProblem(
				problems("staging", (env) -> env.setProperty("school.storage.secret-key", "school-dev-secret")),
				"SCHOOL_STORAGE_SECRET_KEY is the development");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.storage.bucket", "school-media-dev")),
				"SCHOOL_STORAGE_BUCKET is a development");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.storage.create-bucket", "true")),
				"SCHOOL_STORAGE_CREATE_BUCKET must be false");
	}

	@Test
	void presignedUrlHostMustBePublicHttpsEvenWhenTheInternalEndpointIsHttp() {
		// single-VM staging: the backend reaches the store on a private http address, clients use the public one
		assertThat(problems("staging", (env) -> {
			env.setProperty("school.storage.endpoint", "http://seaweed:8333");
			env.setProperty("school.storage.public-endpoint", "https://media.example.org");
		})).isEmpty();
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.storage.endpoint", "http://seaweed:8333")),
				"SCHOOL_STORAGE_PUBLIC_ENDPOINT is not set");
		// production: the internal endpoint must be https as well
		assertSingleProblem(problems("prod", (env) -> {
			env.setProperty("school.storage.endpoint", "http://store.internal:8333");
			env.setProperty("school.storage.public-endpoint", "https://media.example.org");
		}), "SCHOOL_STORAGE_ENDPOINT must be an https");
	}

	// ----------------------------------------------------- development leftovers

	@Test
	void localProfileMayNotBeCombinedWithADeployedProfile() {
		MockEnvironment env = valid("staging");
		env.setActiveProfiles("staging", "local");
		assertSingleProblem(problems(env), "spring.profiles.active");
	}

	@Test
	void devAdminBootstrapIsRefused() {
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.bootstrap.admin.phone", "+251900000001")),
				"SCHOOL_BOOTSTRAP_ADMIN_PHONE");
		assertSingleProblem(problems("staging", (env) -> env.setProperty("school.bootstrap.admin.name", "Dev Admin")),
				"SCHOOL_BOOTSTRAP_ADMIN_NAME");
		assertThat(problems("prod", (env) -> {
			env.setProperty("school.bootstrap.admin.phone", "+251911223344");
			env.setProperty("school.bootstrap.admin.name", "Platform administrator");
		})).isEmpty();
	}

	@Test
	void productionMustNotExposeMetricsOrApiDocsPublicly() {
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.security.public-metrics", "true")),
				"school.security.public-metrics");
		assertSingleProblem(problems("prod", (env) -> env.setProperty("school.security.public-api-docs", "true")),
				"school.security.public-api-docs");
		assertThat(problems("staging", (env) -> env.setProperty("school.security.public-api-docs", "true"))).isEmpty();
	}

	@Test
	void secretValuesAreNeverPartOfAMessage() {
		MockEnvironment env = valid("staging");
		env.setProperty("spring.datasource.password", "school");
		env.setProperty("school.auth.otp.pepper", "local-development-pepper-not-a-secret-0123456789");
		env.setProperty("school.storage.secret-key", "school-dev-secret");
		env.setProperty("school.auth.jwt.keys", "{\"kty\":\"garbage-private-material\"}");
		String all = String.join("\n", problems(env));
		assertThat(all).doesNotContain("local-development-pepper")
			.doesNotContain("school-dev-secret")
			.doesNotContain("garbage-private-material")
			.doesNotContain("password=school");
	}

	@Test
	void exceptionMessageListsEveryProblem() {
		DeploymentConfigurationException ex = new DeploymentConfigurationException("staging", List.of("A is bad", "B is bad"));
		assertThat(ex.getMessage()).contains("[staging]").contains("2 problems").contains("1. A is bad").contains("2. B is bad");
	}

	@Test
	void postProcessorThrowsForDeployedProfilesOnly() {
		var processor = new DeploymentConfigPostProcessor();
		var bad = new org.springframework.mock.env.MockEnvironment();
		bad.setActiveProfiles("prod");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.postProcessEnvironment(bad, null))
			.isInstanceOf(DeploymentConfigurationException.class)
			.hasMessageContaining("DB_URL");
		var local = new org.springframework.mock.env.MockEnvironment();
		local.setActiveProfiles("local");
		processor.postProcessEnvironment(local, null);
	}

	// ----------------------------------------------------------------- fixtures

	private static ECKey newKey(boolean withPrivate) {
		try {
			ECKey key = new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString())
				.algorithm(JWSAlgorithm.ES256)
				.keyUse(KeyUse.SIGNATURE)
				.generate();
			return withPrivate ? key : key.toPublicJWK();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String newJwkSet(boolean withPrivate) {
		return new JWKSet(newKey(withPrivate)).toString(false);
	}

}

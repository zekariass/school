package com.nemaz.school.system.startup;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nemaz.school.SchoolBackendApplication;
import com.nemaz.school.system.DatabaseMigrator;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Boots the real application in the {@code staging} profile (own contexts, not the shared
 * {@code @IntegrationTest} one): a bad configuration fails with one message that lists every problem; the
 * migrate mode applies the schema once and is safe to run concurrently; the application then starts with Flyway
 * validate-only, answers the probes without details and reports its database and Redis in readiness.
 */
class DeployedProfileStartupTest {

	/** The validator wants at least 12 characters; the container's own password is "test". */
	private static final String DB_PASSWORD = "a-long-test-password";

	static PostgreSQLContainer postgres;

	static GenericContainer<?> redis;

	/** A non-loopback address of this machine: the validator rightly refuses localhost endpoints. */
	static String hostAddress;

	@BeforeAll
	static void start() throws Exception {
		hostAddress = nonLoopbackAddress();
		assumeTrue(hostAddress != null, "no non-loopback network interface available");
		postgres = new PostgreSQLContainer("postgres:18");
		redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
		postgres.start();
		redis.start();
		try (Connection connection = DriverManager.getConnection(
				"jdbc:postgresql://" + hostAddress + ":" + postgres.getMappedPort(5432) + "/postgres",
				postgres.getUsername(), postgres.getPassword()); Statement statement = connection.createStatement()) {
			statement.execute("alter role \"" + postgres.getUsername() + "\" with password '" + DB_PASSWORD + "'");
		}
	}

	@AfterAll
	static void stop() {
		if (redis != null) {
			redis.stop();
		}
		if (postgres != null) {
			postgres.stop();
		}
	}

	@Test
	void stagingWithMissingConfigurationFailsFastWithOneMessageListingEveryProblem() {
		SpringApplicationBuilder builder = new SpringApplicationBuilder(SchoolBackendApplication.class)
			.web(WebApplicationType.NONE)
			.profiles("staging");
		assertThatThrownBy(() -> builder.run("--spring.main.banner-mode=off"))
			.isInstanceOfSatisfying(DeploymentConfigurationException.class, (ex) -> {
				assertThat(ex.getMessage()).contains("[staging]");
				assertThat(ex.problems()).hasSizeGreaterThanOrEqualTo(10);
				assertThat(ex.getMessage()).contains("DB_URL", "DB_USERNAME", "DB_PASSWORD", "REDIS_URL",
						"CORS_ALLOWED_ORIGINS", "SCHOOL_AUTH_JWT_KEYS", "SCHOOL_AUTH_OTP_PEPPER",
						"SCHOOL_SMS_PROVIDER", "SCHOOL_PUSH_PROVIDER", "SCHOOL_GUARDIANS_INVITE_BASE_URL",
						"SCHOOL_STORAGE_ENDPOINT", "SCHOOL_STORAGE_BUCKET", "SCHOOL_SAFEGUARDING_ENCRYPTION_KEYS_K1",
						"SCHOOL_SAFEGUARDING_ENCRYPTION_ACTIVE_KEY_ID");
				FailureAnalysis analysis = new DeploymentConfigFailureAnalyzer().analyze(ex);
				assertThat(analysis.getDescription()).startsWith("Invalid configuration for profile(s) [staging]");
				assertThat(analysis.getAction()).contains("never printed");
			});
	}

	@Test
	void productionWithDevelopmentValuesIsRefusedWithoutEchoingThem() {
		SpringApplicationBuilder builder = new SpringApplicationBuilder(SchoolBackendApplication.class)
			.web(WebApplicationType.NONE)
			.profiles("prod");
		String devPepper = "local-development-pepper-not-a-secret-0123456789";
		assertThatThrownBy(() -> builder.run("--spring.main.banner-mode=off",
				"--spring.datasource.url=jdbc:postgresql://localhost:5442/school", "--spring.datasource.username=school",
				"--spring.datasource.password=school", "--school.auth.otp.pepper=" + devPepper,
				"--school.sms.provider=console", "--school.sms.allow-console=true", "--school.push.provider=log",
				"--school.storage.access-key=school-dev", "--school.storage.secret-key=school-dev-secret"))
			.isInstanceOfSatisfying(DeploymentConfigurationException.class, (ex) -> {
				assertThat(ex.getMessage()).contains("DB_URL points at localhost", "DB_PASSWORD is a default",
						"SCHOOL_AUTH_OTP_PEPPER is a documented sample", "SCHOOL_SMS_PROVIDER 'console'",
						"SCHOOL_PUSH_PROVIDER 'log'", "SCHOOL_STORAGE_ACCESS_KEY is the development");
				assertThat(ex.getMessage()).doesNotContain(devPepper).doesNotContain("school-dev-secret");
			});
	}

	@Test
	void migrateModeAppliesTheSchemaOnceAndConcurrentRunsAreSafe() throws Exception {
		String db = createDatabase("migrate_once");
		Map<String, String> env = databaseEnvironment(db);

		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			List<Callable<Integer>> runs = Collections.nCopies(3, () -> DatabaseMigrator.run(env, silent()));
			for (Future<Integer> exit : pool.invokeAll(runs)) {
				assertThat(exit.get()).isEqualTo(DatabaseMigrator.EXIT_OK);
			}
		}
		finally {
			pool.shutdownNow();
		}
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		assertThat(DatabaseMigrator.run(env, new PrintStream(captured, true, StandardCharsets.UTF_8)))
			.isEqualTo(DatabaseMigrator.EXIT_OK);
		assertThat(captured.toString(StandardCharsets.UTF_8)).contains("0 migration(s) applied");
		try (Connection connection = DriverManager.getConnection(jdbcUrl(db), postgres.getUsername(), DB_PASSWORD);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("select count(*) from flyway_schema_history where success")) {
			rows.next();
			assertThat(rows.getInt(1)).isGreaterThan(10);
		}
	}

	@Test
	void migrateModeReportsBadConfigurationAndFailures() {
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		assertThat(DatabaseMigrator.run(Map.of(), new PrintStream(captured, true, StandardCharsets.UTF_8)))
			.isEqualTo(DatabaseMigrator.EXIT_BAD_CONFIG);
		assertThat(captured.toString(StandardCharsets.UTF_8)).contains("DB_URL", "DB_USERNAME", "DB_PASSWORD");

		Map<String, String> wrongPassword = Map.of("DB_URL", jdbcUrl("postgres"), "DB_USERNAME",
				postgres.getUsername(), "DB_PASSWORD", "definitely-wrong-password");
		captured.reset();
		assertThat(DatabaseMigrator.run(wrongPassword, new PrintStream(captured, true, StandardCharsets.UTF_8)))
			.isEqualTo(DatabaseMigrator.EXIT_FAILED);
		assertThat(captured.toString(StandardCharsets.UTF_8)).contains("Migration FAILED")
			.doesNotContain("definitely-wrong-password");
	}

	@Test
	void migrateIsRequestedByArgumentOrEnvironment() {
		assertThat(DatabaseMigrator.requested(new String[] { "--migrate" }, Map.of())).isTrue();
		assertThat(DatabaseMigrator.requested(new String[] {}, Map.of("SCHOOL_MIGRATE_ONLY", "true"))).isTrue();
		assertThat(DatabaseMigrator.requested(new String[] {}, Map.of())).isFalse();
		assertThat(DatabaseMigrator.requested(new String[] { "--other" }, Map.of("SCHOOL_MIGRATE_ONLY", "false")))
			.isFalse();
	}

	@Test
	void stagingStartsOnlyOnAMigratedSchemaAndExposesProbesWithoutDetails() throws Exception {
		String db = createDatabase("staging_boot");
		// 1. un-migrated database: validate-only mode refuses to start and tells what to do
		assertThatThrownBy(() -> boot(db).close()).hasStackTraceContaining("Run the migrate job first");

		// 2. after the migrate job the same configuration starts
		assertThat(DatabaseMigrator.run(databaseEnvironment(db), silent())).isEqualTo(DatabaseMigrator.EXIT_OK);
		try (ConfigurableApplicationContext context = boot(db, "--sentry.dsn=https://publickey@o0.ingest.example.org/1")) {
			// error tracking is on only because a DSN is set, and is configured to send no personal data
			assertThat(io.sentry.Sentry.isEnabled()).isTrue();
			var options = io.sentry.Sentry.getCurrentScopes().getOptions();
			assertThat(options.isSendDefaultPii()).isFalse();
			assertThat(options.getMaxRequestBodySize()).isEqualTo(io.sentry.SentryOptions.RequestSize.NONE);
			assertThat(options.getBeforeSend()).isNotNull();
			assertThat(options.getBeforeBreadcrumb()).isNotNull();
			String base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
			HttpClient http = HttpClient.newHttpClient();

			HttpResponse<String> liveness = get(http, base + "/actuator/health/liveness");
			assertThat(liveness.statusCode()).isEqualTo(200);
			assertThat(liveness.body()).isEqualTo("{\"status\":\"UP\"}");

			HttpResponse<String> readiness = get(http, base + "/actuator/health/readiness");
			assertThat(readiness.statusCode()).isEqualTo(200);
			assertThat(readiness.body()).isEqualTo("{\"status\":\"UP\"}");

			// the aggregate has no component details either
			assertThat(get(http, base + "/actuator/health").body()).doesNotContain("components", "jdbc", "postgres");

			HttpResponse<String> info = get(http, base + "/actuator/info");
			assertThat(info.statusCode()).isEqualTo(200);
			assertThat(info.body()).contains("\"app\"", "\"commit\":\"unknown\"", "\"build\"", "school-backend");

			// ECS JSON logging is on in deployed profiles
			assertThat(context.getEnvironment().getProperty("logging.structured.format.console")).isEqualTo("ecs");

			// Redis is part of readiness: stop it and the instance reports DOWN (503), liveness stays UP
			redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
			try {
				HttpResponse<String> down = get(http, base + "/actuator/health/readiness");
				assertThat(down.statusCode()).isEqualTo(503);
				assertThat(down.body()).isEqualTo("{\"status\":\"DOWN\"}");
				assertThat(get(http, base + "/actuator/health/liveness").statusCode()).isEqualTo(200);
			}
			finally {
				redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
			}
		}
		finally {
			io.sentry.Sentry.close();
		}
	}

	// ----------------------------------------------------------------- helpers

	private static ConfigurableApplicationContext boot(String database, String... extra) throws Exception {
		String redisUrl = "redis://" + hostAddress + ":" + redis.getMappedPort(6379);
		List<String> args = new java.util.ArrayList<>(List.of("--server.port=0", "--spring.main.banner-mode=off", "--spring.datasource.url=" + jdbcUrl(database),
					"--spring.datasource.username=" + postgres.getUsername(),
					"--spring.datasource.password=" + DB_PASSWORD, "--spring.data.redis.timeout=2s",
					"--spring.data.redis.url=" + redisUrl,
					"--school.cors.allowed-origins=https://app.example.org",
					"--school.auth.jwt.keys=" + jwkSet(), "--school.auth.otp.pepper=Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZg",
					"--school.safeguarding.encryption.active-key-id=k1",
					"--school.safeguarding.encryption.keys.k1=UTd4IW1QMiN2TDkka1I0JnRXNip6Tjhe" + "YkgzQGNGNSU=",
					"--school.attendance.secrets.active-key-id=k1",
					"--school.attendance.secrets.keys.k1=SGc0IXNENyNqSzIkcFc5JnhCNSpuTTFeY1Y4QHpRMyU=",
					"--school.attendance.secrets.qr-key=clQ2IXlVMyNpTzgkYVMxJmRGNCpnSDdeakwyQGtaOSU=",
					"--school.sms.provider=console", "--school.sms.allow-console=true", "--school.push.provider=expo",
					"--school.guardians.invite-base-url=https://app.example.org",
					"--school.storage.endpoint=https://acct.r2.cloudflarestorage.com",
					"--school.storage.bucket=school-staging-media", "--school.storage.access-key=AKIA0000TESTKEY",
					"--school.storage.secret-key=k3y-secret-value-0987654321", "--school.scheduling.enabled=false",
					"--management.endpoint.health.probes.enabled=true"));
		args.addAll(List.of(extra));
		return new SpringApplicationBuilder(SchoolBackendApplication.class).profiles("staging")
			.run(args.toArray(String[]::new));
	}

	private static Map<String, String> databaseEnvironment(String database) {
		return Map.of("DB_URL", jdbcUrl(database), "DB_USERNAME", postgres.getUsername(), "DB_PASSWORD",
				DB_PASSWORD);
	}

	private static String jdbcUrl(String database) {
		return "jdbc:postgresql://" + hostAddress + ":" + postgres.getMappedPort(5432) + "/" + database;
	}

	private static String createDatabase(String name) throws Exception {
		try (Connection connection = DriverManager.getConnection(jdbcUrl("postgres"), postgres.getUsername(),
				DB_PASSWORD); Statement statement = connection.createStatement()) {
			statement.execute("create database " + name);
		}
		return name;
	}

	private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private static PrintStream silent() {
		return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
	}

	private static String jwkSet() throws Exception {
		return new JWKSet(new ECKeyGenerator(Curve.P_256).keyID("startup-test")
			.algorithm(JWSAlgorithm.ES256)
			.keyUse(KeyUse.SIGNATURE)
			.generate()).toString(false);
	}

	private static String nonLoopbackAddress() throws Exception {
		for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
				continue;
			}
			for (InetAddress address : Collections.list(nic.getInetAddresses())) {
				if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
					return address.getHostAddress();
				}
			}
		}
		return null;
	}

}

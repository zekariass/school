package com.nemaz.school.system.startup;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Pre-flight check of the configuration of a deployed environment. It reads the fully resolved
 * {@link Environment} before any bean exists and returns <em>every</em> problem at once, so one
 * failed start tells the operator everything to fix. Values of secrets are never part of a message:
 * only the setting (and its environment variable) and what is wrong with it.
 *
 * <ul>
 * <li>{@code local} and {@code test} profiles: no checks (development secrets are expected there).</li>
 * <li>{@code staging} and {@code prod}: the full rule set (database, Redis, CORS, keys, pepper,
 * providers, storage, no development credentials). {@code prod} is stricter where noted.</li>
 * <li>Any other profile (for example none, as in a bare {@code docker run}): only the
 * connection settings that would otherwise silently fall back to localhost (database, Redis).</li>
 * </ul>
 */
public final class DeploymentConfigValidator {

	/** What kind of environment the active profiles describe. */
	public enum Mode {

		/** {@code local} or {@code test}: nothing is checked. */
		DEVELOPMENT,
		/** No deployed profile: only the localhost-fallback settings are checked. */
		OTHER,
		/** {@code staging}. */
		STAGING,
		/** {@code prod}. */
		PRODUCTION

	}

	static final int MIN_PEPPER_LENGTH = 32;

	static final int MIN_DB_PASSWORD_LENGTH = 12;

	/** Documented sample and development values that must never run in a deployed environment. */
	private static final Set<String> KNOWN_PEPPERS = Set.of("local-development-pepper-not-a-secret-0123456789",
			"test-pepper-not-a-secret-0123456789abcdef", "change-me-to-a-long-random-secret-0123456789");

	private static final List<String> PLACEHOLDER_MARKERS = List.of("change-me", "changeme", "not-a-secret",
			"replace-me", "replaceme", "placeholder", "your-secret", "example", "xxxxxxxx", "local-development",
			"0123456789abcdef");

	/** The development and test encryption keys of safeguarding incidents (they are in the repository): never deployed. */
	private static final Set<String> KNOWN_SAFEGUARDING_KEYS = Set.of("ZpMvTuBbu45fW1DPsfAjLbkM70qGZACjbSFvt31V1WE=",
			"3XW2ygDFVBin17tAEfBguxoWYqoElaHNMzBdJ/X10Lc=");

	private static final Pattern SAFEGUARDING_KEY_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

	private static final Set<String> WEAK_DB_PASSWORDS = Set.of("school", "postgres", "password", "changeme",
			"admin", "root", "secret", "123456", "12345678", "qwerty");

	private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1", "0.0.0.0",
			"host.docker.internal");

	private static final Pattern JDBC_HOSTS = Pattern.compile("^jdbc:postgresql://([^/?]+)");

	private final Environment env;

	private final Mode mode;

	private final List<String> problems = new ArrayList<>();

	private DeploymentConfigValidator(Environment env, Mode mode) {
		this.env = env;
		this.mode = mode;
	}

	/** Decides the mode from the active profiles. */
	public static Mode modeOf(Environment env) {
		if (env.acceptsProfiles(Profiles.of("staging", "prod"))) {
			return env.acceptsProfiles(Profiles.of("prod")) ? Mode.PRODUCTION : Mode.STAGING;
		}
		if (env.acceptsProfiles(Profiles.of("local", "test"))) {
			return Mode.DEVELOPMENT;
		}
		return Mode.OTHER;
	}

	/** All problems of {@code env} for the mode its profiles imply; empty when it is acceptable. */
	public static List<String> validate(Environment env) {
		return validate(env, modeOf(env));
	}

	static List<String> validate(Environment env, Mode mode) {
		DeploymentConfigValidator validator = new DeploymentConfigValidator(env, mode);
		validator.run();
		return List.copyOf(validator.problems);
	}

	private void run() {
		switch (this.mode) {
			case DEVELOPMENT -> {
			}
			case OTHER -> {
				database();
				redis();
			}
			case STAGING, PRODUCTION -> {
				profiles();
				database();
				redis();
				cors();
				jwt();
				pepper();
				safeguardingKeys();
				providers();
				invitations();
				storage();
				bootstrapAdmin();
				exposure();
			}
		}
	}

	private boolean production() {
		return this.mode == Mode.PRODUCTION;
	}

	// ------------------------------------------------------------------ rules

	private void profiles() {
		if (this.env.acceptsProfiles(Profiles.of("local", "test"))) {
			problem("spring.profiles.active", "must not include 'local' or 'test' together with 'staging' or "
					+ "'prod' (they carry development credentials)");
		}
	}

	private void database() {
		String url = text("spring.datasource.url");
		String user = text("spring.datasource.username");
		String password = text("spring.datasource.password");
		if (url == null) {
			problem("DB_URL", "is not set (JDBC URL, e.g. jdbc:postgresql://<host>:5432/<database>)");
		}
		else if (!url.startsWith("jdbc:postgresql://")) {
			problem("DB_URL", "must be a PostgreSQL JDBC URL starting with jdbc:postgresql://");
		}
		else if (this.mode != Mode.OTHER && hasLoopbackHost(hostsOfJdbc(url))) {
			problem("DB_URL", "points at localhost; use the database host reachable from the container");
		}
		if (user == null) {
			problem("DB_USERNAME", "is not set");
		}
		if (password == null) {
			problem("DB_PASSWORD", "is not set");
		}
		if (this.mode == Mode.OTHER || password == null) {
			return;
		}
		if (WEAK_DB_PASSWORDS.contains(password.toLowerCase(Locale.ROOT)) || password.equals(user)) {
			problem("DB_PASSWORD", "is a default or development credential (as in infra/docker-compose.yml)");
		}
		else if (password.length() < MIN_DB_PASSWORD_LENGTH) {
			problem("DB_PASSWORD", "is too short (minimum " + MIN_DB_PASSWORD_LENGTH + " characters)");
		}
	}

	private void redis() {
		String url = text("spring.data.redis.url");
		if (url == null) {
			problem("REDIS_URL", "is not set; without it Spring would silently use localhost:6379 "
					+ "(e.g. redis://<host>:6379 or rediss://<user>:<password>@<host>:6379)");
			return;
		}
		if (!(url.startsWith("redis://") || url.startsWith("rediss://"))) {
			problem("REDIS_URL", "must start with redis:// or rediss://");
			return;
		}
		String host = hostOf(url);
		if (host != null && this.mode != Mode.OTHER && isLoopback(host)) {
			problem("REDIS_URL", "points at localhost; use the Redis host reachable from the container");
		}
	}

	private void cors() {
		List<String> origins = list("school.cors.allowed-origins");
		if (origins.isEmpty()) {
			problem("CORS_ALLOWED_ORIGINS", "is empty; list the web app origin(s), comma-separated, "
					+ "e.g. https://app.example.org");
			return;
		}
		for (String origin : origins) {
			if (origin.contains("*")) {
				problem("CORS_ALLOWED_ORIGINS", "must not contain a wildcard (*); list exact origins");
			}
			else if (!origin.startsWith("https://")) {
				problem("CORS_ALLOWED_ORIGINS", "every origin must use https:// (found: " + origin + ")");
			}
			else if (!isBareOrigin(origin)) {
				problem("CORS_ALLOWED_ORIGINS", "origins must be scheme and host only, without a path or "
						+ "trailing slash (found: " + origin + ")");
			}
		}
	}

	private void jwt() {
		String keys = text("school.auth.jwt.keys");
		if (bool("school.auth.jwt.allow-ephemeral-key")) {
			problem("school.auth.jwt.allow-ephemeral-key", "must be false (an ephemeral key invalidates every "
					+ "token on restart and breaks multiple instances)");
		}
		if (keys == null) {
			problem("SCHOOL_AUTH_JWT_KEYS", "is not set (JWK Set with an EC P-256 private key; "
					+ "npm run jwk:generate -w school-backend)");
			return;
		}
		JWKSet set;
		try {
			set = JWKSet.parse(keys);
		}
		catch (ParseException | RuntimeException ex) {
			problem("SCHOOL_AUTH_JWT_KEYS", "is not a valid JWK Set document ({\"keys\":[...]})");
			return;
		}
		if (set.getKeys().isEmpty()) {
			problem("SCHOOL_AUTH_JWT_KEYS", "contains no keys");
			return;
		}
		int index = 0;
		for (JWK jwk : set.getKeys()) {
			index++;
			if (!(jwk instanceof ECKey ec) || !Curve.P_256.equals(ec.getCurve())) {
				problem("SCHOOL_AUTH_JWT_KEYS", "key " + index + " is not an EC P-256 key");
				continue;
			}
			if (ec.getKeyID() == null || ec.getKeyID().isBlank()) {
				problem("SCHOOL_AUTH_JWT_KEYS", "key " + index + " has no \"kid\"");
			}
			if (ec.getAlgorithm() != null && !JWSAlgorithm.ES256.equals(ec.getAlgorithm())) {
				problem("SCHOOL_AUTH_JWT_KEYS", "key " + index + " must have alg ES256");
			}
		}
		if (set.getKeys().getFirst() instanceof ECKey first && !first.isPrivate()) {
			problem("SCHOOL_AUTH_JWT_KEYS", "the first key signs tokens and needs its private part (\"d\")");
		}
	}

	private void pepper() {
		String pepper = text("school.auth.otp.pepper");
		if (pepper == null) {
			problem("SCHOOL_AUTH_OTP_PEPPER", "is not set (at least " + MIN_PEPPER_LENGTH + " random characters)");
			return;
		}
		if (pepper.length() < MIN_PEPPER_LENGTH) {
			problem("SCHOOL_AUTH_OTP_PEPPER", "is too short (minimum " + MIN_PEPPER_LENGTH + " characters)");
		}
		if (KNOWN_PEPPERS.contains(pepper) || containsMarker(pepper)) {
			problem("SCHOOL_AUTH_OTP_PEPPER", "is a documented sample or development value; generate a random one");
		}
		else if (pepper.chars().distinct().count() < 8) {
			problem("SCHOOL_AUTH_OTP_PEPPER", "has too little variety to be random; generate a random one");
		}
	}

	/**
	 * The AES-256 keys of safeguarding incidents (docs/Safeguarding_Rules.md S4.4): at least one valid key, the active one among
	 * them, none of the keys that ship in the repository. Values are never echoed.
	 */
	private void safeguardingKeys() {
		String active = text("school.safeguarding.encryption.active-key-id");
		Map<String, String> keys = safeguardingKeyMap();
		if (keys.isEmpty()) {
			problem("SCHOOL_SAFEGUARDING_ENCRYPTION_KEYS_K1", "is not set (base64 of 32 random bytes: "
					+ "openssl rand -base64 32; incidents of children cannot be stored without a key)");
		}
		if (active == null) {
			problem("SCHOOL_SAFEGUARDING_ENCRYPTION_ACTIVE_KEY_ID", "is not set (the id of the key new text is encrypted with, "
					+ "e.g. k1)");
		}
		else if (!keys.isEmpty() && !keys.containsKey(active)) {
			problem("SCHOOL_SAFEGUARDING_ENCRYPTION_ACTIVE_KEY_ID", "names a key that is not configured");
		}
		for (Map.Entry<String, String> entry : keys.entrySet()) {
			String id = entry.getKey();
			if (!SAFEGUARDING_KEY_ID.matcher(id).matches()) {
				problem("SCHOOL_SAFEGUARDING_ENCRYPTION_KEYS", "has a key id that is not valid (letters, digits, _ and -)");
				continue;
			}
			String name = "SCHOOL_SAFEGUARDING_ENCRYPTION_KEYS_" + id.toUpperCase(Locale.ROOT);
			byte[] raw;
			try {
				raw = Base64.getDecoder().decode(entry.getValue());
			}
			catch (IllegalArgumentException ex) {
				problem(name, "is not valid base64");
				continue;
			}
			if (raw.length != 32) {
				problem(name, "must be 32 bytes (AES-256): openssl rand -base64 32");
			}
			else if (KNOWN_SAFEGUARDING_KEYS.contains(entry.getValue())) {
				problem(name, "is a development or test key that ships in the repository; generate a random one");
			}
			else {
				Set<Byte> distinct = new HashSet<>();
				for (byte b : raw) {
					distinct.add(b);
				}
				if (distinct.size() < 8) {
					problem(name, "has too little variety to be random; generate a random one");
				}
			}
		}
	}

	private Map<String, String> safeguardingKeyMap() {
		Map<String, String> keys = new LinkedHashMap<>();
		try {
			Map<String, String> bound = Binder.get(this.env)
				.bind("school.safeguarding.encryption.keys", Bindable.mapOf(String.class, String.class))
				.orElse(Map.of());
			bound.forEach((id, value) -> {
				if (value != null && !value.isBlank() && !value.contains("${")) {
					keys.put(id, value.trim());
				}
			});
		}
		catch (RuntimeException ex) {
			// an unreadable map counts as no keys
		}
		return keys;
	}

	private void providers() {
		String sms = lower(text("school.sms.provider"));
		if (sms == null) {
			problem("SCHOOL_SMS_PROVIDER", "is not set");
		}
		else if ("console".equals(sms)) {
			if (production()) {
				problem("SCHOOL_SMS_PROVIDER", "'console' only writes codes to the log and is not allowed in "
						+ "production; configure a real SMS provider");
			}
			else if (!bool("school.sms.allow-console")) {
				problem("SCHOOL_SMS_PROVIDER", "'console' in staging also needs SCHOOL_SMS_ALLOW_CONSOLE=true "
						+ "(one-time codes are then written to the log)");
			}
		}
		String push = lower(text("school.push.provider"));
		if (push == null) {
			problem("SCHOOL_PUSH_PROVIDER", "is not set (expo or log)");
		}
		else if ("log".equals(push)) {
			if (production()) {
				problem("SCHOOL_PUSH_PROVIDER", "'log' sends nothing and is not allowed in production; use 'expo'");
			}
			else if (!bool("school.push.allow-log")) {
				problem("SCHOOL_PUSH_PROVIDER", "'log' in staging also needs SCHOOL_PUSH_ALLOW_LOG=true");
			}
		}
		else if (!"expo".equals(push)) {
			problem("SCHOOL_PUSH_PROVIDER", "must be 'expo' or 'log'");
		}
	}

	private void invitations() {
		String base = text("school.guardians.invite-base-url");
		if (base == null) {
			problem("SCHOOL_GUARDIANS_INVITE_BASE_URL", "is not set (the web app origin, https)");
			return;
		}
		if (!base.startsWith("https://")) {
			problem("SCHOOL_GUARDIANS_INVITE_BASE_URL", "must be an https:// URL");
		}
		else if (hasLoopbackHost(List.of(nullToEmpty(hostOf(base))))) {
			problem("SCHOOL_GUARDIANS_INVITE_BASE_URL", "points at localhost");
		}
	}

	private void storage() {
		String endpoint = text("school.storage.endpoint");
		String publicEndpoint = text("school.storage.public-endpoint");
		String bucket = text("school.storage.bucket");
		String accessKey = text("school.storage.access-key");
		String secretKey = text("school.storage.secret-key");
		if (endpoint == null) {
			problem("SCHOOL_STORAGE_ENDPOINT", "is not set (R2: https://<account>.r2.cloudflarestorage.com)");
		}
		else {
			storageEndpoint("SCHOOL_STORAGE_ENDPOINT", endpoint, production());
		}
		if (publicEndpoint != null) {
			// Presigned URLs are opened by browsers and phones: they must be reachable and https.
			storageEndpoint("SCHOOL_STORAGE_PUBLIC_ENDPOINT", publicEndpoint, true);
		}
		else if (endpoint != null && !endpoint.startsWith("https://")) {
			problem("SCHOOL_STORAGE_PUBLIC_ENDPOINT", "is not set and the endpoint is not https; clients put this "
					+ "host in presigned URLs, so it must be a public https:// address");
		}
		if (bucket == null) {
			problem("SCHOOL_STORAGE_BUCKET", "is not set");
		}
		else if (Set.of("school-media-dev", "school-media-test").contains(bucket)) {
			problem("SCHOOL_STORAGE_BUCKET", "is a development bucket name");
		}
		if (accessKey == null) {
			problem("SCHOOL_STORAGE_ACCESS_KEY", "is not set");
		}
		else if ("school-dev".equals(accessKey)) {
			problem("SCHOOL_STORAGE_ACCESS_KEY", "is the development credential of infra/docker-compose.yml");
		}
		if (secretKey == null) {
			problem("SCHOOL_STORAGE_SECRET_KEY", "is not set");
		}
		else if ("school-dev-secret".equals(secretKey)) {
			problem("SCHOOL_STORAGE_SECRET_KEY", "is the development credential of infra/docker-compose.yml");
		}
		if (bool("school.storage.create-bucket")) {
			problem("SCHOOL_STORAGE_CREATE_BUCKET", "must be false; buckets, CORS and lifecycle rules are "
					+ "provisioned with the platform (docs/research/R2_Compatibility.md)");
		}
	}

	private void storageEndpoint(String name, String value, boolean requireHttps) {
		if (requireHttps && !value.startsWith("https://")) {
			problem(name, "must be an https:// URL");
		}
		else if (!(value.startsWith("https://") || value.startsWith("http://"))) {
			problem(name, "must be an http(s) URL");
		}
		String host = hostOf(value);
		if (host != null && isLoopback(host)) {
			problem(name, "points at localhost");
		}
	}

	private void bootstrapAdmin() {
		String phone = text("school.bootstrap.admin.phone");
		String name = text("school.bootstrap.admin.name");
		if (phone != null && phone.replaceAll("\\D", "").endsWith("900000001")) {
			problem("SCHOOL_BOOTSTRAP_ADMIN_PHONE", "is the development administrator of the local profile; "
					+ "set the real first administrator explicitly");
		}
		if (name != null && "Dev Admin".equalsIgnoreCase(name)) {
			problem("SCHOOL_BOOTSTRAP_ADMIN_NAME", "is the development administrator name");
		}
	}

	private void exposure() {
		if (production()) {
			if (bool("school.security.public-metrics")) {
				problem("school.security.public-metrics", "must be false in production (Prometheus metrics "
						+ "are reached on a private network only)");
			}
			if (bool("school.security.public-api-docs")) {
				problem("school.security.public-api-docs", "must be false in production");
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	private void problem(String setting, String message) {
		this.problems.add(setting + " " + message);
	}

	/** Resolved, trimmed value; {@code null} when absent, blank or a placeholder that could not be resolved. */
	private String text(String key) {
		try {
			String value = this.env.getProperty(key);
			if (value == null || value.isBlank() || value.contains("${")) {
				return null;
			}
			return value.trim();
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private boolean bool(String key) {
		String value = text(key);
		return value != null && Boolean.parseBoolean(value);
	}

	private List<String> list(String key) {
		try {
			return Binder.get(this.env)
				.bind(key, String[].class)
				.map((values) -> Arrays.stream(values).map(String::trim).filter((value) -> !value.isEmpty()).toList())
				.orElse(List.of());
		}
		catch (RuntimeException ex) {
			return List.of();
		}
	}

	private static String lower(String value) {
		return (value != null) ? value.toLowerCase(Locale.ROOT) : null;
	}

	private static String nullToEmpty(String value) {
		return (value != null) ? value : "";
	}

	private static boolean containsMarker(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		return PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains);
	}

	private static boolean hasLoopbackHost(List<String> hosts) {
		return hosts.stream().anyMatch(DeploymentConfigValidator::isLoopback);
	}

	private static boolean isLoopback(String host) {
		String lower = host.toLowerCase(Locale.ROOT);
		return LOOPBACK_HOSTS.contains(lower) || lower.startsWith("127.");
	}

	private static List<String> hostsOfJdbc(String url) {
		Matcher matcher = JDBC_HOSTS.matcher(url);
		if (!matcher.find()) {
			return List.of();
		}
		List<String> hosts = new ArrayList<>();
		for (String part : matcher.group(1).split(",")) {
			String host = part;
			if (host.startsWith("[")) {
				int end = host.indexOf(']');
				host = (end > 0) ? host.substring(0, end + 1) : host;
			}
			else {
				int colon = host.indexOf(':');
				host = (colon >= 0) ? host.substring(0, colon) : host;
			}
			hosts.add(host);
		}
		return hosts;
	}

	private static String hostOf(String url) {
		try {
			return new URI(url).getHost();
		}
		catch (URISyntaxException ex) {
			return null;
		}
	}

	private static boolean isBareOrigin(String origin) {
		try {
			URI uri = new URI(origin);
			String path = uri.getRawPath();
			return uri.getHost() != null && (path == null || path.isEmpty()) && uri.getRawQuery() == null
					&& uri.getRawFragment() == null && uri.getRawUserInfo() == null;
		}
		catch (URISyntaxException ex) {
			return false;
		}
	}

}

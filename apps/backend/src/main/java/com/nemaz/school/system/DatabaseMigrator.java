package com.nemaz.school.system;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * One-shot migrate mode of the backend image: applies the Flyway migrations and exits with a status
 * code, without starting Spring, a web server, Redis or storage. A deploy runs it once, before the
 * new version takes traffic ({@code docker run ... school-backend --migrate}); the application itself
 * then only <em>validates</em> the schema in {@code staging} and {@code prod}
 * ({@code school.migrations.mode=validate}).
 *
 * <p>
 * Triggered by the argument {@code --migrate} or {@code SCHOOL_MIGRATE_ONLY=true}. Reads {@code DB_URL},
 * {@code DB_USERNAME} and {@code DB_PASSWORD}. Concurrent runs are safe: Flyway takes a database lock on
 * its history table, so a second runner waits and then finds nothing to do.
 *
 * <p>
 * Exit codes: {@code 0} migrated (or already up to date), {@code 1} the migration failed,
 * {@code 2} the connection settings are missing or invalid.
 */
public final class DatabaseMigrator {

	public static final int EXIT_OK = 0;

	public static final int EXIT_FAILED = 1;

	public static final int EXIT_BAD_CONFIG = 2;

	static final String LOCATION = "classpath:db/migration";

	private DatabaseMigrator() {
	}

	/** Whether the process was asked to migrate and exit. */
	public static boolean requested(String[] args, Map<String, String> environment) {
		for (String arg : args) {
			if ("--migrate".equals(arg)) {
				return true;
			}
		}
		return "true".equalsIgnoreCase(environment.get("SCHOOL_MIGRATE_ONLY"));
	}

	/** Runs the migrations configured by {@code environment}; returns the process exit code. */
	public static int run(Map<String, String> environment, PrintStream out) {
		List<String> problems = new ArrayList<>();
		String url = value(environment, "DB_URL");
		String user = value(environment, "DB_USERNAME");
		String password = value(environment, "DB_PASSWORD");
		if (url == null) {
			problems.add("DB_URL is not set");
		}
		else if (!url.startsWith("jdbc:postgresql://")) {
			problems.add("DB_URL must be a PostgreSQL JDBC URL starting with jdbc:postgresql://");
		}
		if (user == null) {
			problems.add("DB_USERNAME is not set");
		}
		if (password == null) {
			problems.add("DB_PASSWORD is not set");
		}
		if (!problems.isEmpty()) {
			out.println(summary(problems));
			return EXIT_BAD_CONFIG;
		}
		try {
			Flyway flyway = Flyway.configure()
				.dataSource(url, user, password)
				.locations(LOCATION)
				.connectRetries(3)
				.load();
			MigrateResult result = flyway.migrate();
			out.println("Migration finished: " + result.migrationsExecuted + " migration(s) applied, schema version "
					+ ((result.targetSchemaVersion != null) ? result.targetSchemaVersion : "unchanged"));
			return EXIT_OK;
		}
		catch (FlywayException ex) {
			// The message names the failing migration; connection passwords are not part of Flyway messages.
			out.println("Migration FAILED: " + ex.getMessage());
			return EXIT_FAILED;
		}
	}

	private static String summary(List<String> problems) {
		StringBuilder text = new StringBuilder("Invalid configuration for the migrate job: ").append(problems.size())
			.append(" problem(s)");
		problems.forEach((problem) -> text.append("\n  - ").append(problem));
		return text.toString();
	}

	private static String value(Map<String, String> environment, String name) {
		String value = environment.get(name);
		return (value == null || value.isBlank()) ? null : value.trim();
	}

}

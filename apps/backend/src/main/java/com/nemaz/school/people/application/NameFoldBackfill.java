package com.nemaz.school.people.application;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Fills {@code student_profiles.name_fold} for students that predate the column (the roster import's duplicate
 * detection compares names through it). Idempotent: it only touches rows whose key is still null, in small
 * batches; a student without a usable name gets an empty key so the loop ends.
 * New and changed students are handled by {@link StudentService}.
 */
@Component
class NameFoldBackfill implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(NameFoldBackfill.class);

	private static final int BATCH = 500;

	private final JdbcClient jdbc;

	NameFoldBackfill(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments args) {
		backfill();
	}

	/** Returns how many profiles were filled. */
	int backfill() {
		record Row(UUID id, String given, String middle, String family) {
		}
		int total = 0;
		List<Row> rows;
		do {
			rows = this.jdbc.sql("""
					select sp.user_id, u.given_name, u.middle_name, u.family_name
					from student_profiles sp join users u on u.id = sp.user_id
					where sp.name_fold is null order by sp.user_id limit :n""")
				.param("n", BATCH)
				.query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
				.list();
			for (Row row : rows) {
				String fold = StudentService.nameFold(row.given(), row.middle(), row.family());
				this.jdbc.sql("update student_profiles set name_fold = :f where user_id = :u")
					.param("f", (fold != null) ? fold : "")
					.param("u", row.id())
					.update();
			}
			total += rows.size();
		}
		while (rows.size() == BATCH);
		if (total > 0) {
			log.info("Filled the name key of {} students", total);
		}
		return total;
	}

}

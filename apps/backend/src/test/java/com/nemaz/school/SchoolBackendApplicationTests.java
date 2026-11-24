package com.nemaz.school;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SchoolBackendApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void contextLoadsAndFlywayCreatesPlatformTables() {
		assertThat(this.jdbc.queryForList(
				"select table_name from information_schema.tables where table_schema = 'public' order by 1",
				String.class))
			.contains("event_publication", "shedlock", "flyway_schema_history");
		assertThat(this.jdbc.queryForList("select extname from pg_extension", String.class))
			.contains("citext", "btree_gist", "pg_trgm");
	}

}

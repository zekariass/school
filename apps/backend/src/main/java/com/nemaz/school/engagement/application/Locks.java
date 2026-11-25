package com.nemaz.school.engagement.application;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Transaction-scoped advisory locks: serialize work on one logical key without a row to lock. */
final class Locks {

	private Locks() {
	}

	/** Blocks until the lock of {@code key} is free; held until the end of the current transaction. */
	static void advisory(JdbcClient jdbc, String key) {
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key).query().listOfRows();
	}

}

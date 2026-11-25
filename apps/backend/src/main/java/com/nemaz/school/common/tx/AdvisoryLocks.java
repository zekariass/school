package com.nemaz.school.common.tx;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Transaction-scoped PostgreSQL advisory locks: serialize work on one logical key (an idempotency key, a roll call)
 * without a row to lock. The lock is held until the end of the current transaction.
 */
public final class AdvisoryLocks {

	private AdvisoryLocks() {
	}

	/** Blocks until the lock of {@code key} is free. */
	public static void acquire(JdbcClient jdbc, String key) {
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key).query().listOfRows();
	}

}

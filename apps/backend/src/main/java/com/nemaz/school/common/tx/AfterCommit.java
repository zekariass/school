package com.nemaz.school.common.tx;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs work after the current transaction commits (or immediately when there is none):
 * cache evictions, deny-list writes and message delivery must not happen for a transaction
 * that rolls back. Unlike a {@code @TransactionalEventListener}, nothing is written to the
 * event publication registry, so it is safe for payloads that must never be persisted
 * (one-time codes).
 */
public final class AfterCommit {

	private AfterCommit() {
	}

	public static void run(Runnable work) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					work.run();
				}
			});
		}
		else {
			work.run();
		}
	}

}

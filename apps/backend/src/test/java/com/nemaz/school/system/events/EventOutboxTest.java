package com.nemaz.school.system.events;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.nemaz.school.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The transactional outbox (spec §91) end to end: events are recorded with the
 * publishing transaction, delivered after commit, and a failed delivery is redelivered
 * by EventPublicationMaintenance (at-least-once).
 */
@IntegrationTest
@Import(EventOutboxTest.ListenerConfig.class)
class EventOutboxTest {

	@Autowired
	ApplicationEventPublisher events;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	FlakyListener listener;

	@Autowired
	EventPublicationMaintenance maintenance;

	@Test
	void deliversAfterCommitAndRedeliversAfterFailure() {
		UUID id = UUID.randomUUID();
		this.listener.failNextAttempts(1);

		this.tx.executeWithoutResult((status) -> this.events.publishEvent(new ProbeEvent(id)));

		// First attempt fails: the publication stays incomplete in the outbox table.
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(this.listener.attempts(id)).isEqualTo(1);
			assertThat(incompleteCount(id)).isEqualTo(1);
		});

		// The maintenance job resubmits it; the second attempt succeeds and completes it.
		this.maintenance.resubmitIncomplete();

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(this.listener.deliveredIds()).contains(id);
			assertThat(incompleteCount(id)).isZero();
		});
	}

	@Test
	void rolledBackTransactionsPublishNothing() {
		UUID id = UUID.randomUUID();

		this.tx.executeWithoutResult((status) -> {
			this.events.publishEvent(new ProbeEvent(id));
			status.setRollbackOnly();
		});

		assertThat(this.jdbc.queryForObject("select count(*) from event_publication where serialized_event like ?",
				Integer.class, "%" + id + "%")).isZero();
		assertThat(this.listener.attempts(id)).isZero();
	}

	private int incompleteCount(UUID id) {
		return this.jdbc.queryForObject(
				"select count(*) from event_publication where completion_date is null and serialized_event like ?",
				Integer.class, "%" + id + "%");
	}

	public record ProbeEvent(UUID id) {
	}

	public static class FlakyListener {

		private final AtomicInteger failuresLeft = new AtomicInteger();

		private final java.util.Map<UUID, AtomicInteger> attempts = new java.util.concurrent.ConcurrentHashMap<>();

		private final java.util.Set<UUID> delivered = java.util.concurrent.ConcurrentHashMap.newKeySet();

		public void failNextAttempts(int count) {
			this.failuresLeft.set(count);
		}

		public int attempts(UUID id) {
			AtomicInteger count = this.attempts.get(id);
			return (count != null) ? count.get() : 0;
		}

		public java.util.Set<UUID> deliveredIds() {
			return this.delivered;
		}

		@ApplicationModuleListener
		public void on(ProbeEvent event) {
			this.attempts.computeIfAbsent(event.id(), (k) -> new AtomicInteger()).incrementAndGet();
			if (this.failuresLeft.getAndUpdate((n) -> Math.max(0, n - 1)) > 0) {
				throw new IllegalStateException("simulated listener failure");
			}
			this.delivered.add(event.id());
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ListenerConfig {

		@Bean
		FlakyListener flakyListener() {
			return new FlakyListener();
		}

	}

}

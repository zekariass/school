package com.nemaz.school.sync.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Children's data does not stay in the sync ledger: after {@code school.sync.payload-retention} the payload and the
 * result's {@code state} (who was marked what, who got points) are removed, keeping the status, the problem code, the
 * entity id and the version, so a replay of an old operation is still recognized as a DUPLICATE (just without a state to
 * show). After {@code school.sync.row-retention} the rows go too.
 */
@Component
public class SyncRetention {

	private static final Logger log = LoggerFactory.getLogger(SyncRetention.class);

	private static final int BATCH = 2000;

	private static final int MAX_BATCHES = 500;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final SyncProperties props;

	SyncRetention(JdbcClient jdbc, Clock clock, SyncProperties props) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.props = props;
	}

	/** @return the number of ledger rows purged or deleted (an Integer: ShedLock refuses primitive return types) */
	@Scheduled(cron = "0 20 3 * * *", zone = "UTC")
	@SchedulerLock(name = "sync.operations-retention", lockAtMostFor = "PT30M")
	public Integer purgeExpired() {
		return purge();
	}

	/** One pass of the nightly job; also what tests and operators call directly. */
	public Integer purge() {
		Instant now = this.clock.instant();
		int total = 0;
		for (int i = 0; i < MAX_BATCHES; i++) {
			int n = purgeBatch(now.minus(this.props.payloadRetention()));
			total += n;
			if (n < BATCH) {
				break;
			}
		}
		for (int i = 0; i < MAX_BATCHES; i++) {
			int n = deleteBatch(now.minus(this.props.rowRetention()));
			total += n;
			if (n < BATCH) {
				break;
			}
		}
		if (total > 0) {
			log.info("Sync ledger retention handled {} operations", total);
		}
		return total;
	}

	int purgeBatch(Instant before) {
		return this.jdbc.sql("""
				update sync_operations set payload = null, payload_purged_at = :now, result = result - 'state'
				where id in (select id from sync_operations where payload_purged_at is null and received_at < :before
				             order by received_at limit :n)""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("before", Timestamp.from(before))
			.param("n", BATCH)
			.update();
	}

	int deleteBatch(Instant before) {
		return this.jdbc.sql("""
				delete from sync_operations
				where id in (select id from sync_operations where received_at < :before order by received_at limit :n)""")
			.param("before", Timestamp.from(before))
			.param("n", BATCH)
			.update();
	}

}

package com.nemaz.school.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.nemaz.school.notifications.infrastructure.DeliveryStore;
import com.nemaz.school.notifications.infrastructure.DeliveryStore.DeliveryJob;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries push deliveries that failed (back-off 1 minute, then 5; at most 3 attempts in all) and
 * deliveries left QUEUED by an interrupted fan-out. Pushes for notifications over a day old are
 * dropped: they would only confuse.
 */
@Component
public class DeliveryRetryJob {

	static final Duration QUEUED_GRACE = Duration.ofMinutes(2);

	static final Duration FRESH_FOR = Duration.ofHours(24);

	private static final int BATCH = 200;

	private static final Logger log = LoggerFactory.getLogger(DeliveryRetryJob.class);

	private final DeliveryStore deliveries;

	private final PushDispatcher dispatcher;

	private final Clock clock;

	DeliveryRetryJob(DeliveryStore deliveries, PushDispatcher dispatcher, Clock clock) {
		this.deliveries = deliveries;
		this.dispatcher = dispatcher;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
	@SchedulerLock(name = "notifications.retry-deliveries", lockAtMostFor = "PT5M")
	public Integer retryDue() {
		Instant now = this.clock.instant();
		List<DeliveryJob> due = this.deliveries.due(now, now.minus(QUEUED_GRACE), now.minus(FRESH_FOR), BATCH);
		if (!due.isEmpty()) {
			log.info("Retrying {} push deliveries", due.size());
			this.dispatcher.dispatch(due);
		}
		return due.size();
	}

}

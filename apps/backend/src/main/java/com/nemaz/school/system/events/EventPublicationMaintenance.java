package com.nemaz.school.system.events;

import com.nemaz.school.system.SchoolProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the transactional outbox healthy (spec §91):
 * <ul>
 * <li>redelivers events whose listener failed or whose instance died mid-processing,
 * giving at-least-once delivery, so listeners must be idempotent;</li>
 * <li>purges completed publications after a retention window.</li>
 * </ul>
 */
@Component
class EventPublicationMaintenance {

	private static final Logger log = LoggerFactory.getLogger(EventPublicationMaintenance.class);

	private final IncompleteEventPublications incomplete;

	private final CompletedEventPublications completed;

	private final SchoolProperties.Events settings;

	EventPublicationMaintenance(IncompleteEventPublications incomplete, CompletedEventPublications completed,
			SchoolProperties props) {
		this.incomplete = incomplete;
		this.completed = completed;
		this.settings = props.events();
	}

	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
	@SchedulerLock(name = "events.resubmit-incomplete", lockAtMostFor = "PT5M")
	void resubmitIncomplete() {
		this.incomplete.resubmitIncompletePublicationsOlderThan(this.settings.resubmitOlderThan());
	}

	@Scheduled(cron = "0 30 3 * * *", zone = "UTC")
	@SchedulerLock(name = "events.purge-completed", lockAtMostFor = "PT30M")
	void purgeCompleted() {
		this.completed.deletePublicationsOlderThan(this.settings.purgeCompletedOlderThan());
		log.info("Purged completed event publications older than {}", this.settings.purgeCompletedOlderThan());
	}

}

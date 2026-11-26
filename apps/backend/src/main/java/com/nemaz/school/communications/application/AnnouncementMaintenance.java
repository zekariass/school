package com.nemaz.school.communications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The communications jobs. Both are idempotent and safe on several instances (ShedLock) and when run twice. The locked
 * methods return {@code Integer}: ShedLock refuses primitive return types.
 * <ul>
 * <li><b>Scheduled announcements</b> (every minute): publishes the ones whose time has come, each in its own transaction,
 * as their author ({@link AnnouncementService#publishDue}); one that cannot be sent goes back to DRAFT and its author is told.</li>
 * <li><b>Delivery resume</b> (every minute): continues interrupted deliveries (announcements published over two minutes
 * ago whose recipients are not all notified, notices not completed), sends the email and SMS that came due (the end of the
 * recipients' quiet hours), and frees rows a crashed sender left claimed.</li>
 * </ul>
 */
@Component
public class AnnouncementMaintenance {

	static final Duration GRACE = Duration.ofMinutes(2);

	private static final int BATCH = 50;

	private static final Logger log = LoggerFactory.getLogger(AnnouncementMaintenance.class);

	private final AnnouncementService announcements;

	private final AnnouncementDelivery delivery;

	private final ChannelDispatcher channels;

	private final Clock clock;

	AnnouncementMaintenance(AnnouncementService announcements, AnnouncementDelivery delivery, ChannelDispatcher channels,
			Clock clock) {
		this.announcements = announcements;
		this.delivery = delivery;
		this.channels = channels;
		this.clock = clock;
	}

	@Scheduled(cron = "10 * * * * *", zone = "UTC")
	@SchedulerLock(name = "communications.publish-scheduled", lockAtMostFor = "PT5M")
	public Integer publishScheduledJob() {
		return publishScheduled();
	}

	/** One pass of the scheduled-announcement job (also what tests call); returns how many were published. */
	public Integer publishScheduled() {
		int published = 0;
		List<UUID> due = this.announcements.dueIds(BATCH);
		for (UUID id : due) {
			try {
				if (this.announcements.publishDue(id)) {
					published++;
				}
			}
			catch (RuntimeException ex) {
				// left SCHEDULED: the next run tries again; one bad announcement never blocks the others
				log.warn("Scheduled announcement {} could not be published: {}", id, ex.getClass().getSimpleName());
			}
		}
		if (!due.isEmpty()) {
			log.info("Scheduled announcements due: {}, published: {}", due.size(), published);
		}
		return published;
	}

	@Scheduled(cron = "40 * * * * *", zone = "UTC")
	@SchedulerLock(name = "communications.resume-deliveries", lockAtMostFor = "PT10M")
	public Integer resumeDeliveriesJob() {
		return resumeDeliveries();
	}

	/** One pass of the resume job (also what tests call); returns how many pieces of work it did. */
	public Integer resumeDeliveries() {
		Instant before = this.clock.instant().minus(GRACE);
		int work = this.channels.recoverStuck();
		for (UUID id : this.delivery.undelivered(before, BATCH)) {
			work += guarded(() -> this.delivery.deliver(id), id);
		}
		for (UUID id : this.delivery.openNotices(before, BATCH)) {
			work += guarded(() -> this.delivery.deliverNotice(id), id);
		}
		for (UUID id : this.channels.announcementsWithDueChannels(BATCH)) {
			work += guarded(() -> this.channels.sendDue(id), id);
		}
		if (work > 0) {
			log.info("Announcement delivery resumed: {} pieces of work", work);
		}
		return work;
	}

	private static int guarded(java.util.function.IntSupplier work, UUID id) {
		try {
			return work.getAsInt();
		}
		catch (RuntimeException ex) {
			log.warn("Announcement {} delivery step failed: {}", id, ex.getClass().getSimpleName());
			return 0;
		}
	}

}

package com.nemaz.school.media.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.nemaz.school.media.domain.Media;
import com.nemaz.school.media.domain.ObjectStorage;
import com.nemaz.school.media.infrastructure.MediaRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes uploads that were requested but never completed: after 24 hours the row and any
 * object the client left behind are deleted. Completing later gives 404, and the client simply
 * asks for a new upload.
 */
@Component
public class MediaMaintenance {

	private static final Logger log = LoggerFactory.getLogger(MediaMaintenance.class);

	static final Duration STALE_AFTER = Duration.ofHours(24);

	private static final int BATCH = 200;

	private final MediaRepository media;

	private final ObjectStorage storage;

	private final Clock clock;

	MediaMaintenance(MediaRepository media, ObjectStorage storage, Clock clock) {
		this.media = media;
		this.storage = storage;
		this.clock = clock;
	}

	@Scheduled(cron = "0 15 * * * *", zone = "UTC")
	@SchedulerLock(name = "media.purge-stale-uploads", lockAtMostFor = "PT30M")
	@Transactional
	public int purgeStaleUploads() {
		int total = 0;
		List<Media> stale;
		do {
			stale = this.media.findStalePending(this.clock.instant().minus(STALE_AFTER), PageRequest.of(0, BATCH));
			for (Media m : stale) {
				this.storage.delete(m.getObjectKey());
			}
			this.media.deleteAll(stale);
			this.media.flush();
			total += stale.size();
		}
		while (stale.size() == BATCH);
		if (total > 0) {
			log.info("Removed {} stale media uploads", total);
		}
		return total;
	}

}

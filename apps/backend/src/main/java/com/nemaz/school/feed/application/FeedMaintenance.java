package com.nemaz.school.feed.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The feed's recurring jobs. Both are idempotent and safe to run on several instances (ShedLock) and repeatedly.
 * <ul>
 * <li><b>Scheduled posts</b> (every minute): publishes the posts whose time has come, each in its own transaction, with
 * the consent gate re-checked at that moment ({@link PostService#publishDue}).</li>
 * <li><b>Story expiry</b> (every five minutes): marks stories past {@code expires_at} EXPIRED, and deletes the photo and
 * text of stories that expired more than seven days ago. Visibility never depends on this job: reads compare
 * {@code expires_at} with the clock.</li>
 * </ul>
 * The methods return {@code Integer}: ShedLock refuses primitive return types.
 */
@Component
public class FeedMaintenance {

	private static final Logger log = LoggerFactory.getLogger(FeedMaintenance.class);

	/** How long the author and moderators can still read an expired story's photo. */
	static final Duration EXPIRED_RETENTION = Duration.ofDays(7);

	private static final int PURGE_BATCH = 200;

	private final PostService posts;

	private final JdbcClient jdbc;

	private final FeedMedia media;

	private final Clock clock;

	FeedMaintenance(PostService posts, JdbcClient jdbc, FeedMedia media, Clock clock) {
		this.posts = posts;
		this.jdbc = jdbc;
		this.media = media;
		this.clock = clock;
	}

	@Scheduled(cron = "5 * * * * *", zone = "UTC")
	@SchedulerLock(name = "feed.publish-scheduled-posts", lockAtMostFor = "PT5M")
	public Integer publishScheduledPosts() {
		return publishDue();
	}

	/** One pass of the scheduled-post job; also what tests and operators call directly. Returns how many were published. */
	public Integer publishDue() {
		int published = 0;
		List<UUID> due = this.posts.dueIds();
		for (UUID id : due) {
			if (this.posts.publishDue(id)) {
				published++;
			}
		}
		if (!due.isEmpty()) {
			log.info("Scheduled posts due: {}, published: {}", due.size(), published);
		}
		return published;
	}

	@Scheduled(cron = "20 */5 * * * *", zone = "UTC")
	@SchedulerLock(name = "feed.expire-stories", lockAtMostFor = "PT10M")
	public Integer expireStoriesJob() {
		return expireStories();
	}

	/** One pass of the story job; returns how many stories were expired plus how many were purged. */
	@Transactional
	public Integer expireStories() {
		Instant now = this.clock.instant();
		int expired = this.jdbc.sql("""
				update stories set status = 'EXPIRED', updated_at = :now, version = version + 1
				where status = 'PUBLISHED' and expires_at <= :now""")
			.param("now", Timestamp.from(now))
			.update();
		record Old(UUID id, UUID mediaId) {
		}
		List<Old> old = this.jdbc.sql("""
				select id, media_id from stories where status = 'EXPIRED' and expires_at <= :cutoff
				order by expires_at, id limit :n""")
			.param("cutoff", Timestamp.from(now.minus(EXPIRED_RETENTION)))
			.param("n", PURGE_BATCH)
			.query((rs, i) -> new Old(rs.getObject("id", UUID.class), rs.getObject("media_id", UUID.class)))
			.list();
		for (Old story : old) {
			this.jdbc.sql("""
					update stories set status = 'DELETED', media_id = null, caption = null, deleted_at = :now, updated_at = :now,
					       version = version + 1
					where id = :id""")
				.param("now", Timestamp.from(now))
				.param("id", story.id())
				.update();
			if (story.mediaId() != null) {
				this.media.discard(List.of(story.mediaId()));
			}
		}
		if (expired > 0 || !old.isEmpty()) {
			log.info("Stories expired: {}, purged: {}", expired, old.size());
		}
		return expired + old.size();
	}

}

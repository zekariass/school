package com.nemaz.school.feed.application;

import com.nemaz.school.media.MediaDeleted;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Forgets media that was deleted (by its uploader, a moderator, a post deletion or a rejected contribution). Idempotent:
 * clearing a reference that is already gone changes nothing. A story left with neither photo nor text is deleted.
 */
@Component
class FeedMediaEvents {

	private final JdbcClient jdbc;

	FeedMediaEvents(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@ApplicationModuleListener
	void on(MediaDeleted event) {
		this.jdbc.sql("delete from post_media where media_id = :m").param("m", event.mediaId()).update();
		this.jdbc.sql("""
				update stories set media_id = null,
				       status = case when caption is null then 'DELETED' else status end,
				       deleted_at = case when caption is null then coalesce(deleted_at, now()) else deleted_at end,
				       updated_at = now()
				where media_id = :m""")
			.param("m", event.mediaId())
			.update();
	}

}

package com.nemaz.school.feed.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.feed.application.FeedModels.FeedMediaItem;
import com.nemaz.school.feed.application.FeedModels.FeedMediaRef;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaKind;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.media.MediaSummary;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Attaching media to posts and stories and describing it for responses. Uploads go straight to object storage
 * (the media module); here a READY upload of purpose POST or STORY, made by the author for the class or school the
 * content is in, becomes the content's media. A file belongs to at most one post or story, so an upload cannot be
 * reused to carry content into another audience. Only photos are accepted until video processing exists (Phase 3).
 */
@Component
class FeedMedia {

	static final int MAX_MEDIA_PER_POST = 10;

	/** Kinds the feed accepts now. Video is a Phase 3 feature; the model (media_kind) does not block it. */
	static final Set<MediaKind> SUPPORTED_KINDS = Set.of(MediaKind.IMAGE);

	private final MediaService media;

	private final JdbcClient jdbc;

	FeedMedia(MediaService media, JdbcClient jdbc) {
		this.media = media;
		this.jdbc = jdbc;
	}

	/** Validates the references (count, no duplicates) before anything is written. */
	static void validate(List<FeedMediaRef> refs) {
		if (refs.size() > MAX_MEDIA_PER_POST) {
			throw ApiException.badRequest("feed.too_many_media", MAX_MEDIA_PER_POST);
		}
		Set<UUID> seen = new HashSet<>();
		for (FeedMediaRef ref : refs) {
			if (!seen.add(ref.mediaId())) {
				throw ApiException.badRequest("feed.media_already_attached");
			}
		}
	}

	/** Checks one upload and returns its info; {@code purpose} and {@code context} must match how it was uploaded. */
	MediaInfo requireUsable(CurrentUser user, UUID mediaId, MediaPurpose purpose, MediaContext context) {
		MediaInfo info = this.media.requireAttachable(mediaId, purpose, context, user);
		if (!SUPPORTED_KINDS.contains(info.kind())) {
			throw ApiException.badRequest("feed.media_kind_not_allowed");
		}
		boolean used = this.jdbc
			.sql("select exists (select 1 from post_media where media_id = :m) or exists (select 1 from stories where media_id = :m)")
			.param("m", mediaId)
			.query(Boolean.class)
			.single();
		if (used) {
			throw ApiException.conflict("feed.media_already_attached");
		}
		return info;
	}

	/** Attaches the files to the post in the given order (the first is sort order 0). */
	void attachToPost(CurrentUser user, UUID postId, MediaContext context, List<FeedMediaRef> refs) {
		int order = 0;
		for (FeedMediaRef ref : refs) {
			requireUsable(user, ref.mediaId(), MediaPurpose.POST, context);
			insert(postId, ref, order++);
		}
	}

	/**
	 * Makes the post's media exactly {@code refs}: files no longer listed are removed (and discarded), new ones are
	 * attached, kept ones get the new order and caption. Returns true when the set of files changed.
	 */
	boolean replaceOnPost(CurrentUser user, UUID postId, MediaContext context, List<FeedMediaRef> refs) {
		Set<UUID> existing = new HashSet<>(this.jdbc.sql("select media_id from post_media where post_id = :p")
			.param("p", postId)
			.query(UUID.class)
			.list());
		Set<UUID> wanted = new HashSet<>();
		refs.forEach((r) -> wanted.add(r.mediaId()));
		List<UUID> removed = new ArrayList<>();
		for (UUID id : existing) {
			if (!wanted.contains(id)) {
				removed.add(id);
			}
		}
		for (UUID id : removed) {
			this.jdbc.sql("delete from post_media where post_id = :p and media_id = :m").param("p", postId).param("m", id).update();
		}
		int order = 0;
		boolean added = false;
		for (FeedMediaRef ref : refs) {
			if (existing.contains(ref.mediaId())) {
				this.jdbc.sql("update post_media set sort_order = :o, caption = :c where post_id = :p and media_id = :m")
					.param("o", order++)
					.param("c", ref.caption())
					.param("p", postId)
					.param("m", ref.mediaId())
					.update();
			}
			else {
				requireUsable(user, ref.mediaId(), MediaPurpose.POST, context);
				insert(postId, ref, order++);
				added = true;
			}
		}
		// Nothing references a removed file any more: delete it (its objects go with it).
		removed.forEach(this.media::discard);
		return added || !removed.isEmpty();
	}

	private void insert(UUID postId, FeedMediaRef ref, int order) {
		this.jdbc.sql("insert into post_media (post_id, media_id, sort_order, caption) values (:p, :m, :o, :c)")
			.param("p", postId)
			.param("m", ref.mediaId())
			.param("o", order)
			.param("c", blankToNull(ref.caption()))
			.update();
	}

	List<UUID> mediaIdsOfPost(UUID postId) {
		return this.jdbc.sql("select media_id from post_media where post_id = :p order by sort_order, media_id")
			.param("p", postId)
			.query(UUID.class)
			.list();
	}

	/** The media items of several posts, in order, for READY media only. */
	Map<UUID, List<FeedMediaItem>> itemsOfPosts(List<UUID> postIds) {
		Map<UUID, List<FeedMediaItem>> result = new LinkedHashMap<>();
		if (postIds.isEmpty()) {
			return result;
		}
		record Row(UUID post, UUID media, int order, String caption) {
		}
		List<Row> rows = this.jdbc
			.sql("select post_id, media_id, sort_order, caption from post_media where post_id in (:ids) order by post_id, sort_order, media_id")
			.param("ids", postIds)
			.query((rs, i) -> new Row(Rows.uuid(rs, "post_id"), Rows.uuid(rs, "media_id"), rs.getInt("sort_order"),
					rs.getString("caption")))
			.list();
		Map<UUID, MediaSummary> summaries = this.media.summaries(rows.stream().map(Row::media).toList());
		for (Row row : rows) {
			MediaSummary summary = summaries.get(row.media());
			if (summary != null) {
				result.computeIfAbsent(row.post(), (k) -> new ArrayList<>()).add(item(summary, row.order(), row.caption()));
			}
		}
		return result;
	}

	FeedMediaItem item(MediaSummary summary, int order, String caption) {
		return new FeedMediaItem(summary.id(), order, caption, summary.kind(), summary.width(), summary.height(),
				summary.variants());
	}

	FeedMediaItem itemOf(UUID mediaId) {
		if (mediaId == null) {
			return null;
		}
		return this.media.summary(mediaId).map((s) -> item(s, 0, null)).orElse(null);
	}

	/** Releases the files of a deleted post or story. */
	void discard(List<UUID> mediaIds) {
		mediaIds.forEach(this.media::discard);
	}

	static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

}

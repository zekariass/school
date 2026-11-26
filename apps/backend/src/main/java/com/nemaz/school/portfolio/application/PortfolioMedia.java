package com.nemaz.school.portfolio.application;

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
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaKind;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.media.MediaSummary;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemType;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioMediaView;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Attaching files to items and describing them. Uploads go straight to object storage (media module, purpose PORTFOLIO, context
 * SCHOOL); here a READY upload of the creator becomes the item's file. A file belongs to one item, so an upload cannot be reused to
 * carry content into another audience. Kinds follow the item type; audio and video are not accepted (deferred).
 */
@Component
class PortfolioMedia {

	static final int MAX_FILES = 10;

	private final MediaService media;

	private final JdbcClient jdbc;

	PortfolioMedia(MediaService media, JdbcClient jdbc) {
		this.media = media;
		this.jdbc = jdbc;
	}

	/** The media kinds an item type accepts (empty for TEXT). */
	static Set<MediaKind> kindsOf(PortfolioItemType type) {
		return switch (type) {
			case PHOTO, DRAWING -> Set.of(MediaKind.IMAGE);
			case SCAN -> Set.of(MediaKind.IMAGE, MediaKind.DOCUMENT);
			case DOCUMENT -> Set.of(MediaKind.DOCUMENT);
			case TEXT, AUDIO, VIDEO -> Set.of();
		};
	}

	/** Checks count and duplicates before anything is written. */
	static void validate(PortfolioItemType type, List<UUID> mediaIds) {
		if (mediaIds.isEmpty()) {
			return;
		}
		if (kindsOf(type).isEmpty()) {
			throw ApiException.badRequest("portfolio.media_not_allowed");
		}
		if (mediaIds.size() > MAX_FILES) {
			throw ApiException.badRequest("portfolio.too_many_media", MAX_FILES);
		}
		if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
			throw ApiException.badRequest("portfolio.media_already_attached");
		}
	}

	/** One upload: the creator's own READY PORTFOLIO upload of the school, of an accepted kind, not used by another item. */
	void requireUsable(CurrentUser user, UUID schoolId, PortfolioItemType type, UUID mediaId, UUID exceptItem) {
		MediaInfo info = this.media.requireAttachable(mediaId, MediaPurpose.PORTFOLIO, MediaContext.school(schoolId), user);
		if (!kindsOf(type).contains(info.kind())) {
			throw ApiException.badRequest("portfolio.media_kind_not_allowed");
		}
		UUID owner = this.jdbc.sql("select item_id from portfolio_attachments where media_id = :m")
			.param("m", mediaId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (owner != null && !owner.equals(exceptItem)) {
			throw ApiException.conflict("portfolio.media_already_attached");
		}
	}

	void attach(CurrentUser user, UUID schoolId, PortfolioItemType type, UUID itemId, List<UUID> mediaIds) {
		int order = 0;
		for (UUID id : mediaIds) {
			requireUsable(user, schoolId, type, id, null);
			insert(itemId, id, order++);
		}
	}

	/**
	 * Makes the item's files exactly {@code mediaIds}: files no longer listed are removed and discarded, new ones attached, kept
	 * ones reordered. Returns true when the set of files changed.
	 */
	boolean replace(CurrentUser user, UUID schoolId, PortfolioItemType type, UUID itemId, List<UUID> mediaIds) {
		Set<UUID> existing = new HashSet<>(mediaIdsOf(itemId));
		Set<UUID> wanted = new HashSet<>(mediaIds);
		List<UUID> removed = new ArrayList<>();
		for (UUID id : existing) {
			if (!wanted.contains(id)) {
				removed.add(id);
			}
		}
		boolean added = false;
		int order = 0;
		for (UUID id : mediaIds) {
			if (existing.contains(id)) {
				this.jdbc.sql("update portfolio_attachments set sort_order = :o where item_id = :i and media_id = :m")
					.param("o", order++)
					.param("i", itemId)
					.param("m", id)
					.update();
			}
			else {
				requireUsable(user, schoolId, type, id, itemId);
				insert(itemId, id, order++);
				added = true;
			}
		}
		for (UUID id : removed) {
			this.jdbc.sql("delete from portfolio_attachments where item_id = :i and media_id = :m").param("i", itemId).param("m", id).update();
			this.media.discard(id);
		}
		return added || !removed.isEmpty();
	}

	private void insert(UUID itemId, UUID mediaId, int order) {
		this.jdbc.sql("insert into portfolio_attachments (item_id, media_id, sort_order) values (:i, :m, :o)")
			.param("i", itemId)
			.param("m", mediaId)
			.param("o", order)
			.update();
	}

	List<UUID> mediaIdsOf(UUID itemId) {
		return this.jdbc.sql("select media_id from portfolio_attachments where item_id = :i order by sort_order, media_id")
			.param("i", itemId)
			.query(UUID.class)
			.list();
	}

	/** The READY files of several items, in order. */
	Map<UUID, List<PortfolioMediaView>> viewsOf(List<UUID> itemIds) {
		Map<UUID, List<PortfolioMediaView>> result = new LinkedHashMap<>();
		if (itemIds.isEmpty()) {
			return result;
		}
		record Row(UUID item, UUID media, int order) {
		}
		List<Row> rows = this.jdbc
			.sql("select item_id, media_id, sort_order from portfolio_attachments where item_id in (:ids) order by item_id, sort_order, media_id")
			.param("ids", itemIds)
			.query((rs, i) -> new Row(Rows.uuid(rs, "item_id"), Rows.uuid(rs, "media_id"), rs.getInt("sort_order")))
			.list();
		Map<UUID, MediaSummary> summaries = this.media.summaries(rows.stream().map(Row::media).toList());
		for (Row row : rows) {
			MediaSummary s = summaries.get(row.media());
			if (s != null) {
				result.computeIfAbsent(row.item(), (k) -> new ArrayList<>()).add(new PortfolioMediaView(s.id(), row.order(), s.kind(),
						s.mimeType(), s.width(), s.height(), s.variants()));
			}
		}
		return result;
	}

	/** Which of the items have any file at all (also files not READY yet). */
	Set<UUID> itemsWithFiles(List<UUID> itemIds) {
		if (itemIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(this.jdbc.sql("select distinct item_id from portfolio_attachments where item_id in (:ids)")
			.param("ids", itemIds)
			.query(UUID.class)
			.list());
	}

	/** Releases the files of a deleted item (the objects go with them) and removes the attachments. */
	void discardAll(UUID itemId) {
		List<UUID> ids = mediaIdsOf(itemId);
		this.jdbc.sql("delete from portfolio_attachments where item_id = :i").param("i", itemId).update();
		ids.forEach(this.media::discard);
	}

}
